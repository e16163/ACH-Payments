package com.achpayments.origination;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.common.AppLogRepository;
import com.achpayments.transfer.OfacScreening;
import com.achpayments.transfer.TransferService;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Ba's blueprint, "ACH Origination Processing & Gateway API Specification" section II, with Ba's paths and snake_case
 * fields. Compliance paths are for ROLE_COMPLIANCE, the rest for ROLE_ACH_ORIG_OPS (SecurityConfig). Each step works on
 * a saved transfer named by transfer_id; the window runs the same steps in order.
 */
@RestController
public class OriginationProcessingController {

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractRequest(LocalDate businessDate, String processingWindow, Integer batchSize) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Item(String transferId, String accountNumber, String routingNumber, String transactionType, String secCode,
            BigDecimal amount, String direction) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractResponse(String status, int extractedCount, String batchId, List<Item> items) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VerifyRequest(String transferId, BigDecimal amount, String direction) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VerifyResponse(String accountNumber, String accountStatus, BigDecimal ledgerBalance, BigDecimal availableBalance,
            BigDecimal holdAmount, boolean sufficientFunds) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LimitRequest(String customerId, String accountNumber, BigDecimal amount, String secCode) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LimitResponse(String customerId, boolean limitCheckPassed, BigDecimal dailyLimitAssigned,
            BigDecimal dailyUtilizedTodate, BigDecimal remainingLimit) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScreenRequest(String transferId, String entityName, String address, String country) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScreenResponse(String transferId, String ofacStatus, BigDecimal matchScore, String matchedList, String details) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResolveRequest(String transferId, String resolution, String complianceOfficerId, String notes) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ResolveResponse(String transferId, String previousStatus, String currentStatus, OffsetDateTime updatedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Flagged(String transferId, String memberNo, String recipientName, BigDecimal amount, LocalDate effectiveDt,
            LocalDateTime flaggedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemoRequest(String transferId, String accountNumber, BigDecimal amount, String direction, String memoType) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemoResponse(String memoTransactionId, String accountNumber, String status, BigDecimal adjustedAvailableBalance,
            OffsetDateTime timestamp) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatusRequest(List<String> transferIds, String targetStatus) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatusResponse(int updatedCount, String status, String postingBatchTag) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WindowRequest(String windowTime, String executionMode, Boolean overrideSafetyLock) {
    }

    // ---- the ACH Origination Processing screen ----

    /** A window's last run today: steps 1-7 (ACHOrigWindowRun). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WindowRun(String windowId, int extracted, int accountRejected, int limitRejected, int ofacFlagged, int holdRejected,
            int readyToPost, String runBy, LocalDateTime startedAt) {
    }

    /** One row of ACHOrigSchedule, with its last run today (null if it hasn't run). */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Schedule(int scheduleId, String windowTime, String windowName, WindowRun lastRun) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Today(LocalDate businessDate, List<Schedule> schedules, int waitingForCompliance) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Ready(String transferId, String recipientName, String routingNumber, BigDecimal amount, LocalDate effectiveDt,
            LocalDateTime readyAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record SendRequest(Integer scheduleId) {
    }

    /** A row of ACHOrigBatch. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Batch(String batchId, int batchNo, String companyName, String stdEntryCode, String companyEntryDesc, LocalDate effectiveDt,
            int entryCount, BigDecimal totalCreditAmt) {
    }

    /** A row of ACHOrigFile, with its batches. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FileRow(String fileId, String fileName, Integer scheduleId, LocalDate fileHeaderDt, java.time.LocalTime fileHeaderTm,
            int totalCreditCount, BigDecimal totalCreditAmt, String fedStatus, LocalDateTime sendTm, List<Batch> batches) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WindowResponse(String windowId, String executionStatus, String message, int estimatedCompletionSeconds) {
    }

    private final OriginationWindow window;
    private final TransferService transfers;
    private final OfacScreening ofac;
    private final JdbcTemplate jdbc;
    private final AppLogRepository appLog;
    private final Clock clock;
    private final com.achpayments.outbound.EodFileJob fileJob;
    private final String orig;

    public OriginationProcessingController(OriginationWindow window, TransferService transfers, OfacScreening ofac, JdbcTemplate jdbc,
            AppLogRepository appLog, Clock clock, com.achpayments.outbound.EodFileJob fileJob, @Value("${app.db.origination}") String orig) {
        this.window = window;
        this.transfers = transfers;
        this.ofac = ofac;
        this.jdbc = jdbc;
        this.appLog = appLog;
        this.clock = clock;
        this.fileJob = fileJob;
        this.orig = orig;
    }

    /** 8. Runs the window for today now; it answers when done, so COMPLETED rather than Ba's STARTED. */
    @PostMapping("/api/v1/ach/processing/trigger-window")
    public WindowResponse triggerWindow(@RequestBody WindowRequest body, Principal user) {
        String time = body.windowTime();
        if (time == null || !time.matches("\\d{2}:\\d{2}")) {
            throw new IllegalArgumentException("window_time must be a time like 12:00.");
        }
        appLog.log(ACH_ORIG, "INFO", "User '%s' triggered origination window %s".formatted(user.getName(), time));
        OriginationWindow.Run run = window.run(LocalDate.now(clock), time, user.getName());
        return new WindowResponse(run.windowId(), "COMPLETED", "ACH processing window %s: %d due, %d ready to post, %d flagged, %d rejected."
                .formatted(time, run.extracted(), run.ready(), run.flagged(), run.rejected()), 0);
    }

    /** The screen: today's date, the schedule, each window's last run today, and step 5's queue. */
    @GetMapping("/api/v1/ach/processing/windows")
    public Today windows() {
        LocalDate today = LocalDate.now(clock);
        Map<String, WindowRun> last = new java.util.HashMap<>();
        for (Map<String, Object> r : window.runs(today)) { // in run order, so the last one wins
            last.put((String) r.get("windowTime"), new WindowRun((String) r.get("windowID"), n(r, "extractedCnt"), n(r, "acctRejectCnt"),
                    n(r, "limitRejectCnt"), n(r, "flaggedCnt"), n(r, "holdRejectCnt"), n(r, "readyCnt"), (String) r.get("runBy"),
                    (LocalDateTime) r.get("startTm")));
        }
        List<Schedule> schedules = window.schedule().stream()
                .map(w -> new Schedule(w.scheduleId(), w.windowTime(), w.windowName(), last.get(w.windowTime()))).toList();
        Integer waiting = jdbc.queryForObject("SELECT COUNT(*) FROM " + orig + ".ACHOriginatorTransfer WHERE ofacStatus = 'FLAGGED' "
                + "AND transferVersion = 1", Integer.class);
        return new Today(today, schedules, waiting == null ? 0 : waiting);
    }

    /** The transactions ready to be sent to the Fed for today and the schedule: the ones that window made READY_TO_POST. */
    @GetMapping("/api/v1/ach/processing/ready")
    public List<Ready> ready(@RequestParam("schedule_id") int scheduleId) {
        return jdbc.query("SELECT t.transferSerNo, a.acctOwner, a.routingNo, t.transferAmt, t.effectiveDt, t.processTm FROM " + orig
                + ".ACHOriginatorTransfer t JOIN " + orig + ".ACHOrigRecvAccount a ON a.recvAcctSerNo = t.recvAcctSerNo "
                + "AND a.recvAcctVersion = t.recvAcctVersion WHERE t.processStatus = 'READY_TO_POST' AND t.scheduleID = ? "
                + "AND t.effectiveDt <= ? AND t.transferVersion = 1 ORDER BY t.transferSerNo",
                (rs, i) -> new Ready(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4), rs.getObject(5, LocalDate.class),
                        rs.getObject(6, LocalDateTime.class)), scheduleId, LocalDate.now(clock));
    }

    /** Send to the Fed: creates the ACH origination file of that schedule's ready transactions, and sends it. */
    @PostMapping("/api/v1/ach/processing/send-to-fed")
    public com.achpayments.outbound.EodFileJob.RunResult sendToFed(@RequestBody SendRequest body, Principal user) {
        OriginationWindow.Window w = window.schedule().stream().filter(x -> body.scheduleId() != null && x.scheduleId() == body.scheduleId())
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Choose a schedule."));
        LocalDate today = LocalDate.now(clock);
        String windowId = "WIN-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + w.windowTime().replace(":", "");
        var result = fileJob.sendNow(today, windowId, w.scheduleId());
        appLog.log(ACH_ORIG, "ERROR".equals(result.fedStatus()) ? "ERROR" : "INFO", "User '%s' sent the %s schedule to the Fed: %s"
                .formatted(user.getName(), w.windowTime(), result.message()));
        return result;
    }

    /** Today's ACH origination files sent from the schedule (ACHOrigFile), each with its batches (ACHOrigBatch). */
    @GetMapping("/api/v1/ach/processing/files")
    public List<FileRow> files(@RequestParam("schedule_id") int scheduleId) {
        return jdbc.query("SELECT fileID, fileName, scheduleID, fileHeaderDt, fileHeaderTm, totalCreditCount, totalCreditAmt, fedStatus, sendTm "
                + "FROM " + orig + ".ACHOrigFile WHERE scheduleID = ? AND fileHeaderDt = ? ORDER BY fileID",
                (rs, i) -> new FileRow(rs.getString(1), rs.getString(2), rs.getInt(3), rs.getObject(4, LocalDate.class),
                        rs.getObject(5, java.time.LocalTime.class), rs.getInt(6), rs.getBigDecimal(7), rs.getString(8),
                        rs.getObject(9, LocalDateTime.class), batches(rs.getString(1))), scheduleId, LocalDate.now(clock));
    }

    private List<Batch> batches(String fileID) {
        return jdbc.query("SELECT batchID, batchNo, companyName, stdEntryCode, companyEntryDesc, effectiveDt, totalBatchCount, totalCreditAmt "
                + "FROM " + orig + ".ACHOrigBatch WHERE fileID = ? ORDER BY batchNo",
                (rs, i) -> new Batch(rs.getString(1), rs.getInt(2), rs.getString(3), rs.getString(4), rs.getString(5),
                        rs.getObject(6, LocalDate.class), rs.getInt(7), rs.getBigDecimal(8)), fileID);
    }

    private static int n(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
    }

    /** 1. */
    @PostMapping("/api/v1/ach/origination/extract-schedules")
    public ExtractResponse extract(@RequestBody ExtractRequest body) {
        LocalDate date = body.businessDate() == null ? LocalDate.now(clock) : body.businessDate();
        List<String> due = window.extract(date);
        if (body.batchSize() != null && body.batchSize() > 0 && due.size() > body.batchSize()) {
            due = due.subList(0, body.batchSize());
        }
        List<Item> items = due.stream().map(serNo -> jdbc.queryForObject("SELECT t.transferSerNo, t.origAcctNo, a.routingNo, t.transferAmt FROM "
                + orig + ".ACHOriginatorTransfer t JOIN " + orig + ".ACHOrigRecvAccount a ON a.recvAcctSerNo = t.recvAcctSerNo "
                + "AND a.recvAcctVersion = t.recvAcctVersion WHERE t.transferSerNo = ? AND t.transferVersion = 1",
                (rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getString(3), "WEB", "WEB", rs.getBigDecimal(4), "DEBIT"), serNo))
                .toList();
        String window = body.processingWindow() == null ? "" : "-" + body.processingWindow().replace(":", "");
        return new ExtractResponse("SUCCESS", items.size(), "ACH-BATCH-" + date.format(DAY) + window, items);
    }

    /** 2. */
    @PostMapping("/api/v1/accounts/{accountNumber}/verify")
    public VerifyResponse verify(@PathVariable String accountNumber, @RequestBody VerifyRequest body) {
        Map<String, Object> a = jdbc.queryForList("SELECT acctStatus, ledgerBal, availBal FROM SimCoreAccount WHERE acctNo = ?", accountNumber)
                .stream().findFirst().orElseThrow(() -> new IllegalArgumentException("No such account " + accountNumber + "."));
        BigDecimal ledger = (BigDecimal) a.get("ledgerBal"), available = (BigDecimal) a.get("availBal");
        boolean sufficient;
        if (body.transferId() != null) {
            OriginationWindow.Transfer t = transfer(body.transferId());
            sufficient = window.accountProblem(t).isEmpty();
        } else {
            sufficient = "ACTIVE".equals(a.get("acctStatus")) && body.amount() != null && available.compareTo(body.amount()) >= 0;
        }
        return new VerifyResponse(accountNumber, (String) a.get("acctStatus"), ledger, available, ledger.subtract(available), sufficient);
    }

    /** 3. The daily figures come from the 1-day ACHOrigLimitRule for the account's type. */
    @PostMapping("/api/v1/ach/limits/evaluate")
    public LimitResponse limits(@RequestBody LimitRequest body) {
        String acctType = jdbc.queryForList("SELECT acctType FROM SimCoreAccount WHERE acctNo = ? AND memberNo = ?", String.class,
                body.accountNumber(), body.customerId()).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Account " + body.accountNumber() + " isn't customer " + body.customerId() + "'s."));
        BigDecimal amount = body.amount() == null ? BigDecimal.ZERO : body.amount();
        boolean passed = transfers.limitProblem(body.customerId(), acctType, amount).isEmpty();
        BigDecimal daily = jdbc.queryForList("SELECT limitAmt FROM " + orig + ".ACHOrigLimitRule WHERE status = 'ACTIVE' AND dayNo = 1 "
                + "AND acctType IN (?, 'ALL') ORDER BY limitAmt", BigDecimal.class, acctType).stream().findFirst().orElse(null);
        BigDecimal used = jdbc.queryForObject("SELECT COALESCE(SUM(transferAmt), 0) FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ? "
                + "AND processStatus NOT IN ('CANCELLED', 'REJECTED') AND createTm >= CURDATE()", BigDecimal.class, body.customerId());
        return new LimitResponse(body.customerId(), passed, daily, used, daily == null ? null : daily.subtract(used).max(BigDecimal.ZERO));
    }

    /** 4. Screens the name; with a transfer_id it also records the result on that transfer, as the window does. */
    @PostMapping("/api/v1/compliance/ofac/screen")
    public ScreenResponse screen(@RequestBody ScreenRequest body) {
        if (body.entityName() == null || body.entityName().isBlank()) {
            throw new IllegalArgumentException("entity_name is required.");
        }
        boolean hit = ofac.isHit(body.entityName());
        if (body.transferId() != null && hit) {
            window.flag(transfer(body.transferId()));
        }
        return hit ? new ScreenResponse(body.transferId(), OriginationWindow.FLAGGED, BigDecimal.ONE.setScale(2), "SDN",
                "Exact match on the simulated SDN list, ignoring case and punctuation.")
                : new ScreenResponse(body.transferId(), "CLEAR", BigDecimal.ZERO.setScale(2), null, "No match.");
    }

    /** 5. The officer is the signed-in user; Ba's compliance_officer_id is kept when given. */
    @PatchMapping("/api/v1/compliance/ofac/resolve")
    public ResolveResponse resolve(@RequestBody ResolveRequest body, Principal user) {
        String officer = body.complianceOfficerId() == null || body.complianceOfficerId().isBlank() ? user.getName() : body.complianceOfficerId();
        OriginationWindow.Resolution r = window.resolve(body.transferId(), body.resolution(), officer, body.notes());
        return new ResolveResponse(r.transferId(), r.previousStatus(), r.currentStatus(), OffsetDateTime.now(clock).withNano(0));
    }

    /** The compliance officer's work list for step 5. */
    @GetMapping("/api/v1/compliance/ofac/flagged")
    public List<Flagged> flagged() {
        return jdbc.query("SELECT t.transferSerNo, t.memberNo, a.acctOwner, t.transferAmt, t.effectiveDt, t.ofacTm FROM " + orig
                + ".ACHOriginatorTransfer t JOIN " + orig + ".ACHOrigRecvAccount a ON a.recvAcctSerNo = t.recvAcctSerNo "
                + "AND a.recvAcctVersion = t.recvAcctVersion WHERE t.ofacStatus = 'FLAGGED' AND t.transferVersion = 1 ORDER BY t.ofacTm, t.transferSerNo",
                (rs, i) -> new Flagged(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4),
                        rs.getObject(5, LocalDate.class), rs.getObject(6, LocalDateTime.class)));
    }

    /** 6. Confirms the hold placed at Confirm, or places it for a later payment of a series. */
    @PostMapping("/api/v1/accounts/memo-post")
    public MemoResponse memoPost(@RequestBody MemoRequest body) {
        OriginationWindow.Transfer t = transfer(body.transferId());
        window.placeHold(t).ifPresent(problem -> {
            throw new IllegalArgumentException("The hold couldn't be placed: " + problem);
        });
        Long memoID = jdbc.queryForObject("SELECT MAX(memoPostID) FROM ACHMemoPost WHERE tranSource = 'ORIG' AND tranRef = ? AND postType = 'HOLD'",
                Long.class, t.serNo());
        BigDecimal available = jdbc.queryForObject("SELECT availBal FROM SimCoreAccount WHERE acctNo = ?", BigDecimal.class, t.acctNo());
        return new MemoResponse(memoID == null ? null : "MEMO-" + memoID, t.acctNo(), "APPLIED", available, OffsetDateTime.now(clock).withNano(0));
    }

    /** 7. Only a transfer that passed steps 2-6 (OFAC passed or cleared, hold placed) can be made ready to post. */
    @PatchMapping("/api/v1/ach/transfers/status")
    public StatusResponse status(@RequestBody StatusRequest body) {
        if (!OriginationWindow.READY_TO_POST.equals(body.targetStatus())) {
            throw new IllegalArgumentException("target_status must be READY_TO_POST.");
        }
        int updated = 0;
        for (String serNo : body.transferIds() == null ? List.<String>of() : body.transferIds()) {
            updated += jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET processStatus = ?, processTm = NOW() WHERE transferSerNo = ? "
                    + "AND transferVersion = 1 AND processStatus = ? AND ofacStatus IN ('OFAC_PASSED', 'CLEARED') AND memoStatus = 'POSTED'",
                    OriginationWindow.READY_TO_POST, serNo, OriginationWindow.PENDING_BATCH);
        }
        return new StatusResponse(updated, "SUCCESS", "EOD-POST-" + LocalDate.now(clock).format(DAY));
    }

    private OriginationWindow.Transfer transfer(String serNo) {
        return window.load(serNo, false).orElseThrow(() -> new IllegalArgumentException("No such transfer " + serNo + "."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
