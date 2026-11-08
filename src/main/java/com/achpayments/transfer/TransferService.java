package com.achpayments.transfer;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.achpayments.common.AppLogRepository;
import com.achpayments.memo.MemoPostingEngine;
import com.achpayments.memo.MemoPostingEngine.Screening;
import com.achpayments.memo.MemoPostingEngine.Source;
import com.achpayments.nacha.AchFileValidator;
import com.achpayments.param.ParamCache;
import com.achpayments.transfer.TransferRejected.Kind;

/**
 * UC-ACH-ORG-01 External ACH Transfer: the server-side checks (steps 2-5, EX-01..03) and the submit (steps 6-7).
 * Every check runs again on submit, so a payload that skips the UI's validation is still rejected.
 */
@Service
public class TransferService {

    /** USE-CASES T2: the credit union is the originator for retail members' external transfers. */
    static final String CREDIT_UNION_ORIGINATOR = "9000000001";
    static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999.99"); // The design's DECIMAL(13,2)
    static final Set<String> FREQUENCIES = Set.of("ONETIME", "WEEKLY", "BIWEEKLY", "MONTHLY");
    static final String NOT_VERIFIED = "We couldn't verify this account for ACH transfers. Check the details or use a different account.";

    public record Account(String acctNo, String acctName, String acctType, BigDecimal availBal) {
    }

    public record Preview(String bankName, BigDecimal amount, BigDecimal fee, BigDecimal total, LocalDate effectiveDt,
            LocalDate estimatedArrival, boolean underReview) {
    }

    public record Receipt(String transferSerNo, String processStatus, String ofacStatus, String memoStatus,
            String recvName, String bankName, String routingNo, String acctLast4, String acctType,
            String fromAcctName, String fromAcctLast4, BigDecimal amount, BigDecimal fee, BigDecimal total,
            LocalDate effectiveDt, LocalDate estimatedArrival, String frequency, int transferCount, String memo,
            LocalDateTime submitted) {
    }

    /** A request that passed every check, with the values to store. */
    private record Checked(Account from, String recvAcctSerNo, String recvName, String routingNo, String acctNo, String acctType,
            BigDecimal amount, BigDecimal fee, LocalDate effectiveDt, String frequency, int count, String memo,
            String bankName, boolean ofacHit) {
        BigDecimal total() {
            return amount.add(fee);
        }
    }

    private final JdbcTemplate jdbc;
    private final RoutingService routing;
    private final TransferDates dates;
    private final AccountVerifier verifier;
    private final MemoPostingEngine memo;
    private final ParamCache params;
    private final OfacScreening ofac;
    private final AppLogRepository appLog;
    private final Clock clock;
    private final String orig;

    public TransferService(JdbcTemplate jdbc, RoutingService routing, TransferDates dates, AccountVerifier verifier,
            MemoPostingEngine memo, ParamCache params, OfacScreening ofac, AppLogRepository appLog, Clock clock,
            @Value("${app.db.origination}") String orig) {
        this.jdbc = jdbc;
        this.routing = routing;
        this.dates = dates;
        this.verifier = verifier;
        this.memo = memo;
        this.params = params;
        this.ofac = ofac;
        this.appLog = appLog;
        this.clock = clock;
        this.orig = orig;
    }

    /** Steps 1-2: the member's active funding accounts and their available balances. */
    public List<Account> accounts(String memberNo) {
        return jdbc.query("SELECT acctNo, acctName, acctType, availBal FROM SimCoreAccount "
                + "WHERE memberNo = ? AND acctStatus = 'ACTIVE' ORDER BY acctType, acctNo",
                (rs, i) -> new Account(rs.getString(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4)), memberNo);
    }

    /** Step 5 pre-flight: every check, without saving anything. */
    public Preview preview(String memberNo, TransferRequest r) {
        Checked c = check(memberNo, r);
        return new Preview(c.bankName(), c.amount(), c.fee(), c.total(), c.effectiveDt(),
                dates.estimatedArrival(c.effectiveDt()), c.ofacHit());
    }

    /** Steps 6-7: save the transfer as PENDING and place the memo hold, in one DB transaction. */
    @Transactional
    public Receipt submit(String memberNo, TransferRequest r) {
        Checked c = check(memberNo, r);
        activate(memberNo, c.recvAcctSerNo());
        String serNo = insertTransfer(memberNo, c.recvAcctSerNo(), c);
        MemoPostingEngine.Result hold = memo.post(new MemoPostingEngine.Request(Source.ORIG, serNo, c.from().acctNo(),
                false, c.total(), c.ofacHit() ? Screening.REVIEW : Screening.ALLOW));
        if (hold.status() == MemoPostingEngine.Status.NSF) {
            throw new TransferRejected(Kind.FUNDS, "Your available balance isn't enough for this transfer.");
        }
        if (hold.status() == MemoPostingEngine.Status.INELIGIBLE) {
            throw new TransferRejected(Kind.INVALID, "This account can't be used for transfers right now.");
        }
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET memoStatus = ?, memoTm = NOW() "
                + "WHERE transferSerNo = ? AND transferVersion = 1", hold.status().name(), serNo);
        appLog.log(ACH_ORIG, "INFO", "Member %s submitted transfer %s for %s, memo status %s".formatted(memberNo, serNo, c.total(), hold.status()));
        return receipt(memberNo, serNo).orElseThrow();
    }

    /** The success screen, tracking details and receipt. Only the member's own transfers are visible. */
    public Optional<Receipt> receipt(String memberNo, String transferSerNo) {
        return jdbc.query("SELECT t.transferSerNo, t.processStatus, t.ofacStatus, t.memoStatus, a.acctOwner, d.bankName, "
                + "a.routingNo, a.acctNo, a.acctType, s.acctName, t.origAcctNo, t.transferAmt, t.feeAmt, t.effectiveDt, "
                + "t.pmtFrequency, t.transferReqNo, t.transferReqRemaining, t.companyEntryDesc, t.createTm "
                + "FROM " + orig + ".ACHOriginatorTransfer t "
                + "JOIN " + orig + ".ACHOrigRecvAccount a ON a.recvAcctSerNo = t.recvAcctSerNo AND a.recvAcctVersion = t.recvAcctVersion "
                + "LEFT JOIN ABADirectory d ON d.routingNo = a.routingNo "
                + "LEFT JOIN SimCoreAccount s ON s.acctNo = t.origAcctNo "
                + "WHERE t.memberNo = ? AND t.transferSerNo = ? AND t.transferVersion = 1",
                (rs, i) -> {
                    BigDecimal amount = rs.getBigDecimal("transferAmt");
                    BigDecimal fee = rs.getBigDecimal("feeAmt");
                    LocalDate effective = rs.getObject("effectiveDt", LocalDate.class);
                    return new Receipt(rs.getString("transferSerNo"), rs.getString("processStatus"),
                            rs.getString("ofacStatus"), rs.getString("memoStatus"), rs.getString("acctOwner"),
                            rs.getString("bankName"), rs.getString("routingNo"), last4(rs.getString("acctNo")),
                            rs.getString("acctType"), rs.getString("acctName"), last4(rs.getString("origAcctNo")),
                            amount, fee, amount.add(fee), effective, dates.estimatedArrival(effective),
                            rs.getString("pmtFrequency"), rs.getInt("transferReqNo") + rs.getInt("transferReqRemaining"),
                            rs.getString("companyEntryDesc"),
                            rs.getObject("createTm", LocalDateTime.class));
                }, memberNo, transferSerNo).stream().findFirst();
    }

    // ---- External Bank Accounts (Ba's ach-origination-system.yaml: /v1/holders/{holderId}/external-accounts) ----

    /** Ba's status for a new account. Credit unions verify a new external account with trial deposits first. */
    static final String PENDING_MICRO_DEPOSIT = "ACTIVE_PENDING_MICRO_DEPOSIT";
    /** The same status as stored: ACHOrigRecvAccount.acctStatus is VARCHAR(20), too short for Ba's value. */
    static final String PENDING_MICRO_DEPOSIT_DB = "PENDING_MICRO_DEP";

    static final String ROUTING_DIGITS = "Enter all 9 digits of the routing number.";

    public record NewExternalAccount(String bankName, String routingNumber, String accountNumber, String accountType,
            String accountHolderName) {
    }

    public record AddedExternalAccount(String externalAccountId, String status) {
    }

    public record ExternalAccount(String externalAccountId, String bankName, String maskedAccountNumber, String accountType,
            String status) {
    }

    /** The member's active external accounts, newest first, including ones still waiting for micro-deposit verification. */
    public List<ExternalAccount> externalAccounts(String memberNo) {
        return jdbc.query("SELECT a.recvAcctSerNo, d.bankName, a.acctNo, a.acctType, a.acctStatus FROM " + orig
                + ".ACHOrigRecvAccount a LEFT JOIN ABADirectory d ON d.routingNo = a.routingNo "
                + "WHERE a.memberNo = ? AND a.recvAcctVersion = 1 AND a.acctStatus IN ('ACTIVE', ?) "
                + "ORDER BY a.lastModTm DESC, a.recvAcctSerNo DESC",
                (rs, i) -> new ExternalAccount(rs.getString(1), rs.getString(2), "••••" + last4(rs.getString(3)),
                        rs.getString(4), PENDING_MICRO_DEPOSIT_DB.equals(rs.getString(5)) ? PENDING_MICRO_DEPOSIT : rs.getString(5)),
                memberNo, PENDING_MICRO_DEPOSIT_DB);
    }

    /** Saves a new external account as pending micro-deposit verification. The checks match a transfer's recipient checks. */
    public AddedExternalAccount addExternalAccount(String memberNo, NewExternalAccount r) {
        if (r == null) {
            throw invalid("Fill in the account details.");
        }
        String owner = r.accountHolderName() == null ? "" : r.accountHolderName().trim().replaceAll("\\s+", " ");
        if (!owner.matches("[A-Za-z0-9 .,'&-]{1,22}")) {
            throw invalid("Enter the account holder's name: up to 22 letters, numbers and spaces.");
        }
        if (r.bankName() == null || r.bankName().isBlank()) {
            throw invalid("Enter the bank name.");
        }
        // For now any 9 digits pass (the user, 2026-11-07), until real routing number validation is written
        if (r.routingNumber() == null || !r.routingNumber().matches("\\d{9}")) {
            throw invalid(ROUTING_DIGITS);
        }
        if (r.accountNumber() == null || !r.accountNumber().matches("\\d{4,17}")) {
            throw invalid("Enter the account number: 4 to 17 digits.");
        }
        if (!"CHECKING".equals(r.accountType()) && !"SAVINGS".equals(r.accountType())) {
            throw invalid("Choose Checking or Savings.");
        }
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM " + orig + ".ACHOrigRecvAccount WHERE memberNo = ? "
                + "AND routingNo = ? AND acctNo = ? AND acctType = ? AND recvAcctVersion = 1 AND acctStatus IN ('ACTIVE', ?)",
                Integer.class, memberNo, r.routingNumber(), r.accountNumber(), r.accountType(), PENDING_MICRO_DEPOSIT_DB);
        if (existing != null && existing > 0) {
            throw invalid("This account is already in your external bank accounts.");
        }
        String serNo = insertWithNextSerial("REC", orig + ".ACHOrigRecvAccount", "recvAcctSerNo", s -> jdbc.update(
                "INSERT INTO " + orig + ".ACHOrigRecvAccount (recvAcctSerNo, recvAcctVersion, memberNo, acctNo, routingNo, "
                + "acctType, acctOwner, acctStatus, sourceID, lastModBy) VALUES (?, 1, ?, ?, ?, ?, ?, ?, 'ONLINE_BANKING', ?)",
                s, memberNo, r.accountNumber(), r.routingNumber(), r.accountType(), owner.toUpperCase(Locale.ROOT),
                PENDING_MICRO_DEPOSIT_DB, memberNo));
        appLog.log(ACH_ORIG, "INFO", "Member %s added external account %s ending %s, pending micro-deposit verification"
                .formatted(memberNo, serNo, last4(r.accountNumber())));
        return new AddedExternalAccount(serNo, PENDING_MICRO_DEPOSIT);
    }

    // ---- checks ----

    private Checked check(String memberNo, TransferRequest r) {
        if (r == null) {
            throw invalid("Fill in the transfer details.");
        }
        Account from = accounts(memberNo).stream().filter(a -> a.acctNo().equals(r.origAcctNo())).findFirst()
                .orElseThrow(() -> invalid("Choose one of your accounts to transfer from."));

        // The recipient: one of the member's own registered external bank accounts, active or pending micro-deposits
        Map<String, Object> to = jdbc.queryForList("SELECT acctOwner, routingNo, acctNo, acctType FROM " + orig
                + ".ACHOrigRecvAccount WHERE recvAcctSerNo = ? AND recvAcctVersion = 1 AND memberNo = ? AND acctStatus IN ('ACTIVE', ?)",
                r.externalAccountId(), memberNo, PENDING_MICRO_DEPOSIT_DB).stream().findFirst()
                .orElseThrow(() -> invalid("Choose one of your external bank accounts to transfer to."));
        String recvName = (String) to.get("acctOwner");
        String routingNo = (String) to.get("routingNo");
        String acctNo = (String) to.get("acctNo");
        String acctType = (String) to.get("acctType");
        // The ABA check digit is required (the EOD job rejects a NACHA file with a bad one); for now a bank missing from
        // ABADirectory passes with no bank name (the user, 2026-11-07), until real routing number validation is written
        if (!AchFileValidator.isValidRouting(routingNo)) {
            throw invalid(RoutingService.INVALID);
        }
        String bankName = routing.bankName(routingNo).orElse(null);
        BigDecimal amount = r.amount();
        if (amount == null || amount.signum() <= 0 || amount.stripTrailingZeros().scale() > 2
                || amount.compareTo(MAX_AMOUNT) > 0) {
            throw invalid("Enter an amount between $0.01 and $99,999,999,999.99.");
        }
        amount = amount.setScale(2);
        Optional<String> dateProblem = dates.check(r.effectiveDt());
        if (dateProblem.isPresent()) {
            throw invalid(dateProblem.get());
        }
        if (r.frequency() == null || !FREQUENCIES.contains(r.frequency())) {
            throw invalid("Choose how often to send this transfer.");
        }
        int count;
        if (r.frequency().equals("ONETIME")) {
            if (r.transferCount() != null && r.transferCount() != 1) {
                throw invalid("A one-time transfer is sent once.");
            }
            count = 1;
        } else {
            if (r.transferCount() == null || r.transferCount() < 2 || r.transferCount() > 52) {
                throw invalid("Choose how many transfers to send: 2 to 52.");
            }
            count = r.transferCount();
        }
        String memoText = r.memo() == null ? "" : r.memo().trim().toUpperCase(Locale.ROOT);
        if (!memoText.matches("[A-Z0-9 .,&-]{0,10}")) {
            throw invalid("The memo can be up to 10 letters, numbers and spaces.");
        }
        BigDecimal fee = params.get("ACH_ORIG", "EXTERNAL_XFER_FEE").map(BigDecimal::new).orElse(BigDecimal.ZERO).setScale(2);

        if (!verifier.isEligibleForAch(routingNo, acctNo, acctType)) {
            throw new TransferRejected(Kind.NOT_VERIFIED, NOT_VERIFIED);
        }
        checkLimits(memberNo, from.acctType(), amount);
        if (from.availBal().compareTo(amount.add(fee)) < 0) {
            throw new TransferRejected(Kind.FUNDS, "Your available balance of " + money(from.availBal())
                    + " isn't enough for this transfer.");
        }
        return new Checked(from, r.externalAccountId(), recvName, routingNo, acctNo, acctType, amount, fee,
                r.effectiveDt(), r.frequency(), count, memoText.isEmpty() ? null : memoText, bankName, ofac.isHit(recvName));
    }

    /**
     * USE-CASES T4, the design's ACHOrigLimitRule as written: each active rule for the source account type (or ALL)
     * adds up the member's transfers from that type within its dayNo days (today counts as day 1), plus this one.
     */
    private void checkLimits(String memberNo, String acctType, BigDecimal amount) {
        List<Map<String, Object>> rules = jdbc.queryForList("SELECT acctType, dayNo, limitAmt FROM " + orig
                + ".ACHOrigLimitRule WHERE status = 'ACTIVE' AND acctType IN (?, 'ALL') ORDER BY dayNo, limitAmt", acctType);
        for (Map<String, Object> rule : rules) {
            String ruleType = (String) rule.get("acctType");
            int dayNo = ((Number) rule.get("dayNo")).intValue();
            BigDecimal limit = (BigDecimal) rule.get("limitAmt");
            BigDecimal used = jdbc.queryForObject("SELECT COALESCE(SUM(transferAmt), 0) FROM " + orig
                    + ".ACHOriginatorTransfer WHERE memberNo = ? AND processStatus NOT IN ('CANCELLED', 'REJECTED') "
                    + "AND createTm >= CURDATE() - INTERVAL ? DAY AND (? = 'ALL' OR origAcctType = ?)",
                    BigDecimal.class, memberNo, dayNo - 1, ruleType, acctType);
            if (used.add(amount).compareTo(limit) > 0) {
                throw new TransferRejected(Kind.LIMIT, "This transfer exceeds your " + period(dayNo)
                        + " external limit of " + money(limit) + ". Please reduce the amount or contact support.");
            }
        }
    }

    // ---- saving ----

    /** A picked account still pending micro-deposits becomes ACTIVE: the transfer's account check has just verified it. */
    private void activate(String memberNo, String recvAcctSerNo) {
        jdbc.update("UPDATE " + orig + ".ACHOrigRecvAccount SET acctStatus = 'ACTIVE', lastModBy = ? WHERE recvAcctSerNo = ? "
                + "AND recvAcctVersion = 1 AND acctStatus = ?", memberNo, recvAcctSerNo, PENDING_MICRO_DEPOSIT_DB);
    }

    private String insertTransfer(String memberNo, String recvAcct, Checked c) {
        boolean recurring = c.count() > 1;
        return insertWithNextSerial("TRF", orig + ".ACHOriginatorTransfer", "transferSerNo", serNo -> jdbc.update(
                "INSERT INTO " + orig + ".ACHOriginatorTransfer (transferSerNo, transferVersion, memberNo, recvAcctSerNo, "
                + "recvAcctVersion, origAcctNo, origAcctType, origAcctGrp, recurringFlag, intAcctSpecFlag, nextPmtDt, "
                + "pmtFrequency, transferReqNo, transferReqRemaining, transferAmt, processStatus, ofacStatus, ofacTm, "
                + "memoStatus, postingStatus, postingSystem, sourceID, originatorID, lastModBy, effectiveDt, "
                + "companyEntryDesc, feeAmt) VALUES (?, 1, ?, ?, 1, ?, ?, 'RETAIL', ?, 0, ?, ?, ?, ?, ?, ?, ?, "
                + "NOW(), 'PENDING', 'PENDING', 'MAIN_CORE', 'ONLINE_BANKING', ?, ?, ?, ?, ?)",
                serNo, memberNo, recvAcct, c.from().acctNo(), c.from().acctType(), recurring ? 1 : 0, c.effectiveDt(),
                c.frequency(), 1, c.count() - 1, c.amount(), c.ofacHit() ? "PENDING" : "PENDING_BATCH",
                c.ofacHit() ? "FLAGGED" : "OFAC_PASSED", CREDIT_UNION_ORIGINATOR, memberNo, c.effectiveDt(), c.memo(), c.fee()));
    }

    /**
     * UC-ACH-EOD-01 E4: the next payment of a recurring series, as its own row linked to the first by the design's
     * pTransferSerNo. Its hold is placed when it comes due. Does nothing when the series has no payments left.
     */
    public void scheduleNextPayment(String transferSerNo) {
        Map<String, Object> row = jdbc.queryForMap("SELECT COALESCE(pTransferSerNo, transferSerNo) AS firstSerNo, pmtFrequency, "
                + "transferReqNo, transferReqRemaining FROM " + orig + ".ACHOriginatorTransfer "
                + "WHERE transferSerNo = ? AND transferVersion = 1", transferSerNo);
        if (((Number) row.get("transferReqRemaining")).intValue() <= 0) {
            return;
        }
        String firstSerNo = (String) row.get("firstSerNo");
        LocalDate first = jdbc.queryForObject("SELECT effectiveDt FROM " + orig + ".ACHOriginatorTransfer "
                + "WHERE transferSerNo = ? AND transferVersion = 1", LocalDate.class, firstSerNo);
        LocalDate next = dates.occurrence(first, (String) row.get("pmtFrequency"), ((Number) row.get("transferReqNo")).intValue());
        insertWithNextSerial("TRF", orig + ".ACHOriginatorTransfer", "transferSerNo", serNo -> jdbc.update(
                "INSERT INTO " + orig + ".ACHOriginatorTransfer (transferSerNo, transferVersion, memberNo, pTransferSerNo, "
                + "recvAcctSerNo, recvAcctVersion, origAcctNo, origAcctType, origAcctGrp, recurringFlag, intAcctSpecFlag, "
                + "nextPmtDt, pmtFrequency, transferReqNo, transferReqRemaining, transferAmt, processStatus, ofacStatus, "
                + "ofacTm, memoStatus, postingStatus, postingSystem, sourceID, originatorID, lastModBy, effectiveDt, "
                + "companyEntryDesc, feeAmt) SELECT ?, 1, memberNo, ?, recvAcctSerNo, recvAcctVersion, origAcctNo, "
                + "origAcctType, origAcctGrp, recurringFlag, intAcctSpecFlag, ?, pmtFrequency, transferReqNo + 1, "
                + "transferReqRemaining - 1, transferAmt, 'PENDING_BATCH', 'OFAC_PASSED', NOW(), 'PENDING', 'PENDING', "
                + "postingSystem, sourceID, originatorID, 'EOD_BATCH', ?, companyEntryDesc, feeAmt FROM " + orig
                + ".ACHOriginatorTransfer WHERE transferSerNo = ? AND transferVersion = 1",
                serNo, firstSerNo, next, next, transferSerNo));
    }

    /** The design's serial format PREFIX-yyyyMMdd-nnnn; retries if another request took the same number first. */
    private String insertWithNextSerial(String prefix, String table, String column, java.util.function.Consumer<String> insert) {
        String day = prefix + "-" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + "-";
        for (int attempt = 0; attempt < 5; attempt++) {
            String max = jdbc.queryForObject("SELECT MAX(" + column + ") FROM " + table + " WHERE " + column + " LIKE ?",
                    String.class, day + "%");
            int next = max == null ? 1 : Integer.parseInt(max.substring(day.length())) + 1;
            if (next > 9999) {
                throw new IllegalStateException("More than 9999 " + prefix + " serial numbers today");
            }
            String serNo = day + "%04d".formatted(next);
            try {
                insert.accept(serNo);
                return serNo;
            } catch (DuplicateKeyException e) {
                // someone else took this number; try the next one
            }
        }
        throw new IllegalStateException("Couldn't allocate a " + prefix + " serial number");
    }

    // ---- helpers ----

    private static TransferRejected invalid(String message) {
        return new TransferRejected(Kind.INVALID, message);
    }

    private static String period(int dayNo) {
        return switch (dayNo) {
            case 1 -> "daily";
            case 7 -> "weekly";
            case 30 -> "monthly";
            default -> dayNo + "-day";
        };
    }

    /** "$25,000" for whole dollars, "$1,250.50" otherwise. */
    static String money(BigDecimal amount) {
        NumberFormat f = NumberFormat.getCurrencyInstance(Locale.US);
        if (amount.stripTrailingZeros().scale() <= 0) {
            f.setMaximumFractionDigits(0);
        }
        return f.format(amount);
    }

    private static String last4(String acctNo) {
        return acctNo == null ? null : acctNo.substring(Math.max(0, acctNo.length() - 4));
    }
}
