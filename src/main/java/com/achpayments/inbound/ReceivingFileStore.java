package com.achpayments.inbound;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.achpayments.nacha.AchFile.Batch;
import com.achpayments.nacha.AchFile.BatchHeader;
import com.achpayments.nacha.AchFile.Entry;
import com.achpayments.nacha.AchFile.FileHeader;
import com.achpayments.nacha.AchFileValidator;
import com.achpayments.nacha.NachaDates;

/** UC-ACH-RCV-01 and UC-ACH-RCV-02 phase 1: what ingestion writes to ACHRecvFile, ACHRecvBatch and ACHRecvTransaction. */
@Repository
public class ReceivingFileStore {

    private static final String SOURCE_ID = "FED_ACH_INBOUND";
    private static final String SYSTEM_SETTLED = "SETTLED"; // UC-ACH-RCV-02 step 2 and the sample row
    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final String recv;

    public ReceivingFileStore(JdbcTemplate jdbc, Clock clock, @Value("${app.db.receiving}") String recv) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.recv = recv;
    }

    /** Step 1. The header isn't trusted yet, so the dates are the time of receipt until it's read. */
    public String insertFile(String fileName) {
        LocalDate today = LocalDate.now(clock);
        String prefix = "ACHFILE-" + today.format(DAY) + "-";
        String max = jdbc.queryForObject("SELECT MAX(fileID) FROM " + recv + ".ACHRecvFile WHERE fileID LIKE ?", String.class, prefix + "%");
        String fileID = prefix + "%02d".formatted(max == null ? 1 : Integer.parseInt(max.substring(prefix.length())) + 1);
        jdbc.update("INSERT INTO " + recv + ".ACHRecvFile (fileID, processID, fileName, fileHeaderDt, fileHeaderTm, fedStatus, processStatus) "
                + "VALUES (?, ?, ?, ?, ?, 'RECEIVED', 'INGESTING')",
                fileID, "RCV-" + fileID.substring("ACHFILE-".length()), fileName, today, LocalTime.now(clock).withNano(0));
        return fileID;
    }

    /** EX-03: The design's key is the File Creation Date, File Creation Time and File ID Modifier. */
    public boolean isDuplicate(String fileID, FileHeader h) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvFile WHERE fileHeaderDt = ? AND fileHeaderTm = ? "
                + "AND fileIDModifier = ? AND fileID <> ?", Integer.class, NachaDates.date(h.fileCreationDate()),
                NachaDates.time(h.fileCreationTime()), h.fileIdModifier(), fileID);
        return count != null && count > 0;
    }

    /** The first serial number of a file's entries; the rest follow it. */
    public long firstSerial() {
        Long max = jdbc.queryForObject("SELECT MAX(CAST(SUBSTRING(transferSerNo, ?) AS UNSIGNED)) FROM " + recv
                + ".ACHRecvTransaction WHERE transferSerNo LIKE ?", Long.class, serialPrefix().length() + 1, serialPrefix() + "%");
        return max == null ? 1 : max + 1;
    }

    private String serialPrefix() {
        return "TRF-" + LocalDate.now(clock).format(DAY) + "-";
    }

    /** Steps 3-4 for one batch: a bulk INSERT of its entries. Returns the next serial number. */
    public long insertBatch(String fileID, Batch batch, long serial) {
        BatchHeader h = batch.header();
        LocalDate effective = NachaDates.date(h.effectiveEntryDate());
        LocalDate settlement = NachaDates.settlementDate(h.settlementDate(), effective);
        LocalDate descriptive = NachaDates.descriptiveDate(h.companyDescriptiveDate());
        String batchID = fileID + "-B%03d".formatted(h.batchNumber());
        jdbc.update("INSERT INTO " + recv + ".ACHRecvBatch (batchID, fileID, batchNo, companyName, companyID, companyDiscData, stdEntryCode, "
                + "companyEntryDesc, descriptiveDt, effectiveDt, settlementDt, odfiID, totalBatchCount, totalDebitAmt, totalCreditAmt, "
                + "Process, processID, processName, serviceClassCode) "
                + "SELECT ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RECEIVING', processID, 'Inbound FedACH file', ? "
                + "FROM " + recv + ".ACHRecvFile WHERE fileID = ?",
                batchID, fileID, h.batchNumber(), h.companyName(), h.companyId(), h.companyDiscretionaryData(), h.secCode(),
                h.companyEntryDescription(), descriptive, effective, settlement, h.originatingDfi(), batch.entries().size(),
                dollars(batch.control().totalDebitCents()), dollars(batch.control().totalCreditCents()), h.serviceClassCode(), fileID);

        List<Object[]> rows = new ArrayList<>();
        long next = serial;
        for (Entry e : batch.entries()) {
            rows.add(new Object[] {serialPrefix() + "%04d".formatted(next++), fileID, batchID, AchFileValidator.isDebit(e.transactionCode()) ? 0 : 1,
                    e.transactionCode(), h.companyName(), h.companyId(), h.companyDiscretionaryData(), h.secCode(), h.companyEntryDescription(),
                    descriptive, effective, settlement, h.companyId(), dollars(e.amountCents()), e.accountNumber(), e.individualName(),
                    e.discretionaryData(), e.traceNumber(), SYSTEM_SETTLED, SOURCE_ID, e.receivingDfi() + e.checkDigit(), e.individualId(),
                    e.addendaIndicator()});
        }
        jdbc.batchUpdate("INSERT INTO " + recv + ".ACHRecvTransaction (transferSerNo, transferVersion, fileID, batchID, creditFlag, tranCode, "
                + "companyName, companyID, companyDiscData, stdEntryCode, companyEntryDesc, descriptiveDt, effectiveDt, settlementDt, originatorID, "
                + "amount, recvAcctID, recvName, transactionDiscData, traceNo, fedStatus, sourceID, recvRtn, individualID, addendaInd) "
                + "VALUES (?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", rows);
        batch.entries().stream().filter(e -> !e.addenda().isEmpty()).forEach(e -> insertAddenda(fileID, e));
        return next;
    }

    private void insertAddenda(String fileID, Entry e) {
        Long tranID = jdbc.queryForObject("SELECT tranID FROM " + recv + ".ACHRecvTransaction WHERE fileID = ? AND traceNo = ?",
                Long.class, fileID, e.traceNumber());
        int seqNo = 0;
        for (var a : e.addenda()) {
            jdbc.update("INSERT INTO " + recv + ".ACHRecvAddenda (tranID, seqNo, typeCode, content) VALUES (?, ?, ?, ?)",
                    tranID, ++seqNo, a.typeCode(), a.content());
        }
    }

    /** Step 6, in the same transaction as the entries. */
    public void markValidated(String fileID, AchFileValidator.Check check) {
        jdbc.update("UPDATE " + recv + ".ACHRecvFile SET processStatus = 'VALIDATED', totalDebitCount = ?, totalCreditCount = ?, "
                + "totalDebitAmt = ?, totalCreditAmt = ? WHERE fileID = ?", check.debitCount(), check.creditCount(),
                dollars(check.totalDebitCents()), dollars(check.totalCreditCents()), fileID);
    }

    public void saveRawText(String fileID, String rawText) {
        jdbc.update("UPDATE " + recv + ".ACHRecvFile SET rawFileText = ? WHERE fileID = ?", rawText, fileID);
    }

    public String rawText(String fileID) {
        return jdbc.queryForList("SELECT rawFileText FROM " + recv + ".ACHRecvFile WHERE fileID = ?", String.class, fileID)
                .stream().findFirst().orElse(null);
    }

    /** null for an unknown file. */
    public String processStatus(String fileID) {
        return jdbc.queryForList("SELECT processStatus FROM " + recv + ".ACHRecvFile WHERE fileID = ?", String.class, fileID)
                .stream().findFirst().orElse(null);
    }

    public void markFailed(String fileID, String processStatus) {
        jdbc.update("UPDATE " + recv + ".ACHRecvFile SET processStatus = ? WHERE fileID = ?", processStatus, fileID);
    }

    /** Runs after the outcome is known, so a rolled-back file still shows what its header said. */
    public void saveHeader(String fileID, FileHeader h) {
        jdbc.update("UPDATE " + recv + ".ACHRecvFile SET fileHeaderDt = ?, fileHeaderTm = ?, fileIDModifier = ?, immedDest = ?, immedOrigin = ? "
                + "WHERE fileID = ?", NachaDates.date(h.fileCreationDate()), NachaDates.time(h.fileCreationTime()), h.fileIdModifier(),
                h.immediateDestination(), h.immediateOrigin(), fileID);
    }

    private static BigDecimal dollars(long cents) {
        return BigDecimal.valueOf(cents, 2);
    }
}
