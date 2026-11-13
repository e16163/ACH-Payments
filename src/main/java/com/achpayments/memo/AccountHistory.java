package com.achpayments.memo;

import java.math.BigDecimal;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Ba (2026-11-13): each account lists its credits and debits. Every balance UPDATE on SimCoreAccount is followed by
 * one call here, in the same DB transaction, which copies the balances it left into SimCoreAcctTran.
 */
@Component
public class AccountHistory {

    public static final String AVAILABLE = "AVAILABLE";
    public static final String POSTED = "POSTED";

    private final JdbcTemplate jdbc;

    public AccountHistory(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String acctNo, String desc, boolean credit, BigDecimal amount, String balanceType, String source, String ref) {
        String text = desc.length() > 100 ? desc.substring(0, 100) : desc;
        jdbc.update("INSERT INTO SimCoreAcctTran (acctNo, tranDesc, creditFlag, amount, balanceType, tranSource, tranRef, "
                + "availBalAfter, ledgerBalAfter) SELECT acctNo, ?, ?, ?, ?, ?, ?, availBal, ledgerBal FROM SimCoreAccount WHERE acctNo = ?",
                text, credit ? 1 : 0, amount, balanceType, source, ref, acctNo);
    }
}
