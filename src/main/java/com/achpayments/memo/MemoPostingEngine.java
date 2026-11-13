package com.achpayments.memo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.achpayments.param.ParamCache;

/**
 * The design's "Memo Posting Eligibility Engine" (design doc component C, PLAN 5.4): checks the eligibility rules, then
 * holds or posts the amount against the account's available balance in the simulated core. Every attempt is
 * recorded in ACHMemoPost. Runs inside the caller's DB transaction when there is one.
 */
@Service
public class MemoPostingEngine {

    public enum Source { ORIG, RECV }

    public enum Screening { ALLOW, REVIEW }

    public enum Status { POSTED, NSF, REVIEW, DEFERRED, INELIGIBLE }

    /** desc is the line on the member's account history; null shows the source and reference. */
    public record Request(Source source, String tranRef, String acctNo, boolean credit, BigDecimal amount,
            Screening screening, String desc) {
        public Request(Source source, String tranRef, String acctNo, boolean credit, BigDecimal amount, Screening screening) {
            this(source, tranRef, acctNo, credit, amount, screening, null);
        }
    }

    public record Result(Status status, String reason) {
        public boolean posted() {
            return status == Status.POSTED;
        }
    }

    private final JdbcTemplate jdbc;
    private final ParamCache params;
    private final AccountHistory history;

    public MemoPostingEngine(JdbcTemplate jdbc, ParamCache params, AccountHistory history) {
        this.jdbc = jdbc;
        this.params = params;
        this.history = history;
    }

    @Transactional
    public Result post(Request r) {
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        Result result = decideAndApply(r);
        if (result.posted()) {
            String desc = r.desc() != null ? r.desc()
                    : (r.source() == Source.ORIG ? "ACH transfer " : r.credit() ? "ACH credit " : "ACH debit ") + r.tranRef();
            history.record(r.acctNo(), desc, r.credit(), r.amount(), AccountHistory.AVAILABLE,
                    r.source() == Source.ORIG ? "ACH_ORIG" : "ACH_RCV", r.tranRef());
        }
        jdbc.update("INSERT INTO ACHMemoPost (tranSource, tranRef, acctNo, creditFlag, amount, postType, memoStatus, reason) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)", r.source().name(), r.tranRef(), r.acctNo(), r.credit() ? 1 : 0,
                r.amount(), r.source() == Source.ORIG ? "HOLD" : "POST", result.status().name(), result.reason());
        return result;
    }

    private Result decideAndApply(Request r) {
        List<String> status = jdbc.queryForList("SELECT acctStatus FROM SimCoreAccount WHERE acctNo = ?", String.class, r.acctNo());
        if (status.isEmpty()) {
            throw new IllegalArgumentException("Unknown account " + r.acctNo());
        }
        if (!status.get(0).equals("ACTIVE")) {
            return new Result(Status.INELIGIBLE, "Account is " + status.get(0));
        }
        if (r.screening() != Screening.ALLOW) {
            return new Result(Status.REVIEW, "Waiting for screening review");
        }
        if (r.credit()) {
            if (r.source() == Source.RECV && !params.get("ACH_RCV", "MEMO_POST_IMMEDIATE").map("TRUE"::equalsIgnoreCase).orElse(true)) {
                return new Result(Status.DEFERRED, "ACH_RCV/MEMO_POST_IMMEDIATE is FALSE: posts at end of day");
            }
            jdbc.update("UPDATE SimCoreAccount SET availBal = availBal + ? WHERE acctNo = ?", r.amount(), r.acctNo());
            return new Result(Status.POSTED, null);
        }
        // Debit: one atomic UPDATE, so two debits at once can't both spend the same money.
        int updated = jdbc.update("UPDATE SimCoreAccount SET availBal = availBal - ? WHERE acctNo = ? AND availBal >= ?",
                r.amount(), r.acctNo(), r.amount());
        return updated == 1 ? new Result(Status.POSTED, null) : new Result(Status.NSF, "Insufficient available balance");
    }
}
