package com.achpayments.origination;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
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
    public static final String CLEAR_RESOLUTION = "CLEARED_FALSE_POSITIVE", BLOCK_RESOLUTION = "BLOCKED_CONFIRMED_MATCH";

    public record Run(String windowId, String windowTime, int extracted, int ready, int flagged, int rejected) {
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

    /** Steps 1-7 for every transfer due by the business date. Each transfer is its own DB transaction. */
    public Run run(LocalDate businessDate, String windowTime) {
        String windowId = "WIN-" + businessDate.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + windowTime.replace(":", "");
        List<String> due = extract(businessDate);
        int ready = 0, flagged = 0, rejected = 0;
        for (String serNo : due) {
            String outcome = transaction.execute(s -> process(serNo));
            switch (outcome) {
                case READY_TO_POST -> ready++;
                case FLAGGED -> flagged++;
                case REJECTED -> rejected++;
                default -> {
                }
            }
        }
        appLog.log(ACH_ORIG, "INFO", "%s: %d transfer(s) due, %d ready to post, %d flagged by OFAC, %d rejected"
                .formatted(windowId, due.size(), ready, flagged, rejected));
        return new Run(windowId, windowTime, due.size(), ready, flagged, rejected);
    }

    /** Step 1: submitted transfers, and later payments of a series, whose effective date has come. */
    public List<String> extract(LocalDate businessDate) {
        return jdbc.queryForList("SELECT transferSerNo FROM " + orig + ".ACHOriginatorTransfer WHERE processStatus = ? "
                + "AND transferVersion = 1 AND effectiveDt <= ? ORDER BY transferSerNo", String.class, PENDING_BATCH, businessDate);
    }

    /** Steps 2-7 for one transfer. Returns its new processStatus, or FLAGGED. */
    String process(String serNo) {
        Transfer t = load(serNo, true).orElse(null);
        if (t == null || !t.processStatus().equals(PENDING_BATCH)) {
            return "SKIPPED"; // changed since it was extracted
        }
        Optional<String> problem = accountProblem(t);
        if (problem.isEmpty()) {
            problem = transfers.limitProblem(t.memberNo(), t.acctType(), BigDecimal.ZERO).map(m -> "LIMIT_EXCEEDED: " + m);
        }
        if (problem.isPresent()) {
            reject(t, problem.get());
            return REJECTED;
        }
        if (!t.ofacStatus().equals(CLEARED)) {
            if (ofac.isHit(t.recvName())) {
                flag(t);
                return FLAGGED;
            }
            jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET ofacStatus = ?, ofacTm = NOW() WHERE transferSerNo = ? "
                    + "AND transferVersion = 1", OFAC_PASSED, serNo);
        }
        Optional<String> hold = placeHold(t);
        if (hold.isPresent()) {
            reject(t, hold.get());
            return REJECTED;
        }
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET processStatus = ?, processTm = NOW() WHERE transferSerNo = ? "
                + "AND transferVersion = 1", READY_TO_POST, serNo);
        return READY_TO_POST;
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
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET processStatus = ?, processTm = NOW() WHERE transferSerNo = ? "
                + "AND transferVersion = 1", REJECTED, t.serNo());
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
