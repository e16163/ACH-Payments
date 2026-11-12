package com.achpayments.outbound;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.achpayments.common.AppLogRepository;
import com.achpayments.nacha.AchFile;
import com.achpayments.nacha.AchFile.BatchHeader;
import com.achpayments.nacha.AchFile.Entry;
import com.achpayments.nacha.AchFile.FileHeader;
import com.achpayments.nacha.AchFileParser;
import com.achpayments.nacha.AchFileValidator;
import com.achpayments.nacha.AchFileWriter;
import com.achpayments.origination.TraceNumberGenerator;
import com.achpayments.outbound.NachaFileAssembler.BatchInput;
import com.achpayments.param.ParamCache;
import com.achpayments.transfer.OfacScreening;
import com.achpayments.transfer.TransferService;

/**
 * UC-ACH-EOD-01 steps 2-6 and the file's status changes. The file's text is always rebuilt from the saved rows, so a
 * resend is byte-for-byte the same file. Every status change adds a row to the design's ACHOrigFedAction audit trail.
 */
@Service
public class EodFileStore {

    static final int FED_SEND = 401, FILE_QUEUED = 402, FED_SEND_FAILED = 403, FILE_ERROR = 404;
    static final String SYSTEM_USER = "EOD_BATCH";
    private static final String MODIFIERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final String SEC_CODE = "WEB"; // USE-CASES T1
    private static final String SERVICE_CLASS_CREDITS = "220";
    private static final String DEFAULT_ENTRY_DESC = "TRANSFER"; // the field is required, the member's memo isn't
    /** Nacha gives these entry descriptions a meaning, so a member's memo can't be sent as one. */
    private static final Set<String> RESERVED_ENTRY_DESC = Set.of("REVERSAL", "RECLAIM", "NONSETTLED", "RETRY PYMT",
            "REDEPCHECK", "HCCLAIMPMT", "AUTOENROLL");

    /** One due transfer, joined to its recipient and originator. */
    private record Due(String serNo, String firstSerNo, BigDecimal amount, String memoStatus, String ofacStatus,
            String memo, LocalDate effectiveDt, String originatorID, String odfiRouting, String sourceID, boolean recurring,
            String recvName, String routingNo, String recvAcctNo, String recvAcctType) {
    }

    /** E5: what must be the same for entries to share a NACHA batch. */
    private record BatchKey(String secCode, String companyId, String odfiRouting, LocalDate effectiveDt, String entryDesc)
            implements Comparable<BatchKey> {
        @Override
        public int compareTo(BatchKey o) {
            return toString().compareTo(o.toString());
        }
    }

    private final JdbcTemplate jdbc;
    private final ParamCache params;
    private final OfacScreening ofac;
    private final TraceNumberGenerator traces;
    private final TransferService transfers;
    private final AppLogRepository appLog;
    private final Clock clock;
    private final String orig;

    public EodFileStore(JdbcTemplate jdbc, ParamCache params, OfacScreening ofac,
            TraceNumberGenerator traces, TransferService transfers, AppLogRepository appLog, Clock clock,
            @Value("${app.db.origination}") String orig) {
        this.jdbc = jdbc;
        this.params = params;
        this.ofac = ofac;
        this.traces = traces;
        this.transfers = transfers;
        this.appLog = appLog;
        this.clock = clock;
        this.orig = orig;
    }

    /**
     * Steps 2-6 in one DB transaction: either the file, its batches, its entries and the transfer statuses are all
     * saved, or none are. Only transfers still READY_TO_POST are picked up, so a rerun can't duplicate anything.
     *
     * @return the new fileID, or empty if nothing is due
     */
    @Transactional
    public Optional<String> compile(LocalDate businessDate, String processID) {
        return compile(businessDate, processID, null);
    }

    /** scheduleID: only the transfers that window made ready, and the file records it; null for every ready transfer. */
    @Transactional
    public Optional<String> compile(LocalDate businessDate, String processID, Integer scheduleID) {
        alertOnFlaggedItems(businessDate);
        Map<BatchKey, List<Due>> groups = new TreeMap<>();
        for (Due d : dueTransfers(businessDate, scheduleID)) {
            if (readyForFile(d)) {
                String desc = d.memo() == null || RESERVED_ENTRY_DESC.contains(d.memo()) ? DEFAULT_ENTRY_DESC : d.memo();
                groups.computeIfAbsent(new BatchKey(SEC_CODE, d.originatorID(), d.odfiRouting(), d.effectiveDt(), desc),
                        k -> new ArrayList<>()).add(d);
            }
        }
        if (groups.isEmpty()) {
            appLog.log(ACH_ORIG, "INFO", "EOD file: nothing due for " + businessDate);
            return Optional.empty();
        }

        String fileID = insertFile(processID, scheduleID, groups);
        int batchNo = 0;
        for (Map.Entry<BatchKey, List<Due>> group : groups.entrySet()) {
            insertBatch(fileID, ++batchNo, processID, group.getKey(), group.getValue());
        }
        // Step 5: a file that fails its own checks is never queued; the exception rolls all of this back.
        String text = fileText(fileID);
        List<String> problems = AchFileValidator.validate(AchFileParser.parse(text));
        if (!problems.isEmpty()) {
            throw new IllegalStateException("EOD file " + fileID + " failed validation: " + problems);
        }
        addAction(fileID, processID, FILE_QUEUED);
        return Optional.of(fileID);
    }

    /** EX-01: items waiting for compliance stay out of the file and are raised again on each run. */
    private void alertOnFlaggedItems(LocalDate businessDate) {
        jdbc.queryForList("SELECT transferSerNo FROM " + orig + ".ACHOriginatorTransfer WHERE processStatus = 'PENDING' "
                + "AND ofacStatus = 'FLAGGED' AND effectiveDt <= ? AND transferVersion = 1", String.class, businessDate)
                .forEach(serNo -> appLog.log(ACH_ORIG, "WARN", "Compliance alert: " + serNo + " is flagged by OFAC and left out of the EOD file"));
    }

    private List<Due> dueTransfers(LocalDate businessDate, Integer scheduleID) {
        return jdbc.query("SELECT t.transferSerNo, COALESCE(t.pTransferSerNo, t.transferSerNo) AS firstSerNo, "
                + "t.transferAmt, t.memoStatus, t.ofacStatus, t.companyEntryDesc, t.effectiveDt, t.originatorID, o.routingNo AS odfiRouting, "
                + "t.sourceID, t.recurringFlag, a.acctOwner, a.routingNo, a.acctNo, a.acctType "
                + "FROM " + orig + ".ACHOriginatorTransfer t "
                + "JOIN " + orig + ".ACHOrigRecvAccount a ON a.recvAcctSerNo = t.recvAcctSerNo AND a.recvAcctVersion = t.recvAcctVersion "
                + "JOIN " + orig + ".ACHOriginator o ON o.originatorID = t.originatorID "
                + "WHERE t.processStatus = 'READY_TO_POST' AND t.ofacStatus IN ('OFAC_PASSED', 'CLEARED') AND t.transferVersion = 1 "
                + "AND t.effectiveDt <= ? AND (? IS NULL OR t.scheduleID = ?) ORDER BY t.transferSerNo FOR UPDATE",
                (rs, i) -> new Due(rs.getString("transferSerNo"), rs.getString("firstSerNo"),
                        rs.getBigDecimal("transferAmt"), rs.getString("memoStatus"), rs.getString("ofacStatus"),
                        rs.getString("companyEntryDesc"), rs.getObject("effectiveDt", LocalDate.class), rs.getString("originatorID"),
                        rs.getString("odfiRouting"), rs.getString("sourceID"), rs.getBoolean("recurringFlag"),
                        rs.getString("acctOwner"), rs.getString("routingNo"), rs.getString("acctNo"), rs.getString("acctType")),
                businessDate, scheduleID, scheduleID);
    }

    /**
     * EX-01: screens the name again, unless a compliance officer cleared it. The hold was placed by the origination window
     * (Ba's blueprint step 6), so a transfer without one is left out.
     */
    private boolean readyForFile(Due d) {
        if (!"CLEARED".equals(d.ofacStatus()) && ofac.isHit(d.recvName())) {
            jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET ofacStatus = 'FLAGGED', ofacTm = NOW(), processStatus = 'PENDING' "
                    + "WHERE transferSerNo = ? AND transferVersion = 1", d.serNo());
            appLog.log(ACH_ORIG, "WARN", "Compliance alert: " + d.serNo() + " is flagged by OFAC and left out of the EOD file");
            return false;
        }
        if (!"POSTED".equals(d.memoStatus())) {
            appLog.log(ACH_ORIG, "WARN", "EOD file: " + d.serNo() + " left out, memoStatus is " + d.memoStatus());
            return false;
        }
        return true;
    }

    private String insertFile(String processID, Integer scheduleID, Map<BatchKey, List<Due>> groups) {
        LocalDate today = LocalDate.now(clock);
        String day = "FILE_" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "_";
        String max = jdbc.queryForObject("SELECT MAX(fileID) FROM " + orig + ".ACHOrigFile WHERE fileID LIKE ?", String.class, day + "%");
        int seq = max == null ? 1 : Integer.parseInt(max.substring(day.length())) + 1;
        if (seq > MODIFIERS.length()) {
            throw new IllegalStateException("More than " + MODIFIERS.length() + " files today: the File ID Modifier ran out");
        }
        String fileID = day + "%02d".formatted(seq);
        List<Due> all = groups.values().stream().flatMap(List::stream).toList();
        BigDecimal credits = all.stream().map(Due::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String origin = all.get(0).odfiRouting();
        jdbc.update("INSERT INTO " + orig + ".ACHOrigFile (fileID, processID, scheduleID, fileIDModifier, fileName, fileHeaderDt, fileHeaderTm, "
                + "totalCreditCount, totalCreditAmt, fedStatus, immedDest, immedOrigin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?)",
                fileID, processID, scheduleID, String.valueOf(MODIFIERS.charAt(seq - 1)), fileID + ".ach", today,
                LocalTime.now(clock).withSecond(0).withNano(0), all.size(), credits, param("FED_IMMEDIATE_DEST"), origin);
        return fileID;
    }

    private void insertBatch(String fileID, int batchNo, String processID, BatchKey key, List<Due> items) {
        String batchID = fileID + "-B%03d".formatted(batchNo);
        String companyName = param("ORIG_COMPANY_NAME");
        BigDecimal credits = items.stream().map(Due::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        jdbc.update("INSERT INTO " + orig + ".ACHOrigBatch (batchID, fileID, batchNo, companyName, companyID, stdEntryCode, "
                + "companyEntryDesc, effectiveDt, odfiID, totalBatchCount, totalCreditAmt, Process, processID, processName, "
                + "serviceClassCode) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ORIGINATION', ?, 'EOD ACH File', ?)",
                batchID, fileID, batchNo, companyName, key.companyId(), key.secCode(), key.entryDesc(), key.effectiveDt(),
                key.odfiRouting().substring(0, 8), items.size(), credits, processID, SERVICE_CLASS_CREDITS);
        for (Due d : items) { // the trace numbers ascend within the batch, as NACHA requires
            jdbc.update("INSERT INTO " + orig + ".ACHOrigTransaction (transferSerNo, transferVersion, fileID, batchID, creditFlag, "
                    + "tranCode, companyName, companyID, stdEntryCode, companyEntryDesc, effectiveDt, originatorID, amount, recvAcctID, "
                    + "recvName, transactionDiscData, traceNo, fedStatus, sourceID, recvRoutingNo, individualID, addendaInd) "
                    + "VALUES (?, 1, ?, ?, 1, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?, ?, ?, '0')",
                    d.serNo(), fileID, batchID, "SAVINGS".equals(d.recvAcctType()) ? "32" : "22", companyName, key.companyId(),
                    key.secCode(), key.entryDesc(), d.effectiveDt(), d.originatorID(), d.amount(), d.recvAcctNo(), d.recvName(),
                    d.recurring() ? "R" : "S", traces.next(d.odfiRouting()), d.sourceID(), d.routingNo(),
                    d.serNo().replace("-", "")); // TRF-20261105-0001 fits the 15-character field
            // Design: the batch updates the transfer's status to Processed; delivery is tracked by fedStatus.
            jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET processStatus = 'PROCESSED', processTm = NOW() "
                    + "WHERE transferSerNo = ? AND transferVersion = 1", d.serNo());
            transfers.scheduleNextPayment(d.serNo());
        }
    }

    private String param(String name) {
        return params.get("ACH_ORIG", name).orElseThrow(() -> new IllegalStateException("Missing parameter ACH_ORIG/" + name));
    }

    // ---- the file's text ----

    /** Steps 4-5: the NACHA text of a saved file. */
    public String fileText(String fileID) {
        Map<String, Object> f = jdbc.queryForMap("SELECT immedDest, immedOrigin, fileHeaderDt, fileHeaderTm, fileIDModifier FROM "
                + orig + ".ACHOrigFile WHERE fileID = ?", fileID);
        String originName = jdbc.queryForObject("SELECT name FROM " + orig + ".ACHOriginator WHERE routingNo = ? LIMIT 1",
                String.class, f.get("immedOrigin"));
        LocalDateTime created = ((java.sql.Date) f.get("fileHeaderDt")).toLocalDate().atTime(((java.sql.Time) f.get("fileHeaderTm")).toLocalTime());
        FileHeader header = new FileHeader("01", (String) f.get("immedDest"), (String) f.get("immedOrigin"),
                created.format(DateTimeFormatter.ofPattern("yyMMdd")), created.format(DateTimeFormatter.ofPattern("HHmm")),
                (String) f.get("fileIDModifier"), "094", "10", "1", "FEDERAL RESERVE BANK", originName.toUpperCase(Locale.ROOT), "");
        List<BatchInput> batches = jdbc.query("SELECT batchID, batchNo, companyName, companyID, stdEntryCode, companyEntryDesc, "
                + "effectiveDt, odfiID, serviceClassCode FROM " + orig + ".ACHOrigBatch WHERE fileID = ? ORDER BY batchNo",
                (rs, i) -> new BatchInput(new BatchHeader(rs.getString("serviceClassCode"), rs.getString("companyName"), "",
                        rs.getString("companyID"), rs.getString("stdEntryCode"), rs.getString("companyEntryDesc"), "",
                        rs.getObject("effectiveDt", LocalDate.class).format(DateTimeFormatter.ofPattern("yyMMdd")), "", "1",
                        rs.getString("odfiID"), rs.getInt("batchNo")), entries(rs.getString("batchID"))), fileID);
        return AchFileWriter.write(NachaFileAssembler.assemble(header, batches));
    }

    private List<Entry> entries(String batchID) {
        return jdbc.query("SELECT tranCode, recvRoutingNo, recvAcctID, amount, individualID, recvName, transactionDiscData, addendaInd, traceNo "
                + "FROM " + orig + ".ACHOrigTransaction WHERE batchID = ? ORDER BY traceNo", (rs, i) -> {
                    String routing = rs.getString("recvRoutingNo");
                    return new Entry(rs.getString("tranCode"), routing.substring(0, 8), routing.substring(8),
                            rs.getString("recvAcctID"), rs.getBigDecimal("amount").movePointRight(2).longValueExact(),
                            rs.getString("individualID"), rs.getString("recvName"), rs.getString("transactionDiscData"),
                            rs.getString("addendaInd"), rs.getString("traceNo"), List.of());
                }, batchID);
    }

    // ---- status changes ----

    public List<String> queuedFiles() {
        return jdbc.queryForList("SELECT fileID FROM " + orig + ".ACHOrigFile WHERE fedStatus = 'QUEUED' ORDER BY fileID", String.class);
    }

    public String fedStatus(String fileID) {
        return jdbc.queryForObject("SELECT fedStatus FROM " + orig + ".ACHOrigFile WHERE fileID = ?", String.class, fileID);
    }

    public String fileName(String fileID) {
        return jdbc.queryForObject("SELECT fileName FROM " + orig + ".ACHOrigFile WHERE fileID = ?", String.class, fileID);
    }

    /** Step 7. */
    @Transactional
    public void markSent(String fileID, String processID) {
        jdbc.update("UPDATE " + orig + ".ACHOrigFile SET fedStatus = 'SENT', sendTm = NOW() WHERE fileID = ?", fileID);
        jdbc.update("UPDATE " + orig + ".ACHOrigTransaction SET fedStatus = 'SENT' WHERE fileID = ?", fileID);
        addAction(fileID, processID, FED_SEND);
    }

    public void recordFailedAttempt(String fileID, String processID) {
        addAction(fileID, processID, FED_SEND_FAILED);
    }

    /** EX-02, after the last attempt. */
    @Transactional
    public void markError(String fileID, String processID) {
        jdbc.update("UPDATE " + orig + ".ACHOrigFile SET fedStatus = 'ERROR' WHERE fileID = ?", fileID);
        jdbc.update("UPDATE " + orig + ".ACHOrigTransaction SET fedStatus = 'ERROR' WHERE fileID = ?", fileID);
        addAction(fileID, processID, FILE_ERROR);
    }

    private void addAction(String fileID, String processID, int actionID) {
        jdbc.update("INSERT INTO " + orig + ".ACHOrigFedAction (fileID, processID, actionID, lastModBy) VALUES (?, ?, ?, ?)",
                fileID, processID, actionID, SYSTEM_USER);
    }
}
