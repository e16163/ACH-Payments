package com.achpayments.memo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.achpayments.memo.MemoPostingEngine.Request;
import com.achpayments.memo.MemoPostingEngine.Screening;
import com.achpayments.memo.MemoPostingEngine.Source;
import com.achpayments.memo.MemoPostingEngine.Status;
import com.achpayments.param.ParamCache;
import com.achpayments.param.ParamService;

/** R6 "Done when": memo posts update available balance, NSF debits are flagged, eligibility rules apply. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class MemoPostingEngineIntegrationTest {

    private static final String ACCT = "R6-TEST-ACCT";

    @Autowired
    MemoPostingEngine engine;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ParamService params;

    @Autowired
    ParamCache cache;

    @BeforeEach
    void account() {
        jdbc.update("INSERT INTO SimCoreAccount (acctNo, memberNo, acctName, acctType, acctOwner, ledgerBal, availBal) "
                + "VALUES (?, 'R6-MEMBER', 'Test', 'CHECKING', 'TEST', 100.00, 100.00)", ACCT);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM ACHMemoPost WHERE acctNo = ?", ACCT);
        jdbc.update("DELETE FROM SimCoreAccount WHERE acctNo = ?", ACCT);
        params.save("ACH_RCV", "MEMO_POST_IMMEDIATE", "Enable immediate memo posting upon transaction ingestion",
                "BOOLEAN", "TRUE", "SYS_ADMIN");
    }

    private BigDecimal available() {
        return jdbc.queryForObject("SELECT availBal FROM SimCoreAccount WHERE acctNo = ?", BigDecimal.class, ACCT);
    }

    private Status post(Source source, boolean credit, String amount, Screening screening) {
        return engine.post(new Request(source, "T1", ACCT, credit, new BigDecimal(amount), screening, "Test entry")).status();
    }

    @Test
    void debitHoldAndCreditPostChangeAvailableBalance() {
        assertEquals(Status.POSTED, post(Source.ORIG, false, "30.00", Screening.ALLOW));
        assertEquals(new BigDecimal("70.00"), available());
        assertEquals(Status.POSTED, post(Source.RECV, true, "5.50", Screening.ALLOW));
        assertEquals(new BigDecimal("75.50"), available());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM ACHMemoPost WHERE acctNo = ? AND memoStatus = 'POSTED'", Integer.class, ACCT));
    }

    @Test
    void debitAtExactlyTheBalancePostsOneCentMoreIsNsf() {
        assertEquals(Status.NSF, post(Source.ORIG, false, "100.01", Screening.ALLOW));
        assertEquals(new BigDecimal("100.00"), available());
        assertEquals(Status.POSTED, post(Source.ORIG, false, "100.00", Screening.ALLOW));
        assertEquals(new BigDecimal("0.00"), available());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ACHMemoPost WHERE acctNo = ? AND memoStatus = 'NSF'", Integer.class, ACCT));
    }

    @Test
    void concurrentDebitsNeverOverdraw() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Status>> results = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            results.add(pool.submit(() -> post(Source.ORIG, false, "10.00", Screening.ALLOW)));
        }
        int posted = 0;
        for (Future<Status> f : results) if (f.get() == Status.POSTED) posted++;
        pool.shutdown();
        assertEquals(10, posted);
        assertEquals(new BigDecimal("0.00"), available());
    }

    @Test
    void eligibilityRules() {
        assertEquals(Status.REVIEW, post(Source.ORIG, false, "1.00", Screening.REVIEW));
        jdbc.update("UPDATE SimCoreAccount SET acctStatus = 'FROZEN' WHERE acctNo = ?", ACCT);
        assertEquals(Status.INELIGIBLE, post(Source.RECV, true, "1.00", Screening.ALLOW));
        jdbc.update("UPDATE SimCoreAccount SET acctStatus = 'ACTIVE' WHERE acctNo = ?", ACCT);
        params.save("ACH_RCV", "MEMO_POST_IMMEDIATE", "Enable immediate memo posting upon transaction ingestion",
                "BOOLEAN", "FALSE", "SYS_ADMIN");
        assertEquals(Status.DEFERRED, post(Source.RECV, true, "1.00", Screening.ALLOW));
        assertEquals(new BigDecimal("100.00"), available()); // none of these changed the balance
    }
}
