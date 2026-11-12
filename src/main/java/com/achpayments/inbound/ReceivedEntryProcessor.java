package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.achpayments.common.AppLogRepository;
import com.achpayments.memo.MemoPostingEngine;
import com.achpayments.nacha.AchFileValidator;
import com.achpayments.param.ParamCache;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Ba's blueprint, ACH Receiving Processing steps 4-6, run on the entries of one file. Entry statuses:
 * RECEIVED -> AUTO_CORRECTED or MANUAL_REVIEW (step 4) -> REJECTED (step 5) -> MEMO_POSTED (step 6) -> POSTED (step 7).
 */
@Service
public class ReceivedEntryProcessor {

    public static final String RECEIVED = "RECEIVED", AUTO_CORRECTED = "AUTO_CORRECTED", MANUAL_REVIEW = "MANUAL_REVIEW",
            REJECTED = "REJECTED", MEMO_POSTED = "MEMO_POSTED", POSTED = "POSTED", PRENOTE = "PRENOTE";

    /** NACHA transaction code -> the simulated core's transaction code. Return and NOC codes aren't posted by this flow. */
    static final Map<String, String> CORE_TRAN_CODES = Map.of(
            "22", "DDA_CR", "27", "DDA_DR", "32", "SAV_CR", "37", "SAV_DR",
            "23", PRENOTE, "28", PRENOTE, "33", PRENOTE, "38", PRENOTE);

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Detail(String transactionId, String originalAccount, String correctedAccount, String action, String reason) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TranscodeResult(String fileId, int processedCount, int matchedAccounts, int autoCorrectedAccounts,
            int manualReviewRequired, List<Detail> details) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RejectedItem(String transactionId, String returnCode, String returnReason, BigDecimal amount,
            String creditDebitIndicator) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RejectionResult(String fileId, int evaluatedCount, int acceptedCount, int rejectedCount,
            List<RejectedItem> rejectedItems) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MemoResult(String fileId, int totalEligible, int memoPostedCount, BigDecimal totalMemoCredits,
            BigDecimal totalMemoDebits, String status) {
    }

    /** What steps 4-6 did to one file, for the window run (V19, ACHRecvWindowRun). */
    public record Counts(int entries, int matched, int corrected, int review, int rejected, int memoPosted) {
    }

    private record Return(String code, String reason) {
    }

    private final JdbcTemplate jdbc;
    private final MemoPostingEngine memo;
    private final ParamCache params;
    private final AppLogRepository appLog;
    private final Clock clock;
    private final String recv;

    public ReceivedEntryProcessor(JdbcTemplate jdbc, MemoPostingEngine memo, ParamCache params, AppLogRepository appLog, Clock clock,
            @Value("${app.db.receiving}") String recv) {
        this.jdbc = jdbc;
        this.memo = memo;
        this.params = params;
        this.appLog = appLog;
        this.clock = clock;
        this.recv = recv;
    }

    /** Steps 4-6, as a processing window runs them. */
    public Counts process(String fileID) {
        TranscodeResult t = processTranscodesAndAccounts(fileID);
        RejectionResult r = evaluateRejections(fileID);
        MemoResult m = memoPost(fileID, true);
        appLog.log(ACH_RCV, "INFO", "%s processed: %d matched, %d auto-corrected, %d for manual review, %d rejected, %d memo posted"
                .formatted(fileID, t.matchedAccounts(), t.autoCorrectedAccounts(), t.manualReviewRequired(), r.rejectedCount(),
                        m.memoPostedCount()));
        return new Counts(t.processedCount(), t.matchedAccounts(), t.autoCorrectedAccounts(), t.manualReviewRequired(), r.rejectedCount(),
                m.memoPostedCount());
    }

    /** Step 4: map the transaction code, then find the account: exact, a known alias (ACHRecvAcctSubs), or zero padding. */
    @Transactional
    public TranscodeResult processTranscodesAndAccounts(String fileID) {
        requireFile(fileID);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT tranID, tranCode, recvAcctID, recvRtn FROM " + recv
                + ".ACHRecvTransaction WHERE fileID = ? AND processStatus = ? AND coreTranCode IS NULL ORDER BY tranID", fileID, RECEIVED);
        int matched = 0, corrected = 0, review = 0;
        List<Detail> details = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("tranID"));
            String account = (String) row.get("recvAcctID");
            String core = CORE_TRAN_CODES.get((String) row.get("tranCode"));
            if (core == null) {
                update(id, MANUAL_REVIEW, null, null, "UNSUPPORTED_TRAN_CODE");
                details.add(new Detail(id, account, null, "MANUAL_REVIEW_FLAGGED", "UNSUPPORTED_TRAN_CODE"));
                review++;
                continue;
            }
            if (exists(account)) {
                update(id, RECEIVED, core, null, null);
                matched++;
                continue;
            }
            String alias = alias(account, (String) row.get("recvRtn"));
            String padded = alias == null ? zeroPadded(account) : null;
            if (alias != null || padded != null) {
                String reason = alias != null ? "KNOWN_ALIAS" : "ZERO_PADDING";
                update(id, AUTO_CORRECTED, core, alias != null ? alias : padded, reason);
                details.add(new Detail(id, account, alias != null ? alias : padded, "AUTO_CORRECTED", reason));
                corrected++;
            } else {
                update(id, MANUAL_REVIEW, core, null, "ACCOUNT_NOT_FOUND");
                details.add(new Detail(id, account, null, "MANUAL_REVIEW_FLAGGED", "ACCOUNT_NOT_FOUND"));
                review++;
            }
        }
        return new TranscodeResult(fileID, rows.size(), matched, corrected, review, details);
    }

    /**
     * Step 5: return codes. An account still not found after step 4 is R03. Closed R02, frozen R16, deceased R15. Debits also
     * check stop payments (R08), originator blocks (R10 consumer, R29 corporate) and funds (R01). An entry over
     * ACH_RCV/MAX_ENTRY_AMOUNT isn't returned; it waits for manual review.
     */
    @Transactional
    public RejectionResult evaluateRejections(String fileID) {
        requireFile(fileID);
        List<Map<String, Object>> rows = entries(fileID, "processStatus IN ('RECEIVED', 'AUTO_CORRECTED', 'MANUAL_REVIEW')");
        BigDecimal limit = params.get("ACH_RCV", "MAX_ENTRY_AMOUNT").map(BigDecimal::new).orElse(null);
        List<RejectedItem> rejected = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Return r = returnFor(row);
            String id = String.valueOf(row.get("tranID"));
            BigDecimal amount = (BigDecimal) row.get("amount");
            if (r != null) {
                reject(row, r);
                rejected.add(new RejectedItem(id, r.code(), r.reason(), amount, isCredit(row) ? "CREDIT" : "DEBIT"));
            } else if (limit != null && amount.compareTo(limit) > 0 && !MANUAL_REVIEW.equals(row.get("processStatus"))) {
                jdbc.update("UPDATE " + recv + ".ACHRecvTransaction SET processStatus = ?, processReason = 'OVER_LIMIT' WHERE tranID = ?",
                        MANUAL_REVIEW, row.get("tranID"));
            }
        }
        return new RejectionResult(fileID, rows.size(), rows.size() - rejected.size(), rejected.size(), rejected);
    }

    /**
     * Step 6: debits are held at once; credits are made available at once only from a trusted Company ID
     * (ACH_RCV/TRUSTED_COMPANY_IDS). Other credits wait for the end-of-day posting file. With eligibleOnly false, every entry
     * is memo posted.
     */
    @Transactional
    public MemoResult memoPost(String fileID, boolean eligibleOnly) {
        requireFile(fileID);
        Set<String> trusted = Arrays.stream(params.get("ACH_RCV", "TRUSTED_COMPANY_IDS").orElse("").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        List<Map<String, Object>> rows = entries(fileID, "processStatus IN ('RECEIVED', 'AUTO_CORRECTED') AND coreTranCode <> 'PRENOTE'");
        int eligible = 0, posted = 0;
        BigDecimal credits = BigDecimal.ZERO, debits = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            boolean credit = isCredit(row);
            if (eligibleOnly && credit && !trusted.contains((String) row.get("companyID"))) {
                continue;
            }
            eligible++;
            BigDecimal amount = (BigDecimal) row.get("amount");
            MemoPostingEngine.Result result = memo.post(new MemoPostingEngine.Request(MemoPostingEngine.Source.RECV,
                    String.valueOf(row.get("tranID")), (String) row.get("acct"), credit, amount, MemoPostingEngine.Screening.ALLOW));
            if (result.posted()) {
                jdbc.update("UPDATE " + recv + ".ACHRecvTransaction SET processStatus = ?, memoPostedTm = ? WHERE tranID = ?",
                        MEMO_POSTED, LocalDateTime.now(clock).withNano(0), row.get("tranID"));
                posted++;
                if (credit) {
                    credits = credits.add(amount);
                } else {
                    debits = debits.add(amount);
                }
            } else if (result.status() == MemoPostingEngine.Status.NSF) { // spent since step 5
                reject(row, new Return("R01", "INSUFFICIENT_FUNDS"));
            }
        }
        return new MemoResult(fileID, eligible, posted, credits, debits, "MEMO_POST_COMPLETED");
    }

    private Return returnFor(Map<String, Object> row) {
        if (MANUAL_REVIEW.equals(row.get("processStatus"))) {
            return "ACCOUNT_NOT_FOUND".equals(row.get("processReason")) ? new Return("R03", "NO_ACCOUNT_UNABLE_TO_LOCATE") : null;
        }
        String account = (String) row.get("acct");
        Map<String, Object> core = jdbc.queryForMap("SELECT acctStatus, availBal FROM SimCoreAccount WHERE acctNo = ?", account);
        switch ((String) core.get("acctStatus")) {
            case "CLOSED" -> {
                return new Return("R02", "ACCOUNT_CLOSED");
            }
            case "FROZEN" -> {
                return new Return("R16", "ACCOUNT_FROZEN");
            }
            case "DECEASED" -> {
                return new Return("R15", "BENEFICIARY_DECEASED");
            }
            default -> {
            }
        }
        if (isCredit(row)) {
            return null;
        }
        BigDecimal amount = (BigDecimal) row.get("amount");
        String companyID = (String) row.get("companyID");
        Integer stops = jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvStopPayment WHERE acctNo = ? "
                + "AND (companyID IS NULL OR companyID = ?) AND (amount IS NULL OR amount = ?)", Integer.class, account, companyID, amount);
        if (stops != null && stops > 0) {
            return new Return("R08", "PAYMENT_STOPPED");
        }
        Integer blocks = jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvBlockedOriginator WHERE acctNo = ? AND companyID = ?",
                Integer.class, account, companyID);
        if (blocks != null && blocks > 0) {
            return Set.of("CCD", "CTX").contains((String) row.get("stdEntryCode"))
                    ? new Return("R29", "CORPORATE_NOT_AUTHORIZED") : new Return("R10", "CUSTOMER_ADVISES_NOT_AUTHORIZED");
        }
        if (((BigDecimal) core.get("availBal")).compareTo(amount) < 0) {
            return new Return("R01", "INSUFFICIENT_FUNDS");
        }
        return null;
    }

    /** Marks the entry and records the return to send back to the ODFI (sending it is a later step, Q5). */
    private void reject(Map<String, Object> row, Return r) {
        Object tranID = row.get("tranID");
        jdbc.update("UPDATE " + recv + ".ACHRecvTransaction SET processStatus = ?, returnCode = ?, processReason = ? WHERE tranID = ?",
                REJECTED, r.code(), r.reason(), tranID);
        String rdfi = params.get("ACH_RCV", "RDFI_ROUTING_NO").orElseThrow();
        String odfi8 = ((String) row.get("traceNo")).substring(0, 8);
        LocalDate today = LocalDate.now(clock);
        jdbc.update("INSERT INTO " + recv + ".ACHRecvReturn (tranID, typeID, sourceID, recvTraceNo, returnTraceNo, amount, acctName, "
                + "returnStatus, returnCode, companyName, companyID, discretionaryData, returnAcctNo, returnRtn, returnDt, lastModBy, dueDt) "
                + "VALUES (?, 'RETURN', 'FED_ACH_INBOUND', ?, ?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, ?, ?, 'ACH_SYS', ?)",
                tranID, row.get("traceNo"), rdfi.substring(0, 8) + "%07d".formatted(((Number) tranID).longValue() % 10_000_000),
                row.get("amount"), row.get("recvName"), r.code(), row.get("companyName"), row.get("companyID"), row.get("companyDiscData"),
                row.get("recvAcctID"), odfi8 + checkDigit(odfi8), today, today);
    }

    private List<Map<String, Object>> entries(String fileID, String where) {
        return jdbc.queryForList("SELECT tranID, tranCode, creditFlag, amount, companyID, companyName, companyDiscData, stdEntryCode, "
                + "recvName, recvAcctID, COALESCE(correctedAcctNo, recvAcctID) AS acct, traceNo, processStatus, processReason FROM " + recv
                + ".ACHRecvTransaction WHERE fileID = ? AND " + where + " ORDER BY tranID", fileID);
    }

    private void update(String tranID, String status, String core, String correctedAcct, String reason) {
        jdbc.update("UPDATE " + recv + ".ACHRecvTransaction SET processStatus = ?, coreTranCode = ?, correctedAcctNo = ?, processReason = ? "
                + "WHERE tranID = ?", status, core, correctedAcct, reason, tranID);
    }

    private boolean exists(String account) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM SimCoreAccount WHERE acctNo = ?", Integer.class, account);
        return n != null && n > 0;
    }

    private String alias(String account, String routing) {
        return jdbc.queryForList("SELECT s.subsAcctNo FROM " + recv + ".ACHRecvAcctSubs s JOIN SimCoreAccount c ON c.acctNo = s.subsAcctNo "
                + "WHERE s.nocAcctNo = ? AND s.nocRoutingNo = ? AND s.acctStatus = 'ACTIVE'", String.class, account, routing)
                .stream().findFirst().orElse(null);
    }

    /** The one core account that's the same number with leading zeros added or removed, e.g. 982341 -> 000982341. */
    private String zeroPadded(String account) {
        List<String> found = jdbc.queryForList("SELECT acctNo FROM SimCoreAccount WHERE TRIM(LEADING '0' FROM acctNo) = TRIM(LEADING '0' FROM ?)",
                String.class, account);
        return found.size() == 1 ? found.get(0) : null;
    }

    private void requireFile(String fileID) {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvFile WHERE fileID = ?", Integer.class, fileID);
        if (n == null || n == 0) {
            throw new IllegalArgumentException("No such file " + fileID + ".");
        }
    }

    private static boolean isCredit(Map<String, Object> row) {
        return !AchFileValidator.isDebit((String) row.get("tranCode"));
    }

    private static String checkDigit(String routing8) {
        int[] weights = {3, 7, 1, 3, 7, 1, 3, 7};
        int sum = 0;
        for (int i = 0; i < 8; i++) {
            sum += (routing8.charAt(i) - '0') * weights[i];
        }
        return String.valueOf((10 - sum % 10) % 10);
    }
}
