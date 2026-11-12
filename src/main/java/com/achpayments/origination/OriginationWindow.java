package com.achpayments.origination;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.achpayments.common.AppLogRepository;
import com.achpayments.memo.MemoPostingEngine;
import com.achpayments.memo.MemoPostingEngine.Screening;
import com.achpayments.memo.MemoPostingEngine.Source;
import com.achpayments.transfer.OfacScreening;
import com.achpayments.transfer.TransferService;

/**
 * Ba's blueprint, ACH Origination Processing steps 1-7, run on each submitted transfer whose effective date has come:
 * account status and balance, limits, OFAC, the memo hold, then READY_TO_POST for the EOD file (step 8). The hold was
 * placed when the member confirmed (Q3); this step only places one for a later payment of a recurring series. A failure
 * rejects the transfer and gives its hold back; an OFAC hit leaves it FLAGGED, with its hold, for a compliance officer.
 */
@Service
public class OriginationWindow {

    public static final String PENDING_BATCH = "PENDING_BATCH", READY_TO_POST = "READY_TO_POST", REJECTED = "REJECTED",
            FLAGGED = "FLAGGED", CLEARED = "CLEARED", BLOCKED = "BLOCKED", OFAC_PASSED = "OFAC_PASSED";
    /** lastModBy on a transfer the window rejects; the EOD screen lists them. */
    public static final String WINDOW_USER = "ORIG_WINDOW";
    public static final String CLEAR_RESOLUTION = "CLEARED_FALSE_POSITIVE", BLOCK_RESOLUTION = "BLOCKED_CONFIRMED_MATCH";

    /** One window run: what each step did (V19, ACHOrigWindowRun). */
    public record Run(String windowId, String windowTime, int extracted, int ready, int flagged, int acctRejected, int limitRejected,
            int holdRejected) {
        public int rejected() {
            return acctRejected + limitRejected + holdRejected;
        }
    }

    /** One row of the processing schedule (V19, ACHOrigSchedule). */
    public record Window(int scheduleId, String windowTime, String windowName) {
    }

    /** Where a transfer's run through steps 2-7 ended. */
    enum Outcome {
        READY, FLAGGED, ACCOUNT, LIMIT, HOLD, SKIPPED
    }

    public record Resolution(String transferId, String previousStatus, String currentStatus) {
    }

    /** One transfer as the window sees it. */
    record Transfer(String serNo, String memberNo, String acctNo, String acctType, BigDecimal amount, BigDecimal fee,
            String processStatus, String ofacStatus, String memoStatus, String recvName) {
        BigDecimal total() {
            return amount.add(fee);
        }
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final TransferService transfers;
    private final OfacScreening ofac;
    private final MemoPostingEngine memo;
    private final AppLogRepository appLog;
    private final Clock clock;
    private final String orig;

    public OriginationWindow(JdbcTemplate jdbc, TransactionTemplate transaction, TransferService transfers, OfacScreening ofac,
            MemoPostingEngine memo, AppLogRepository appLog, Clock clock, @Value("${app.db.origination}") String orig) {
        this.jdbc = jdbc;
        this.transaction = transaction;
        this.transfers = transfers;
        this.ofac = ofac;
        this.memo = memo;
        this.appLog = appLog;
        this.clock = clock;
        this.orig = orig;
    }

    /** A scheduled window. */
    public Run run(LocalDate businessDate, String windowTime) {
        return run(businessDate, windowTime, "SCHEDULER");
    }

    /** Steps 1-7 for every transfer due by the business date. Each transfer is its own DB transaction. */
    public Run run(LocalDate businessDate, String windowTime, String runBy) {
        LocalDateTime started = LocalDateTime.now(clock).withNano(0);
        String windowId = "WIN-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + windowTime.replace(":", "");
        int carried = carryOver(businessDate, windowTime);
        List<String> due = extract(businessDate);
        Map<Outcome, Integer> n = new EnumMap<>(Outcome.class);
        for (String serNo : due) {
            n.merge(transaction.execute(s -> process(serNo, windowTime)), 1, Integer::sum);
        }
        Run run = new Run(windowId, windowTime, due.size(), n.getOrDefault(Outcome.READY, 0), n.getOrDefault(Outcome.FLAGGED, 0),
                n.getOrDefault(Outcome.ACCOUNT, 0), n.getOrDefault(Outcome.LIMIT, 0), n.getOrDefault(Outcome.HOLD, 0));
        jdbc.update("INSERT INTO " + orig + ".ACHOrigWindowRun (windowID, scheduleID, businessDt, windowTime, extractedCnt, acctRejectCnt, "
                + "limitRejectCnt, flaggedCnt, holdRejectCnt, readyCnt, runBy, startTm, endTm) VALUES (?, (SELECT scheduleID FROM " + orig
                + ".ACHOrigSchedule WHERE windowTime = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", windowId, windowTime, businessDate, windowTime, run.extracted(), run.acctRejected(), run.limitRejected(), run.flagged(),
                run.holdRejected(), run.ready(), runBy, started, LocalDateTime.now(clock).withNano(0));
        appLog.log(ACH_ORIG, "INFO", "%s: %d transfer(s) due, %d ready to post, %d flagged by OFAC, %d rejected%s"
                .formatted(windowId, due.size(), run.ready(), run.flagged(), run.rejected(),
                        carried == 0 ? "" : ", %d carried over from an earlier day".formatted(carried)));
        return run;
    }

    /**
     * Ba (2026-11-12): anything ready that no schedule sent goes in the next business day's first window (6 AM). Ready means
     * READY_TO_POST on an earlier day, or made ready outside a window (no scheduleID).
     */
    private int carryOver(LocalDate businessDate, String windowTime) {
        List<Window> windows = schedule();
        if (windows.isEmpty() || !windows.get(0).windowTime().equals(windowTime)) {
            return 0;
        }
        return jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET scheduleID = ? WHERE processStatus = ? AND transferVersion = 1 "
                + "AND (scheduleID IS NULL OR processTm < ?)", windows.get(0).scheduleId(), READY_TO_POST, businessDate.atStartOfDay());
    }

    /** The active windows, in time order. */
    public List<Window> schedule() {
        return jdbc.query("SELECT scheduleID, windowTime, windowName FROM " + orig + ".ACHOrigSchedule WHERE status = 'ACTIVE' "
                + "ORDER BY windowTime", (rs, i) -> new Window(rs.getInt(1), rs.getString(2), rs.getString(3)));
    }

    /** The window runs for a business date, in order, for the ACH Origination Processing screen. */
    public List<Map<String, Object>> runs(LocalDate businessDate) {
        return jdbc.queryForList("SELECT windowID, windowTime, extractedCnt, acctRejectCnt, limitRejectCnt, flaggedCnt, holdRejectCnt, "
                + "readyCnt, runBy, startTm FROM " + orig + ".ACHOrigWindowRun WHERE businessDt = ? ORDER BY runID", businessDate);
    }

    /** Step 1: submitted transfers, and later payments of a series, whose effective date has come. */
    public List<String> extract(LocalDate businessDate) {
        return jdbc.queryForList("SELECT transferSerNo FROM " + orig + ".ACHOriginatorTransfer WHERE processStatus = ? "
                + "AND transferVersion = 1 AND effectiveDt <= ? ORDER BY transferSerNo", String.class, PENDING_BATCH, businessDate);
    }

    /** Steps 2-7 for one transfer. */
    Outcome process(String serNo, String windowTime) {
        Transfer t = load(serNo, true).orElse(null);
        if (t == null || !t.processStatus().equals(PENDING_BATCH)) {
            return Outcome.SKIPPED; // changed since it was extracted
        }
        Optional<String> problem = accountProblem(t);
        if (problem.isPresent()) {
            reject(t, problem.get());
            return Outcome.ACCOUNT;
        }
        problem = transfers.limitProblem(t.memberNo(), t.acctType(), BigDecimal.ZERO).map(m -> "LIMIT_EXCEEDED: " + m);
        if (problem.isPresent()) {
            reject(t, problem.get());
            return Outcome.LIMIT;
        }
        if (!t.ofacStatus().equals(CLEARED)) {
            if (ofac.isHit(t.recvName())) {
                flag(t);
                return Outcome.FLAGGED;
            }
            jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET ofacStatus = ?, ofacTm = NOW() WHERE transferSerNo = ? "
                    + "AND transferVersion = 1", OFAC_PASSED, serNo);
        }
        Optional<String> hold = placeHold(t);
        if (hold.isPresent()) {
            reject(t, hold.get());
            return Outcome.HOLD;
        }
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET processStatus = ?, processTm = NOW(), scheduleID = (SELECT scheduleID FROM "
                + orig + ".ACHOrigSchedule WHERE windowTime = ?) WHERE transferSerNo = ? AND transferVersion = 1", READY_TO_POST, windowTime, serNo);
        return Outcome.READY;
    }

    /** Step 2: the funding account must be active and, if no hold is placed yet, cover the amount and fee. */
    Optional<String> accountProblem(Transfer t) {
        Map<String, Object> acct = jdbc.queryForMap("SELECT acctStatus, availBal FROM SimCoreAccount WHERE acctNo = ?", t.acctNo());
        if (!"ACTIVE".equals(acct.get("acctStatus"))) {
            return Optional.of("ACCOUNT_" + acct.get("acctStatus"));
        }
        if (!"POSTED".equals(t.memoStatus()) && ((BigDecimal) acct.get("availBal")).compareTo(t.total()) < 0) {
            return Optional.of("INSUFFICIENT_FUNDS");
        }
        return Optional.empty();
    }

    /** Step 6: the hold placed at Confirm is already there; a later payment of a series places its own (E4). */
    Optional<String> placeHold(Transfer t) {
        if ("POSTED".equals(t.memoStatus())) {
            return Optional.empty();
        }
        var result = memo.post(new MemoPostingEngine.Request(Source.ORIG, t.serNo(), t.acctNo(), false, t.total(), Screening.ALLOW));
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET memoStatus = ?, memoTm = NOW() WHERE transferSerNo = ? "
                + "AND transferVersion = 1", result.status().name(), t.serNo());
        return result.posted() ? Optional.empty() : Optional.of(result.status().name() + ": " + result.reason());
    }

    /** Step 4: waits for a compliance officer, keeping its hold. */
    void flag(Transfer t) {
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET ofacStatus = ?, ofacTm = NOW(), processStatus = 'PENDING' "
                + "WHERE transferSerNo = ? AND transferVersion = 1", FLAGGED, t.serNo());
        appLog.log(ACH_ORIG, "WARN", "Compliance alert: " + t.serNo() + " is flagged by OFAC and waits for review");
    }

    /** Step 5: a compliance officer clears a false positive (it goes on at the next window) or blocks a true match. */
    @Transactional
    public Resolution resolve(String serNo, String resolution, String officer, String notes) {
        Transfer t = load(serNo, true).orElseThrow(() -> new IllegalArgumentException("No such transfer " + serNo + "."));
        if (!t.ofacStatus().equals(FLAGGED)) {
            throw new IllegalArgumentException(serNo + " isn't waiting for OFAC review (ofacStatus " + t.ofacStatus() + ").");
        }
        String current;
        if (CLEAR_RESOLUTION.equals(resolution)) {
            current = CLEARED;
            jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET ofacStatus = ?, ofacTm = NOW(), processStatus = ?, lastModBy = ? "
                    + "WHERE transferSerNo = ? AND transferVersion = 1", CLEARED, PENDING_BATCH, officer, serNo);
        } else if (BLOCK_RESOLUTION.equals(resolution)) {
            current = BLOCKED;
            jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET ofacStatus = ?, ofacTm = NOW(), lastModBy = ? "
                    + "WHERE transferSerNo = ? AND transferVersion = 1", BLOCKED, officer, serNo);
            reject(t, "OFAC_BLOCKED");
        } else {
            throw new IllegalArgumentException("resolution must be " + CLEAR_RESOLUTION + " or " + BLOCK_RESOLUTION + ".");
        }
        jdbc.update("INSERT INTO " + orig + ".ACHOrigOfacReview (transferSerNo, previousStatus, resolution, currentStatus, "
                + "complianceOfficerID, notes) VALUES (?, ?, ?, ?, ?, ?)", serNo, FLAGGED, resolution, current, officer, notes);
        appLog.log(ACH_ORIG, "INFO", "Compliance officer '%s' %s %s".formatted(officer, current.toLowerCase(), serNo));
        return new Resolution(serNo, FLAGGED, current);
    }

    /** Rejects the transfer, gives its hold back, and schedules the next payment of a series (E4). */
    void reject(Transfer t, String reason) {
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET processStatus = ?, processTm = NOW(), "
                + "lastModBy = IF(ofacStatus = ?, lastModBy, ?) WHERE transferSerNo = ? AND transferVersion = 1",
                REJECTED, BLOCKED, WINDOW_USER, t.serNo()); // a block keeps the officer's name
        if ("POSTED".equals(t.memoStatus())) {
            jdbc.update("UPDATE SimCoreAccount SET availBal = availBal + ? WHERE acctNo = ?", t.total(), t.acctNo());
            jdbc.update("INSERT INTO ACHMemoPost (tranSource, tranRef, acctNo, creditFlag, amount, postType, memoStatus, reason) "
                    + "VALUES ('ORIG', ?, ?, 1, ?, 'RELEASE', 'POSTED', ?)", t.serNo(), t.acctNo(), t.total(), reason);
            jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET memoStatus = 'RELEASED', memoTm = NOW() WHERE transferSerNo = ? "
                    + "AND transferVersion = 1", t.serNo());
        }
        appLog.log(ACH_ORIG, "WARN", "Origination window: " + t.serNo() + " rejected, " + reason);
        transfers.scheduleNextPayment(t.serNo());
    }

    Optional<Transfer> load(String serNo, boolean forUpdate) {
        return jdbc.query("SELECT t.transferSerNo, t.memberNo, t.origAcctNo, t.origAcctType, t.transferAmt, t.feeAmt, t.processStatus, "
                + "t.ofacStatus, t.memoStatus, a.acctOwner FROM " + orig + ".ACHOriginatorTransfer t JOIN " + orig + ".ACHOrigRecvAccount a "
                + "ON a.recvAcctSerNo = t.recvAcctSerNo AND a.recvAcctVersion = t.recvAcctVersion "
                + "WHERE t.transferSerNo = ? AND t.transferVersion = 1" + (forUpdate ? " FOR UPDATE" : ""),
                (rs, i) -> new Transfer(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBigDecimal(5),
                        rs.getBigDecimal(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10)), serNo)
                .stream().findFirst();
    }
}
