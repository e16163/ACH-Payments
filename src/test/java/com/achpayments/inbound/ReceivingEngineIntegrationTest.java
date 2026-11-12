package com.achpayments.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
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
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.achpayments.inbound.FedInboundSimulator.Sample;
import com.achpayments.inbound.ReceivingController.Detail;
import com.achpayments.inbound.ReceivingController.FileRow;
import com.achpayments.inbound.ReceivingController.Page;
import com.achpayments.inbound.ReceivingEngine.Result;

/** UC-ACH-RCV-01 and UC-ACH-RCV-02, on the real test databases. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class ReceivingEngineIntegrationTest {

    private static final String BLANK_94 = " ".repeat(94);

    @Autowired
    ReceivingController controller;
    @Autowired
    ReceivingEngine engine;
    @Autowired
    FedInboundSimulator fed;
    @Autowired
    FileSignature signature;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${app.db.receiving}")
    String recv;
    @Value("${app.fed.inbound-dir}")
    Path inbound;
    @Value("${app.fed.outbound-dir}")
    Path outbound;

    @BeforeEach
    @AfterEach
    void clean() throws IOException {
        jdbc.update("DELETE FROM " + recv + ".ACHRecvReturn WHERE lastModBy = 'ACH_SYS'"); // returns from Receive's steps 4-6
        jdbc.update("DELETE FROM " + recv + ".ACHRecvAddenda");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvTransaction WHERE fileID LIKE 'ACHFILE-2%'");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvBatch WHERE fileID LIKE 'ACHFILE-2%'");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvFile WHERE fileID LIKE 'ACHFILE-2%'");
        jdbc.update("DELETE FROM AppLog WHERE logMesg LIKE '%ACHFILE-2%'");
        for (Path dir : List.of(inbound, quarantine(), archive(), outbound)) {
            if (Files.exists(dir)) {
                try (Stream<Path> files = Files.walk(dir)) {
                    files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(dir)).forEach(p -> p.toFile().delete());
                }
            }
        }
    }

    private Path quarantine() {
        return inbound.resolveSibling("quarantine");
    }

    private Path archive() {
        return inbound.resolveSibling("archive");
    }

    private Result receive(Sample sample) throws IOException {
        fed.deliver(sample);
        return only(engine.ingestAll());
    }

    private static Result only(List<Result> results) {
        assertEquals(1, results.size());
        return results.get(0);
    }

    private int count(String table, String fileID) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + "." + table + " WHERE fileID = ?", Integer.class, fileID);
    }

    private Map<String, Object> file(String fileID) {
        return jdbc.queryForMap("SELECT * FROM " + recv + ".ACHRecvFile WHERE fileID = ?", fileID);
    }

    private List<String> logs(String fileID, String level) {
        return jdbc.queryForList("SELECT logMesg FROM AppLog WHERE logLevel = ? AND logMesg LIKE ? ORDER BY logAppId", String.class, level, "%" + fileID + "%");
    }

    private void deliverText(String name, String text) throws IOException {
        Files.createDirectories(inbound);
        Path file = inbound.resolve(name);
        Files.writeString(file, text);
        signature.sign(file);
    }

    private String sampleText() throws IOException {
        fed.deliver(Sample.PAYROLL);
        String text = Files.readString(inbound.resolve("PAYROLL.ach"));
        Files.delete(inbound.resolve("PAYROLL.ach"));
        Files.delete(inbound.resolve("PAYROLL.ach.sig"));
        return text;
    }

    private static String pad94(String s) {
        return (s + BLANK_94).substring(0, 94);
    }

    @Test
    void sampleOneIsSavedToTheThreeTables() throws IOException {
        Result r = receive(Sample.PAYROLL);
        assertEquals(ReceivingEngine.VALIDATED, r.processStatus());
        assertEquals(5, count("ACHRecvBatch", r.fileID()));
        assertEquals(50, count("ACHRecvTransaction", r.fileID()));

        Map<String, Object> f = file(r.fileID());
        assertEquals("VALIDATED", f.get("processStatus"));
        assertEquals("RECEIVED", f.get("fedStatus"));
        assertEquals("PAYROLL.ach", f.get("fileName"));
        assertEquals(LocalDate.of(2026, 11, 6), ((java.sql.Date) f.get("fileHeaderDt")).toLocalDate());
        assertEquals(LocalTime.of(8, 30), ((java.sql.Time) f.get("fileHeaderTm")).toLocalTime());
        assertEquals("A", f.get("fileIDModifier"));
        assertEquals("123456780", f.get("immedDest"));
        assertEquals("1987654321", f.get("immedOrigin"));
        assertEquals(50, f.get("totalCreditCount"));
        assertEquals(0, f.get("totalDebitCount"));
        assertEquals(new BigDecimal("129450.00"), f.get("totalCreditAmt"));
        assertEquals(new BigDecimal("0.00"), f.get("totalDebitAmt"));

        Map<String, Object> b = jdbc.queryForMap("SELECT * FROM " + recv + ".ACHRecvBatch WHERE fileID = ? AND batchNo = 3", r.fileID());
        assertEquals("ACME BONUSES", b.get("companyName"));
        assertEquals("QUARTERLY BONUS", b.get("companyDiscData"));
        assertEquals("BONUSES", b.get("companyEntryDesc"));
        assertEquals("19876543", b.get("odfiID"));
        assertEquals("200", b.get("serviceClassCode"));
        assertEquals(10, b.get("totalBatchCount"));
        assertEquals(new BigDecimal("26700.00"), b.get("totalCreditAmt"));
        assertEquals(r.fileID() + "-B003", b.get("batchID"));

        assertTrue(Files.exists(archive().resolve(r.fileID() + "-PAYROLL.ach")));
        assertTrue(Files.exists(archive().resolve(r.fileID() + "-PAYROLL.ach.sig")));
        assertFalse(Files.exists(inbound.resolve("PAYROLL.ach")));
        assertEquals(4, logs(r.fileID(), "INFO").size()); // In-Flight, Decrypted, Validated, the event
        assertTrue(logs(r.fileID(), "INFO").get(3).startsWith("ACH_FILE_INGESTED_EVENT"));
    }

    @Test
    void mapsAnEntryAsInTheFieldList() throws IOException {
        Result r = receive(Sample.PAYROLL);
        Page page = controller.transactions(r.fileID(), null, null, null, 1, 10, "traceNo", "ASC");
        Detail d = controller.transaction(page.items().get(0).tranID());
        assertTrue(d.transferSerNo().matches("TRF-\\d{8}-\\d{4}"), d.transferSerNo());
        assertEquals(1, d.transferVersion());
        assertEquals(r.fileID(), d.fileID());
        assertEquals(r.fileID() + "-B001", d.batchID());
        assertEquals(1, d.creditFlag());
        assertEquals("22", d.tranCode());
        assertEquals("ACME PAYROLL", d.companyName());
        assertEquals("1900483920", d.companyID());
        assertEquals("WEEKLY PAYROLL", d.companyDiscData());
        assertEquals("PPD", d.stdEntryCode());
        assertEquals("PAIDROLL", d.companyEntryDesc());
        assertEquals(LocalDate.of(2026, 11, 6), d.descriptiveDt());
        assertEquals(LocalDate.of(2026, 11, 6), d.effectiveDt());
        assertEquals(LocalDate.of(2026, 11, 6), d.settlementDt());
        assertEquals("1900483920", d.originatorID());
        assertEquals(new BigDecimal("1500.00"), d.amount());
        assertEquals("987654321019", d.recvAcctID());
        assertEquals("JOHN DOE", d.recvName());
        assertEquals("", d.transactionDiscData());
        assertEquals("198765430000001", d.traceNo());
        assertEquals("SETTLED", d.fedStatus());
        assertEquals("FED_ACH_INBOUND", d.sourceID());
        Map<String, Object> extra = jdbc.queryForMap("SELECT processStatus, recvRtn, individualID, addendaInd FROM " + recv
                + ".ACHRecvTransaction WHERE tranID = ?", d.tranID());
        assertEquals("RECEIVED", extra.get("processStatus")); // Ba's blueprint name (V17)
        assertEquals("123456780", extra.get("recvRtn"));
        assertEquals("EMP000000001", extra.get("individualID"));
        assertEquals("0", extra.get("addendaInd"));
    }

    @Test
    void sampleTwoHasDebitsAndCredits() throws IOException {
        Result r = receive(Sample.RETAIL);
        assertEquals(ReceivingEngine.VALIDATED, r.processStatus());
        Map<String, Object> f = file(r.fileID());
        assertEquals(new BigDecimal("71840.00"), f.get("totalCreditAmt"));
        assertEquals(new BigDecimal("1144.90"), f.get("totalDebitAmt"));
        assertEquals(30, f.get("totalCreditCount"));
        assertEquals(20, f.get("totalDebitCount"));
        assertEquals(Integer.valueOf(20), jdbc.queryForObject("SELECT COUNT(*) FROM " + recv
                + ".ACHRecvTransaction WHERE fileID = ? AND creditFlag = 0 AND tranCode = '27'", Integer.class, r.fileID()));
    }

    @Test
    void entrySerialNumbersKeepRisingAcrossFiles() throws IOException {
        Result first = receive(Sample.PAYROLL);
        Result second = receive(Sample.RETAIL);
        int rows = jdbc.queryForObject("SELECT COUNT(DISTINCT transferSerNo) FROM " + recv + ".ACHRecvTransaction WHERE fileID IN (?, ?)",
                Integer.class, first.fileID(), second.fileID());
        assertEquals(100, rows);
    }

    @Test
    void doesNotScreenOrMemoPostReceivedEntries() throws IOException {
        int memos = jdbc.queryForObject("SELECT COUNT(*) FROM ACHMemoPost", Integer.class);
        BigDecimal balances = jdbc.queryForObject("SELECT SUM(availBal) FROM SimCoreAccount", BigDecimal.class);
        receive(Sample.PAYROLL);
        assertEquals(memos, jdbc.queryForObject("SELECT COUNT(*) FROM ACHMemoPost", Integer.class));
        assertEquals(balances, jdbc.queryForObject("SELECT SUM(availBal) FROM SimCoreAccount", BigDecimal.class));
    }

    @Test
    void ex01TamperedFileIsQuarantinedWithAFatalLogAndNothingSaved() throws IOException {
        Result r = receive(Sample.TAMPERED);
        assertEquals("REJECTED_SIG_FAIL", r.processStatus());
        assertEquals("REJECTED_SIG_FAIL", file(r.fileID()).get("processStatus"));
        assertEquals(0, count("ACHRecvBatch", r.fileID()));
        assertEquals(0, count("ACHRecvTransaction", r.fileID()));
        assertTrue(Files.exists(quarantine().resolve(r.fileID() + "-TAMPERED.ach")));
        assertTrue(logs(r.fileID(), "FATAL").get(0).contains("Security Operations alert"));
    }

    @Test
    void ex01AFileWithNoSignatureIsTreatedAsTampered() throws IOException {
        fed.deliver(Sample.PAYROLL);
        Files.delete(inbound.resolve("PAYROLL.ach.sig"));
        assertEquals("REJECTED_SIG_FAIL", only(engine.ingestAll()).processStatus());
    }

    @Test
    void ex02OutOfBalanceRollsBackEverythingAndNotifiesTheFed() throws IOException {
        Result r = receive(Sample.OUT_OF_BALANCE);
        assertEquals("OUT_OF_BALANCE", r.processStatus());
        assertTrue(r.message().contains("Batch 3: total credits says 2675000 but the entries add up to 2670000"), r.message());
        assertEquals(0, count("ACHRecvBatch", r.fileID()));
        assertEquals(0, count("ACHRecvTransaction", r.fileID()));
        assertEquals("C", file(r.fileID()).get("fileIDModifier"));
        assertTrue(Files.exists(quarantine().resolve(r.fileID() + "-OUT_OF_BALANCE.ach")));
        assertTrue(Files.readString(outbound.resolve(r.fileID() + ".rejection.txt")).startsWith("FedACH file rejection"));
        assertEquals(1, logs(r.fileID(), "ERROR").size());
        String trace = jdbc.queryForObject("SELECT logExceptionData FROM AppLog WHERE logLevel = 'ERROR' AND logMesg LIKE ?", String.class, "%" + r.fileID() + "%");
        assertTrue(trace.contains("ReceivingEngine"), trace);
    }

    @Test
    void ex02AWrongFileControlAlsoRollsBack() throws IOException {
        String text = sampleText();
        String[] lines = text.split("\n");
        int nine = 62 - 1;
        lines[nine] = lines[nine].substring(0, 43) + "000012945001" + lines[nine].substring(55); // one cent more
        deliverText("WRONG9.ach", String.join("\n", lines) + "\n");
        Result r = only(engine.ingestAll());
        assertEquals("OUT_OF_BALANCE", r.processStatus());
        assertEquals(0, count("ACHRecvTransaction", r.fileID()));
    }

    @Test
    void ex03TheSameFileTwiceIsFlaggedAndAddsNoEntries() throws IOException {
        Result first = receive(Sample.PAYROLL);
        Result second = receive(Sample.PAYROLL);
        assertEquals("DUPLICATE", second.processStatus());
        assertEquals("DUPLICATE", file(second.fileID()).get("processStatus"));
        assertEquals(50, count("ACHRecvTransaction", first.fileID()));
        assertEquals(0, count("ACHRecvTransaction", second.fileID()));
        assertTrue(logs(second.fileID(), "WARN").get(0).contains("Operations alert"));
    }

    @Test
    void aFileForAnotherBankIsRejected() throws IOException {
        deliverText("OTHER.ach", sampleText().replaceFirst("101 123456780", "101 021000021"));
        Result r = only(engine.ingestAll());
        assertEquals("REJECTED", r.processStatus());
        assertTrue(r.message().contains("addressed to 021000021"), r.message());
    }

    @Test
    void anInvalidFileCreationDateIsRejected() throws IOException {
        deliverText("BADDATE.ach", sampleText().replaceFirst("2611060830", "2613060830"));
        assertEquals("REJECTED", only(engine.ingestAll()).processStatus());
    }

    @Test
    void aRecordThatIsNot94CharactersIsRejected() throws IOException {
        deliverText("SHORT.ach", "101 123456780 not a real header\n");
        Result r = only(engine.ingestAll());
        assertEquals("REJECTED", r.processStatus());
        assertTrue(r.message().contains("expected 94"), r.message());
    }

    @Test
    void aFileNotPaddedToABlockIsRejectedAndRolledBack() throws IOException {
        deliverText("NOPAD.ach", sampleText().replace("9".repeat(94) + "\n", ""));
        Result r = only(engine.ingestAll());
        assertEquals("REJECTED", r.processStatus());
        assertTrue(r.message().contains("62 records"), r.message());
        assertEquals(0, count("ACHRecvTransaction", r.fileID()));
    }

    @Test
    void aFileWithNoEntriesIsValidatedAndHasAnEmptyPage() throws IOException {
        String header = sampleText().lines().findFirst().orElseThrow();
        String control = pad94("9" + "000000" + "000001" + "00000000" + "0000000000" + "000000000000" + "000000000000");
        StringBuilder text = new StringBuilder(header).append('\n').append(control).append('\n');
        for (int i = 0; i < 8; i++) {
            text.append("9".repeat(94)).append('\n');
        }
        deliverText("EMPTY.ach", text.toString().replaceFirst("0830A", "0831D"));
        Result r = only(engine.ingestAll());
        assertEquals("VALIDATED", r.processStatus());
        Page page = controller.transactions(r.fileID(), null, null, null, 1, 10, "traceNo", "ASC");
        assertEquals(0, page.total());
        assertTrue(page.items().isEmpty());
    }

    @Test
    void addendaAreSavedWithTheirEntry() throws IOException {
        String[] sample = sampleText().split("\n");
        String header = sample[0].replaceFirst("0830A", "0832E");
        String batch = sample[1];
        String entry = sample[2].substring(0, 78) + "1" + sample[2].substring(79);
        String addenda = pad94("705PAYMENT INFO" + " ".repeat(69) + "00010000001");
        String control = pad94("8200000002" + "0012345678" + "000000000000" + "000000150000" + "1900483920" + " ".repeat(25) + "19876543" + "0000001");
        String file = pad94("9000001000001000000020012345678" + "000000000000" + "000000150000");
        StringBuilder text = new StringBuilder();
        for (String l : List.of(header, batch, entry, addenda, control, file)) {
            text.append(l).append('\n');
        }
        for (int i = 0; i < 4; i++) {
            text.append("9".repeat(94)).append('\n');
        }
        deliverText("ADDENDA.ach", text.toString());
        Result r = only(engine.ingestAll());
        assertEquals("VALIDATED", r.processStatus(), r.message());
        Map<String, Object> a = jdbc.queryForMap("SELECT a.seqNo, a.typeCode, a.content FROM " + recv + ".ACHRecvAddenda a JOIN " + recv
                + ".ACHRecvTransaction t ON t.tranID = a.tranID WHERE t.fileID = ?", r.fileID());
        assertEquals("05", a.get("typeCode"));
        assertEquals(1, a.get("seqNo"));
        assertTrue(a.get("content").toString().startsWith("PAYMENT INFO"));
    }

    @Test
    void pagesAreTenRowsSortedByTraceNumber() throws IOException {
        Result r = receive(Sample.PAYROLL);
        Page first = controller.transactions(r.fileID(), null, null, null, 1, 10, "traceNo", "ASC");
        assertEquals(50, first.total());
        assertEquals(10, first.items().size());
        assertEquals("198765430000001", first.items().get(0).traceNo());
        assertEquals("198765430000010", first.items().get(9).traceNo());
        Page fifth = controller.transactions(r.fileID(), null, null, null, 5, 10, "traceNo", "ASC");
        assertEquals("198765430000041", fifth.items().get(0).traceNo());
        assertEquals("198765430000050", fifth.items().get(9).traceNo());
        assertTrue(controller.transactions(r.fileID(), null, null, null, 6, 10, "traceNo", "ASC").items().isEmpty());
        assertEquals("198765430000050", controller.transactions(r.fileID(), null, null, null, 1, 10, "traceNo", "DESC").items().get(0).traceNo());
    }

    @Test
    void transactionsSearchByEffectiveDateStartToEndInclusive() throws IOException {
        Result r = receive(Sample.PAYROLL);
        LocalDate effective = controller.transaction(controller.transactions(r.fileID(), null, null, null, 1, 10, "traceNo", "ASC")
                .items().get(0).tranID()).effectiveDt();
        assertEquals(50, controller.transactions(r.fileID(), null, effective, effective, 1, 10, "traceNo", "ASC").total());
        assertEquals(50, controller.transactions(r.fileID(), null, effective.minusDays(3), effective, 1, 10, "traceNo", "ASC").total());
        assertEquals(0, controller.transactions(r.fileID(), null, effective.plusDays(1), effective.plusDays(9), 1, 10, "traceNo", "ASC").total());
        assertThrows(IllegalArgumentException.class, () -> controller.transactions(null, null, effective, effective.minusDays(1), 1, 10, "traceNo", "ASC"));
        assertThrows(IllegalArgumentException.class, () -> controller.transactions(null, null, effective, null, 1, 10, "traceNo", "ASC"));
    }

    @Test
    void transactionsOnlyListTheFileAskedFor() throws IOException {
        Result payroll = receive(Sample.PAYROLL);
        receive(Sample.RETAIL);
        assertEquals(50, controller.transactions(payroll.fileID(), null, null, null, 1, 10, "traceNo", "ASC").total());
        assertTrue(controller.transactions(null, null, null, null, 1, 10, "traceNo", "ASC").total() >= 100);
    }

    @Test
    void badPagingAndUnknownIdsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> controller.transactions(null, null, null, null, 0, 10, "traceNo", "ASC"));
        assertThrows(IllegalArgumentException.class, () -> controller.transactions(null, null, null, null, 1, 101, "traceNo", "ASC"));
        assertThrows(IllegalArgumentException.class, () -> controller.transactions(null, null, null, null, 1, 10, "amount; DROP TABLE x", "ASC"));
        assertThrows(IllegalArgumentException.class, () -> controller.transactions(null, null, null, null, 1, 10, "traceNo", "sideways"));
        assertThrows(EmptyResultDataAccessException.class, () -> controller.transaction(-1));
    }

    @Test
    void theFileListStillShowsTheDesignSampleFile() {
        FileRow sample = controller.files().stream().filter(f -> f.fileID().equals("ACHFILE-88392")).findFirst().orElseThrow();
        assertEquals("PROCESSED", sample.processStatus());
        assertEquals(2, sample.entryCount());
        assertEquals(0, new BigDecimal("3450.50").compareTo(sample.totalCreditAmt()));
    }

    @Test
    void newestFilesComeFirst() throws IOException {
        Result r = receive(Sample.PAYROLL);
        assertEquals(r.fileID(), controller.files().get(0).fileID());
    }
}
