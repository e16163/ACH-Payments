package com.achpayments.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.achpayments.inbound.FedInboundSimulator.Sample;
import com.achpayments.inbound.ReceivingProcessingController.FileRequest;
import com.achpayments.inbound.ReceivingProcessingController.PostingRequest;
import com.achpayments.inbound.ReceivingProcessingController.ValidateRequest;

/**
 * Ba's blueprint, ACH Receiving Processing steps 1-7, on the real test databases with V17's seed accounts (see the comment
 * there for which account shows which rule). Needs TEST_JDBC_URL (README section 7).
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class ReceivingBlueprintIntegrationTest {

    private static final Principal OPS = () -> "achrecv";
    private static final LocalDate BUSINESS_DAY = LocalDate.of(2026, 11, 9);

    @Autowired
    ReceivingController receiving;
    @Autowired
    ReceivingProcessingController api;
    @Autowired
    FedInboundSimulator fed;
    @Autowired
    ReceivingWindow window;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${app.db.receiving}")
    String recv;
    @Value("${app.fed.inbound-dir}")
    Path inbound;
    @Value("${app.core.posting-dir}")
    Path postingDir;

    @BeforeEach
    @AfterEach
    void clean() throws IOException {
        jdbc.update("DELETE FROM " + recv + ".ACHRecvReturn WHERE lastModBy = 'ACH_SYS'");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvAddenda");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvTransaction WHERE fileID LIKE 'ACHFILE-2%'");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvBatch WHERE fileID LIKE 'ACHFILE-2%'");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvFile WHERE fileID LIKE 'ACHFILE-2%'");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvPostingJob");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvWindowRun");
        jdbc.update("DELETE FROM ACHMemoPost WHERE acctNo IN (SELECT acctNo FROM SimCoreAccount WHERE memberNo LIKE 'M300%')");
        jdbc.update("UPDATE SimCoreAccount SET ledgerBal = 2000.00, availBal = 2000.00 WHERE memberNo LIKE 'M300%'");
        jdbc.update("UPDATE SimCoreAccount SET ledgerBal = 5.00, availBal = 5.00 WHERE acctNo = '987654410019'");
        for (Path dir : List.of(inbound, inbound.resolveSibling("archive"), inbound.resolveSibling("quarantine"), postingDir)) {
            if (Files.exists(dir)) {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(dir)).forEach(p -> p.toFile().delete());
                }
            }
        }
    }

    /** The Fed delivers the sample, then a processing window picks it up (steps 2-6). */
    private String receive(Sample sample) throws IOException {
        receiving.receive(new ReceivingController.ReceiveRequest(sample), OPS);
        List<ReceivingEngine.Result> results = window.run("09:00", null, "achrecv").files();
        assertEquals(ReceivingEngine.VALIDATED, results.get(0).processStatus(), results.get(0).message());
        return results.get(0).fileID();
    }

    private Map<String, Object> entry(String fileID, String account) {
        return jdbc.queryForMap("SELECT * FROM " + recv + ".ACHRecvTransaction WHERE fileID = ? AND recvAcctID = ? ORDER BY tranID LIMIT 1",
                fileID, account);
    }

    private BigDecimal balance(String column, String account) {
        return jdbc.queryForObject("SELECT " + column + " FROM SimCoreAccount WHERE acctNo = ?", BigDecimal.class, account);
    }

    @Test
    void aReceivedFileWaitsForAWindowAndTheWindowRunIsKept() throws IOException {
        receiving.receive(new ReceivingController.ReceiveRequest(Sample.PAYROLL), OPS);
        assertEquals(List.of("PAYROLL.ach"), api.windows().waitingFiles()); // nothing happens until a window
        assertEquals(List.of("06:00", "09:00", "12:00", "15:00", "18:00"),
                api.windows().schedules().stream().map(ReceivingWindow.Window::windowTime).toList()); // from ACHRecvSchedule

        window.run("09:00", null, "achrecv");

        ReceivingProcessingController.WindowsStatus today = api.windows();
        assertEquals(List.of(), today.waitingFiles());
        ReceivingProcessingController.WindowRun run = today.runs().get(today.runs().size() - 1);
        assertEquals(List.of("09:00", 1, 1, 50, 47, 2, 1, 3, 47, "achrecv"), List.of(run.windowTime(), run.files(), run.validated(), run.entries(),
                run.matched(), run.autoCorrected(), run.manualReview(), run.rejected(), run.memoPosted(), run.runBy()));
        assertEquals(2, jdbc.queryForObject("SELECT scheduleID FROM " + recv + ".ACHRecvWindowRun ORDER BY runID DESC LIMIT 1", Integer.class));
    }

    @Test
    void payrollCreditsAreMatchedCorrectedRejectedAndMemoPosted() throws IOException {
        String fileID = receive(Sample.PAYROLL);

        Map<String, Object> john = entry(fileID, "987654321019"); // exact match, trusted payroll credit
        assertEquals("MEMO_POSTED", john.get("processStatus"));
        assertEquals("DDA_CR", john.get("coreTranCode"));
        assertTrue(john.get("memoPostedTm") != null);
        assertEquals(new BigDecimal("3500.00"), balance("availBal", "987654321019"));
        assertEquals(new BigDecimal("2000.00"), balance("ledgerBal", "987654321019")); // final posting is step 7

        Map<String, Object> james = entry(fileID, "987654329019");
        assertEquals("0987654329019", james.get("correctedAcctNo"));
        assertEquals("ZERO_PADDING", james.get("processReason"));
        assertEquals("MEMO_POSTED", james.get("processStatus"));

        Map<String, Object> laura = entry(fileID, "987654328019");
        assertEquals("555000328019", laura.get("correctedAcctNo"));
        assertEquals("KNOWN_ALIAS", laura.get("processReason"));

        assertEquals("R03", entry(fileID, "987654330019").get("returnCode"));
        assertEquals("R16", entry(fileID, "987654327019").get("returnCode"));
        assertEquals("R02", entry(fileID, "987654326019").get("returnCode"));
        assertEquals("REJECTED", entry(fileID, "987654326019").get("processStatus"));

        Map<String, Object> ret = jdbc.queryForMap("SELECT * FROM " + recv + ".ACHRecvReturn WHERE recvTraceNo = ?",
                entry(fileID, "987654330019").get("traceNo"));
        assertEquals("R03", ret.get("returnCode"));
        assertEquals("PENDING", ret.get("returnStatus"));
        assertEquals("198765435", ret.get("returnRtn")); // the ODFI from the trace number, with its check digit
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvTransaction WHERE fileID = ? AND processStatus = 'REJECTED'",
                Integer.class, fileID));
    }

    @Test
    void retailDebitsAreHeldOrReturnedAndUntrustedCreditsWait() throws IOException {
        String fileID = receive(Sample.RETAIL);

        assertEquals("MEMO_POSTED", entry(fileID, "987654321019").get("processStatus")); // Alice's $50.00 debit is held
        assertEquals(new BigDecimal("1950.00"), balance("availBal", "987654321019"));
        assertEquals("R10", entry(fileID, "987654322019").get("returnCode")); // WEB debit from a blocked originator
        assertEquals("R08", entry(fileID, "987654323019").get("returnCode")); // stop payment
        assertEquals("R01", entry(fileID, "987654410019").get("returnCode")); // $19.99 against $5.00
        assertEquals("R03", entry(fileID, "987654330019").get("returnCode"));

        Map<String, Object> credit = jdbc.queryForMap("SELECT * FROM " + recv + ".ACHRecvTransaction WHERE fileID = ? AND stdEntryCode = 'CCD' "
                + "AND recvAcctID = '987654501019'", fileID); // GLOBAL B2B AR isn't a trusted Company ID
        assertEquals("RECEIVED", credit.get("processStatus"));
        assertEquals("DDA_CR", credit.get("coreTranCode"));
        assertNull(credit.get("memoPostedTm"));
    }

    @Test
    void aCreditFromBasEarlyPostingCompanyIsMadeAvailableAtOnce() throws IOException {
        fed.deliver(Sample.RETAIL);
        String raw = Files.readString(inbound.resolve("RETAIL.ach"));
        Files.delete(inbound.resolve("RETAIL.ach"));
        Files.delete(inbound.resolve("RETAIL.ach.sig"));
        String fileID = api.validate(new ValidateRequest(null, raw, true)).fileId();
        api.extract(new FileRequest(fileID, null));
        api.transcodes(new FileRequest(fileID, null));
        api.rejections(new FileRequest(fileID, null));
        // the GLOBAL B2B AR credit, as if it came from Company ID 23416789 (Ba's sample rule, seeded in V20)
        jdbc.update("UPDATE " + recv + ".ACHRecvTransaction SET companyID = '23416789' WHERE fileID = ? AND stdEntryCode = 'CCD' "
                + "AND recvAcctID = '987654501019'", fileID);

        api.memoPost(new FileRequest(fileID, true));

        Map<String, Object> credit = jdbc.queryForMap("SELECT processStatus, memoPostedTm, creditFlag FROM " + recv
                + ".ACHRecvTransaction WHERE fileID = ? AND recvAcctID = '987654501019'", fileID);
        assertEquals("MEMO_POSTED", credit.get("processStatus"));
        assertTrue(credit.get("memoPostedTm") != null);
    }

    @Test
    void thePostingFilePostsReadyEntriesOnceAndSkipsRejectedOnes() throws IOException {
        String fileID = receive(Sample.RETAIL);

        CorePostingFileJob.Result result = api.postingFile(new PostingRequest(BUSINESS_DAY, "CORE_FLAT_FILE"));
        assertEquals("JOB-POST-20261109-EOD", result.postingJobId());
        assertEquals("CORE_ACH_POSTING_20261109.DAT", result.postingFileName());
        assertEquals("READY_FOR_CORE_INGESTION", result.status());

        // Alice's debit was held in step 6: only the ledger moves now. B2B credits weren't memo posted: both balances move.
        assertEquals(new BigDecimal("1950.00"), balance("ledgerBal", "987654321019"));
        assertEquals(new BigDecimal("1950.00"), balance("availBal", "987654321019"));
        assertEquals(new BigDecimal("7000.00"), balance("ledgerBal", "987654501019"));
        assertEquals(new BigDecimal("7000.00"), balance("availBal", "987654501019"));
        assertEquals("POSTED", entry(fileID, "987654501019").get("processStatus"));
        assertEquals("REJECTED", entry(fileID, "987654322019").get("processStatus"));

        List<String> lines = Files.readAllLines(postingDir.resolve(result.postingFileName()));
        assertEquals("H|2026-11-09|JOB-POST-20261109-EOD|" + result.totalRecordsPosted(), lines.get(0));
        assertEquals("T|" + result.totalRecordsPosted() + "|" + result.totalDebitVolume() + "|" + result.totalCreditVolume(),
                lines.get(lines.size() - 1));
        assertEquals(result.totalRecordsPosted() + 2, lines.size());
        assertTrue(lines.stream().noneMatch(l -> l.contains("|987654322019|")));

        CorePostingFileJob.Result again = api.postingFile(new PostingRequest(BUSINESS_DAY, null));
        assertEquals(result, again);
        assertEquals(new BigDecimal("7000.00"), balance("ledgerBal", "987654501019")); // not posted twice
    }

    @Test
    void validateThenExtractThenEachStepByEndpoint() throws IOException {
        fed.deliver(Sample.PAYROLL);
        String raw = Files.readString(inbound.resolve("PAYROLL.ach"));
        Files.delete(inbound.resolve("PAYROLL.ach"));
        Files.delete(inbound.resolve("PAYROLL.ach.sig"));

        var v = api.validate(new ValidateRequest("EXEC-REC-TEST", raw, true));
        assertEquals("PASSED", v.validationStatus());
        assertEquals(70, v.totalRecords());
        assertTrue(v.blockCountValid() && v.hashValidationPassed() && v.rawTextStored());
        assertEquals(List.of(), v.errors());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvTransaction WHERE fileID = ?", Integer.class, v.fileId()));

        var x = api.extract(new FileRequest(v.fileId(), null));
        assertEquals(5, x.batchesExtracted());
        assertEquals(50, x.transactionsExtracted());
        assertEquals(new BigDecimal("129450.00"), x.summary().totalCreditAmount());
        assertEquals("PERSISTED", x.status());
        assertThrows(IllegalArgumentException.class, () -> api.extract(new FileRequest(v.fileId(), null))); // only once

        var t = api.transcodes(new FileRequest(v.fileId(), null));
        assertEquals(50, t.processedCount());
        assertEquals(2, t.autoCorrectedAccounts());
        assertEquals(1, t.manualReviewRequired());
        assertEquals(47, t.matchedAccounts());

        var r = api.rejections(new FileRequest(v.fileId(), null));
        assertEquals(3, r.rejectedCount());
        assertEquals(47, r.acceptedCount());

        var m = api.memoPost(new FileRequest(v.fileId(), true));
        assertEquals(47, m.memoPostedCount());
        assertEquals("MEMO_POST_COMPLETED", m.status());
    }

    @Test
    void aFileThatFailsValidationReportsItsErrorsAndCannotBeExtracted() throws IOException {
        fed.deliver(Sample.OUT_OF_BALANCE);
        String raw = Files.readString(inbound.resolve("OUT_OF_BALANCE.ach"));
        Files.delete(inbound.resolve("OUT_OF_BALANCE.ach"));
        Files.delete(inbound.resolve("OUT_OF_BALANCE.ach.sig"));

        var v = api.validate(new ValidateRequest(null, raw, true));
        assertEquals("OUT_OF_BALANCE", v.validationStatus());
        assertTrue(!v.errors().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> api.extract(new FileRequest(v.fileId(), null)));
    }

    @Test
    void aTriggeredWindowPicksUpTheWaitingFileAndProcessesIt() throws IOException {
        fed.deliver(Sample.PAYROLL);
        var run = api.trigger("0900", null, OPS);
        assertTrue(run.executionId().startsWith("EXEC-REC-") && run.executionId().endsWith("-0900"));
        assertEquals("COMPLETED", run.status());
        String fileID = jdbc.queryForObject("SELECT fileID FROM " + recv + ".ACHRecvFile WHERE fileID LIKE 'ACHFILE-2%'", String.class);
        assertEquals("MEMO_POSTED", entry(fileID, "987654321019").get("processStatus"));
        assertThrows(IllegalArgumentException.class, () -> api.trigger("0700", null, OPS));
    }
}
