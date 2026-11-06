package com.achpayments.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.achpayments.common.BankingCalendar;
import com.achpayments.nacha.AchFile;
import com.achpayments.nacha.AchFileParser;
import com.achpayments.nacha.AchFileValidator;
import com.achpayments.param.ParamCache;
import com.achpayments.transfer.TransferDates;
import com.achpayments.transfer.TransferRequest;
import com.achpayments.transfer.TransferService;

/** UC-ACH-EOD-01 "Done when", on the real test databases. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class EodFileJobIntegrationTest {

    private static final String MEMBER = "X-EOD-MEMBER";
    private static final String CHECKING = "X-EOD-CHK";

    @Autowired EodFileJob job;
    @Autowired EodFileStore store;
    @Autowired TransferService transfers;
    @Autowired TransferDates dates;
    @Autowired BankingCalendar calendar;
    @Autowired ParamCache params;
    @Autowired JdbcTemplate jdbc;

    @Value("${app.db.origination}")
    String orig;

    @Value("${app.fed.outbound-dir}")
    String outbound;

    private LocalDate date;

    @BeforeEach
    void setUp() {
        date = dates.earliest();
        jdbc.update("INSERT INTO SimCoreAccount (acctNo, memberNo, acctName, acctType, acctOwner, ledgerBal, availBal) "
                + "VALUES (?, ?, 'Test Checking', 'CHECKING', 'TEST MEMBER', 20000.00, 20000.00)", CHECKING, MEMBER);
    }

    @AfterEach
    void cleanUp() {
        failures(0);
        jdbc.update("DELETE FROM AppLog WHERE logMesg LIKE '%FILE\\_%'"); // fileIDs repeat from one test to the next
        jdbc.update("DELETE FROM OfacSdnName WHERE sdnName = 'LATER LISTED PERSON'");
        jdbc.update("DELETE t FROM " + orig + ".ACHOrigTransaction t JOIN " + orig + ".ACHOrigFile f ON f.fileID = t.fileID WHERE f.processID LIKE 'EOD-%'");
        // The audit trail refuses DELETE, by design. TRUNCATE doesn't fire triggers; put the sample row back.
        jdbc.execute("TRUNCATE TABLE " + orig + ".ACHOrigFedAction");
        jdbc.update("INSERT INTO " + orig + ".ACHOrigFedAction (fileID, processID, actionID, actionTm, lastModBy) "
                + "VALUES ('FILE_20261102_01', 'PROC_FED_01', 401, '2026-11-02 08:30:00', 'FED_BATCH_SYS')");
        jdbc.update("DELETE b FROM " + orig + ".ACHOrigBatch b JOIN " + orig + ".ACHOrigFile f ON f.fileID = b.fileID WHERE f.processID LIKE 'EOD-%'");
        jdbc.update("DELETE FROM " + orig + ".ACHOrigFile WHERE processID LIKE 'EOD-%'");
        jdbc.update("DELETE FROM ACHMemoPost WHERE acctNo = ?", CHECKING);
        jdbc.update("DELETE FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ? AND pTransferSerNo IS NOT NULL", MEMBER);
        jdbc.update("DELETE FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ?", MEMBER);
        jdbc.update("DELETE FROM " + orig + ".ACHOrigRecvAccount WHERE memberNo = ?", MEMBER);
        jdbc.update("DELETE FROM SimCoreAccount WHERE acctNo = ?", CHECKING);
    }

    private void failures(int n) {
        jdbc.update("UPDATE AppParam SET paramRawValue = ? WHERE systemName = 'ACH_ORIG' AND paramName = 'FED_SIM_FAIL_ATTEMPTS'", String.valueOf(n));
        params.clear();
    }

    private String submit(String name, String amount, String memo) {
        return transfers.submit(MEMBER, new TransferRequest(CHECKING, name, "021000021", "123456789", "CHECKING",
                new BigDecimal(amount), date, "ONETIME", null, memo)).transferSerNo();
    }

    private String submitSeries(String frequency, int count) {
        return transfers.submit(MEMBER, new TransferRequest(CHECKING, "Jane Smith", "021000021", "123456789", "CHECKING",
                new BigDecimal("100.00"), date, frequency, count, "RENT")).transferSerNo();
    }

    private String status(String serNo) {
        return jdbc.queryForObject("SELECT processStatus FROM " + orig + ".ACHOriginatorTransfer WHERE transferSerNo = ?", String.class, serNo);
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private BigDecimal available() {
        return jdbc.queryForObject("SELECT availBal FROM SimCoreAccount WHERE acctNo = ?", BigDecimal.class, CHECKING);
    }

    private List<String> actions(String fileID) {
        return jdbc.queryForList("SELECT a.actionName FROM " + orig + ".ACHOrigFedAction f JOIN " + orig + ".Action a ON a.actionID = f.actionID "
                + "WHERE f.fileID = ? ORDER BY f.processID, a.actionID", String.class, fileID);
    }

    private AchFile sentFile(String fileID) throws IOException {
        return AchFileParser.parse(Files.readString(Path.of(outbound, fileID + ".ach")));
    }

    // ---- main flow ----

    @Test
    void aTransferEndsProcessedAndSentInAValidFile() throws IOException {
        String serNo = submit("Jane Smith", "1500.00", "Rent");

        EodFileJob.RunResult result = job.run(date);

        assertEquals("SENT", result.fedStatus());
        String fileID = result.fileID();
        assertEquals("PROCESSED", status(serNo));
        var file = jdbc.queryForMap("SELECT fedStatus, sendTm, totalCreditCount, totalCreditAmt, totalDebitAmt FROM " + orig + ".ACHOrigFile WHERE fileID = ?", fileID);
        assertEquals("SENT", file.get("fedStatus"));
        assertTrue(file.get("sendTm") != null);
        assertEquals(1, file.get("totalCreditCount"));
        assertEquals(new BigDecimal("1500.00"), file.get("totalCreditAmt"));
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigBatch WHERE fileID = ? AND stdEntryCode = 'WEB' AND odfiID = '12345678'", fileID));
        var entry = jdbc.queryForMap("SELECT tranCode, recvRoutingNo, recvAcctID, recvName, transactionDiscData, fedStatus, traceNo, individualID, addendaInd FROM "
                + orig + ".ACHOrigTransaction WHERE transferSerNo = ?", serNo);
        assertEquals("22", entry.get("tranCode"));
        assertEquals("021000021", entry.get("recvRoutingNo"));
        assertEquals("S", entry.get("transactionDiscData"));
        assertEquals("SENT", entry.get("fedStatus"));
        assertEquals(serNo.replace("-", ""), entry.get("individualID"));
        assertEquals("0", String.valueOf(entry.get("addendaInd")));
        assertEquals(List.of("FILE_QUEUED", "FED_SEND"), actions(fileID));

        AchFile sent = sentFile(fileID);
        assertEquals(List.of(), AchFileValidator.validate(sent));
        AchFile.Entry line = sent.batches().get(0).entries().get(0);
        assertEquals(150000L, line.amountCents());
        assertEquals(serNo.replace("-", ""), line.individualId());
        assertEquals("JANE SMITH", line.individualName());
        assertEquals(entry.get("traceNo"), line.traceNumber());
        assertEquals("DEMO CU", sent.batches().get(0).header().companyName());
        assertEquals("011000015", sent.header().immediateDestination());
    }

    @Test
    void onlyTransfersDueByTheBusinessDateGoInTheFile() {
        String due = submit("Jane Smith", "10.00", "Rent");
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET effectiveDt = DATE_ADD(effectiveDt, INTERVAL 7 DAY) WHERE transferSerNo = ?", due);
        assertNull(job.run(date).fileID());
        assertEquals("PENDING_BATCH", status(due));
    }

    @Test
    void aSeparateBatchForEachMemoAndDate_andOneForTheSame() throws IOException {
        submit("Jane Smith", "10.00", "Rent");
        submit("Bob Jones", "20.00", "Rent");
        submit("Amy Lee", "30.00", null);
        AchFile sent = sentFile(job.run(date).fileID());
        assertEquals(2, sent.batches().size());
        assertEquals("RENT", sent.batches().get(0).header().companyEntryDescription());
        assertEquals(2, sent.batches().get(0).entries().size());
        assertEquals("TRANSFER", sent.batches().get(1).header().companyEntryDescription()); // blank memo
        var traces = sent.batches().get(0).entries().stream().map(AchFile.Entry::traceNumber).toList();
        assertEquals(traces.stream().sorted().toList(), traces);
    }

    @Test
    void aMemoThatIsAReservedNachaWordIsNotSentAsTheEntryDescription() throws IOException {
        submit("Jane Smith", "10.00", "Reversal");
        AchFile sent = sentFile(job.run(date).fileID());
        assertEquals("TRANSFER", sent.batches().get(0).header().companyEntryDescription());
    }

    @Test
    void notABankingDayBuildsNoFile() {
        submit("Jane Smith", "10.00", "Rent");
        LocalDate saturday = date.with(java.time.temporal.TemporalAdjusters.next(java.time.DayOfWeek.SATURDAY));
        assertNull(job.run(saturday).fileID());
    }

    @Test
    void nothingDueBuildsNoFile() {
        assertNull(job.run(date).fileID());
    }

    // ---- idempotency ----

    @Test
    void runningTwiceDuplicatesNothing() {
        String serNo = submit("Jane Smith", "10.00", "Rent");
        String fileID = job.run(date).fileID();
        assertNull(job.run(date).fileID());
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigTransaction WHERE transferSerNo = ?", serNo));
        assertEquals(List.of("FILE_QUEUED", "FED_SEND"), actions(fileID));
    }

    @Test
    void aRerunSendsAFileLeftQueuedInsteadOfBuildingAnother() throws IOException {
        String serNo = submit("Jane Smith", "10.00", "Rent");
        String fileID = store.compile(date, "EOD-TEST-INTERRUPTED").orElseThrow(); // the job died before sending
        String expected = store.fileText(fileID);

        assertNull(job.run(date).fileID());

        assertEquals("SENT", store.fedStatus(fileID));
        assertEquals(expected, Files.readString(Path.of(outbound, fileID + ".ach")));
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigTransaction WHERE transferSerNo = ?", serNo));
    }

    // ---- EX-01 ----

    @Test
    void aFlaggedItemStaysOutAndTheCleanOnesProceed() throws IOException {
        String clean = submit("Jane Smith", "10.00", "Rent");
        String flagged = submit("Blocked Test Person", "20.00", "Rent");
        AchFile sent = sentFile(job.run(date).fileID());
        assertEquals(1, sent.batches().get(0).entries().size());
        assertEquals("PROCESSED", status(clean));
        assertEquals("PENDING", status(flagged));
        assertEquals(0, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigTransaction WHERE transferSerNo = ?", flagged));
    }

    @Test
    void aNameListedAfterSubmitIsCaughtDuringCompilation() {
        String serNo = submit("Later Listed Person", "10.00", "Rent");
        jdbc.update("INSERT INTO OfacSdnName (sdnName, program) VALUES ('LATER LISTED PERSON', 'DEMO')");
        assertNull(job.run(date).fileID());
        assertEquals("PENDING", status(serNo));
        assertEquals("FLAGGED", jdbc.queryForObject("SELECT ofacStatus FROM " + orig + ".ACHOriginatorTransfer WHERE transferSerNo = ?", String.class, serNo));
    }

    // ---- EX-02 ----

    @Test
    void twoFailuresThenSuccessEndsSent() {
        submit("Jane Smith", "10.00", "Rent");
        failures(2);
        EodFileJob.RunResult r = job.run(date);
        assertEquals("SENT", r.fedStatus());
        assertEquals(List.of("FILE_QUEUED", "FED_SEND_FAILED", "FED_SEND_FAILED", "FED_SEND"), actions(r.fileID()).stream().sorted(
                java.util.Comparator.comparingInt(List.of("FILE_QUEUED", "FED_SEND_FAILED", "FED_SEND")::indexOf)).toList());
    }

    @Test
    void threeFailuresEndInErrorWithAFatalLog_andAResendWorks() {
        submit("Jane Smith", "10.00", "Rent");
        failures(3);
        EodFileJob.RunResult r = job.run(date);
        assertEquals("ERROR", r.fedStatus());
        assertEquals("ERROR", store.fedStatus(r.fileID()));
        assertEquals(1, count("SELECT COUNT(*) FROM AppLog WHERE logLevel = 'FATAL' AND logMesg LIKE ?", "%" + r.fileID() + " could not be sent%"));
        assertTrue(actions(r.fileID()).contains("FILE_ERROR"));

        assertNull(job.run(date).fileID()); // a rerun doesn't touch a file in ERROR
        assertEquals("ERROR", store.fedStatus(r.fileID()));

        failures(0);
        assertEquals("SENT", job.resend(r.fileID()));
        assertThrows(IllegalArgumentException.class, () -> job.resend(r.fileID())); // can't send it twice
    }

    // ---- audit trail ----

    @Test
    void theAuditTrailCannotBeChanged() {
        submit("Jane Smith", "10.00", "Rent");
        String fileID = job.run(date).fileID();
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE " + orig + ".ACHOrigFedAction SET lastModBy = 'hacker' WHERE fileID = ?", fileID));
        assertThrows(DataAccessException.class, () -> jdbc.update("DELETE FROM " + orig + ".ACHOrigFedAction WHERE fileID = ?", fileID));
    }

    // ---- E4: recurring ----

    @Test
    void eachPaymentOfASeriesIsItsOwnRowAndEntry() {
        String first = submitSeries("WEEKLY", 3);
        assertEquals("SENT", job.run(date).fedStatus());

        var second = jdbc.queryForMap("SELECT transferSerNo, transferReqNo, transferReqRemaining, effectiveDt, memoStatus FROM " + orig
                + ".ACHOriginatorTransfer WHERE pTransferSerNo = ?", first);
        assertEquals(2, second.get("transferReqNo"));
        assertEquals(1, second.get("transferReqRemaining"));
        assertEquals("PENDING", second.get("memoStatus")); // no hold until it's due
        assertEquals(new BigDecimal("19900.00"), available());

        LocalDate nextDate = ((java.sql.Date) second.get("effectiveDt")).toLocalDate();
        assertEquals(dates.occurrence(date, "WEEKLY", 1), nextDate);
        assertEquals("SENT", job.run(nextDate).fedStatus());
        assertEquals(new BigDecimal("19800.00"), available());
        assertEquals("PROCESSED", status((String) second.get("transferSerNo")));
        assertEquals("R", jdbc.queryForObject("SELECT transactionDiscData FROM " + orig + ".ACHOrigTransaction WHERE transferSerNo = ?", String.class, first));

        LocalDate thirdDate = dates.occurrence(date, "WEEKLY", 2);
        job.run(thirdDate);
        assertEquals(3, count("SELECT COUNT(*) FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ?", MEMBER)); // and no fourth
        assertEquals(0, count("SELECT COUNT(*) FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ? AND processStatus = 'PENDING_BATCH'", MEMBER));
    }

    @Test
    void shortFundsRejectThatPaymentAndTheSeriesContinues() {
        String first = submitSeries("WEEKLY", 3);
        job.run(date);
        String second = jdbc.queryForObject("SELECT transferSerNo FROM " + orig + ".ACHOriginatorTransfer WHERE pTransferSerNo = ?", String.class, first);
        jdbc.update("UPDATE SimCoreAccount SET availBal = 50.00 WHERE acctNo = ?", CHECKING);

        assertNull(job.run(dates.occurrence(date, "WEEKLY", 1)).fileID());

        assertEquals("REJECTED", status(second));
        assertEquals(new BigDecimal("50.00"), available());
        assertEquals(0, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigTransaction WHERE transferSerNo = ?", second));
        jdbc.update("UPDATE SimCoreAccount SET availBal = 500.00 WHERE acctNo = ?", CHECKING);
        assertEquals("SENT", job.run(dates.occurrence(date, "WEEKLY", 2)).fedStatus());
    }

    @Test
    void monthlyDatesCountFromTheFirstAndAvoidNonBankingDays() {
        LocalDate jan31 = LocalDate.parse("2027-01-29"); // a Friday, the last banking day of January
        assertEquals(LocalDate.parse("2027-03-01"), dates.occurrence(jan31, "MONTHLY", 1)); // Feb has no 29th: Mar 1
        assertEquals(LocalDate.parse("2027-03-29"), dates.occurrence(jan31, "MONTHLY", 2));
        assertFalse(calendar.isBankingDay(LocalDate.parse("2026-11-26")));
        assertEquals(LocalDate.parse("2026-11-27"), dates.occurrence(LocalDate.parse("2026-11-19"), "WEEKLY", 1)); // Thanksgiving
    }
}
