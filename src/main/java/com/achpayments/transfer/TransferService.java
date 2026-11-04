package com.achpayments.transfer;

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

import com.achpayments.memo.MemoPostingEngine;
import com.achpayments.memo.MemoPostingEngine.Screening;
import com.achpayments.memo.MemoPostingEngine.Source;
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
    static final BigDecimal MAX_AMOUNT = new BigDecimal("99999999999.99"); // Ba's DECIMAL(13,2)
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
    private record Checked(Account from, String recvName, String routingNo, String acctNo, String acctType,
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
    private final Clock clock;
    private final String orig;

    public TransferService(JdbcTemplate jdbc, RoutingService routing, TransferDates dates, AccountVerifier verifier,
            MemoPostingEngine memo, ParamCache params, Clock clock, @Value("${app.db.origination}") String orig) {
        this.jdbc = jdbc;
        this.routing = routing;
        this.dates = dates;
        this.verifier = verifier;
        this.memo = memo;
        this.params = params;
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
        return new Preview(c.bankName(), c.amount(), c.fee(), c.total(), c.effectiveDt(), c.effectiveDt(), c.ofacHit());
    }

    /** Steps 6-7: save the transfer as PENDING and place the memo hold, in one DB transaction. */
    @Transactional
    public Receipt submit(String memberNo, TransferRequest r) {
        Checked c = check(memberNo, r);
        String recvAcct = recvAccount(memberNo, c);
        String serNo = insertTransfer(memberNo, recvAcct, c);
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
        return receipt(memberNo, serNo).orElseThrow();
    }

    /** The success screen, tracking details and receipt. Only the member's own transfers are visible. */
    public Optional<Receipt> receipt(String memberNo, String transferSerNo) {
        return jdbc.query("SELECT t.transferSerNo, t.processStatus, t.ofacStatus, t.memoStatus, a.acctOwner, d.bankName, "
                + "a.routingNo, a.acctNo, a.acctType, s.acctName, t.origAcctNo, t.transferAmt, t.feeAmt, t.effectiveDt, "
                + "t.pmtFrequency, t.transferReqNo, t.companyEntryDesc, t.createTm "
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
                            amount, fee, amount.add(fee), effective, effective, rs.getString("pmtFrequency"),
                            rs.getInt("transferReqNo"), rs.getString("companyEntryDesc"),
                            rs.getObject("createTm", LocalDateTime.class));
                }, memberNo, transferSerNo).stream().findFirst();
    }

    // ---- checks ----

    private Checked check(String memberNo, TransferRequest r) {
        if (r == null) {
            throw invalid("Fill in the transfer details.");
        }
        Account from = accounts(memberNo).stream().filter(a -> a.acctNo().equals(r.origAcctNo())).findFirst()
                .orElseThrow(() -> invalid("Choose one of your accounts to transfer from."));

        String recvName = r.recvName() == null ? "" : r.recvName().trim().replaceAll("\\s+", " ");
        if (!recvName.matches("[A-Za-z0-9 .,'&-]{1,22}")) {
            throw invalid("Enter the recipient's name: up to 22 letters, numbers and spaces.");
        }
        String bankName = routing.bankName(r.routingNo()).orElseThrow(() -> invalid(RoutingService.INVALID));
        if (r.acctNo() == null || !r.acctNo().matches("\\d{4,17}")) {
            throw invalid("Enter the recipient's account number: 4 to 17 digits.");
        }
        if (!"CHECKING".equals(r.acctType()) && !"SAVINGS".equals(r.acctType())) {
            throw invalid("Choose Checking or Savings for the recipient's account.");
        }
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

        if (!verifier.isEligibleForAch(r.routingNo(), r.acctNo(), r.acctType())) {
            throw new TransferRejected(Kind.NOT_VERIFIED, NOT_VERIFIED);
        }
        checkLimits(memberNo, from.acctType(), amount);
        if (from.availBal().compareTo(amount.add(fee)) < 0) {
            throw new TransferRejected(Kind.FUNDS, "Your available balance of " + money(from.availBal())
                    + " isn't enough for this transfer.");
        }
        return new Checked(from, recvName.toUpperCase(Locale.ROOT), r.routingNo(), r.acctNo(), r.acctType(), amount, fee,
                r.effectiveDt(), r.frequency(), count, memoText.isEmpty() ? null : memoText, bankName, ofacHit(recvName));
    }

    /**
     * USE-CASES T4, Ba's ACHOrigLimitRule as he wrote it: each active rule for the source account type (or ALL)
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

    /** USE-CASES T6: the recipient's name against the (simulated) OFAC SDN list, ignoring case and punctuation. */
    private boolean ofacHit(String name) {
        String n = normalize(name);
        return jdbc.queryForList("SELECT sdnName FROM OfacSdnName", String.class).stream().anyMatch(s -> normalize(s).equals(n));
    }

    // ---- saving ----

    /** Reuses the member's saved recipient account if it's the same one, otherwise saves a new one. */
    private String recvAccount(String memberNo, Checked c) {
        List<String> existing = jdbc.queryForList("SELECT recvAcctSerNo FROM " + orig + ".ACHOrigRecvAccount WHERE "
                + "memberNo = ? AND routingNo = ? AND acctNo = ? AND acctType = ? AND acctOwner = ? AND acctStatus = 'ACTIVE' "
                + "AND recvAcctVersion = 1", String.class, memberNo, c.routingNo(), c.acctNo(), c.acctType(), c.recvName());
        if (!existing.isEmpty()) {
            return existing.get(0);
        }
        return insertWithNextSerial("REC", orig + ".ACHOrigRecvAccount", "recvAcctSerNo", serNo -> jdbc.update(
                "INSERT INTO " + orig + ".ACHOrigRecvAccount (recvAcctSerNo, recvAcctVersion, memberNo, acctNo, routingNo, "
                + "acctType, acctOwner, sourceID, lastModBy) VALUES (?, 1, ?, ?, ?, ?, ?, 'ONLINE_BANKING', ?)",
                serNo, memberNo, c.acctNo(), c.routingNo(), c.acctType(), c.recvName(), memberNo));
    }

    private String insertTransfer(String memberNo, String recvAcct, Checked c) {
        boolean recurring = c.count() > 1;
        return insertWithNextSerial("TRF", orig + ".ACHOriginatorTransfer", "transferSerNo", serNo -> jdbc.update(
                "INSERT INTO " + orig + ".ACHOriginatorTransfer (transferSerNo, transferVersion, memberNo, recvAcctSerNo, "
                + "recvAcctVersion, origAcctNo, origAcctType, origAcctGrp, recurringFlag, intAcctSpecFlag, nextPmtDt, "
                + "pmtFrequency, transferReqNo, transferReqRemaining, transferAmt, processStatus, ofacStatus, ofacTm, "
                + "memoStatus, postingStatus, postingSystem, sourceID, originatorID, lastModBy, effectiveDt, "
                + "companyEntryDesc, feeAmt) VALUES (?, 1, ?, ?, 1, ?, ?, 'RETAIL', ?, 0, ?, ?, ?, ?, ?, 'PENDING', ?, "
                + "NOW(), 'PENDING', 'PENDING', 'MAIN_CORE', 'ONLINE_BANKING', ?, ?, ?, ?, ?)",
                serNo, memberNo, recvAcct, c.from().acctNo(), c.from().acctType(), recurring ? 1 : 0, c.effectiveDt(),
                c.frequency(), c.count(), c.count(), c.amount(), c.ofacHit() ? "FLAGGED" : "CLEARED",
                CREDIT_UNION_ORIGINATOR, memberNo, c.effectiveDt(), c.memo(), c.fee()));
    }

    /** Ba's serial format PREFIX-yyyyMMdd-nnnn; retries if another request took the same number first. */
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

    private static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
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
