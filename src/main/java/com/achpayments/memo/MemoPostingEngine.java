package com.achpayments.memo;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.achpayments.param.ParamCache;

/**
 * Ba's "Memo Posting Eligibility Engine" (design doc component C, PLAN 5.4): checks the eligibility rules, then
 * holds or posts the amount against the account's available balance in the simulated core. Every attempt is
 * recorded in ACHMemoPost. Runs inside the caller's DB transaction when there is one.
 */
@Service
public class MemoPostingEngine {

    public enum Source { ORIG, RECV }

    public enum Screening { ALLOW, REVIEW }

    public enum Status { POSTED, NSF, REVIEW, DEFERRED, INELIGIBLE }

    public record Request(Source source, String tranRef, String acctNo, boolean credit, BigDecimal amount,
            Screening screening) {
    }

    public record Result(Status status, String reason) {
        public boolean posted() {
            return status == Status.POSTED;
        }
    }

    private final JdbcTemplate jdbc;
    private final ParamCache params;

    public MemoPostingEngine(JdbcTemplate jdbc, ParamCache params) {
        this.jdbc = jdbc;
        this.params = params;
    }

    @Transactional
    public Result post(Request r) {
        if (r.amount() == null || r.amount().signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        Result result = decideAndApply(r);
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
