package com.achpayments.origination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.achpayments.origination.OriginationProcessingController.ResolveRequest;
import com.achpayments.origination.OriginationProcessingController.StatusRequest;
import com.achpayments.transfer.TransferDates;
import com.achpayments.transfer.TransferRequest;
import com.achpayments.transfer.TransferService;

/** Ba's blueprint, ACH Origination Processing steps 1-7, on the real test databases. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class OriginationWindowIntegrationTest {

    private static final String MEMBER = "X-WIN-MEMBER";
    private static final String CHECKING = "X-WIN-CHK";
    private static final String LISTED = "WINDOW LISTED PERSON";
    private static final Principal OFFICER = () -> "achcomp";

    @Autowired OriginationWindow window;
    @Autowired OriginationProcessingController api;
    @Autowired TransferService transfers;
    @Autowired TransferDates dates;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.achpayments.outbound.EodController eod;

    @Value("${app.db.origination}")
    String orig;

    private LocalDate date;

    @BeforeEach
    void setUp() {
        date = dates.earliest();
        jdbc.update("INSERT INTO SimCoreAccount (acctNo, memberNo, acctName, acctType, acctOwner, ledgerBal, availBal) "
                + "VALUES (?, ?, 'Test Checking', 'CHECKING', 'TEST MEMBER', 20000.00, 20000.00)", CHECKING, MEMBER);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM OfacSdnName WHERE sdnName = ?", LISTED);
        jdbc.update("DELETE FROM " + orig + ".ACHOrigLimitRule WHERE ruleName = 'X-WIN-RULE'");
        jdbc.update("DELETE FROM " + orig + ".ACHOrigOfacReview WHERE transferSerNo IN (SELECT transferSerNo FROM " + orig
                + ".ACHOriginatorTransfer WHERE memberNo = ?)", MEMBER);
        jdbc.update("DELETE FROM ACHMemoPost WHERE acctNo = ?", CHECKING);
        jdbc.update("DELETE FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ? AND pTransferSerNo IS NOT NULL", MEMBER);
        jdbc.update("DELETE FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ?", MEMBER);
        jdbc.update("DELETE FROM " + orig + ".ACHOrigRecvAccount WHERE memberNo = ?", MEMBER);
        jdbc.update("DELETE FROM SimCoreAccount WHERE acctNo = ?", CHECKING);
    }

    private String submit(String name) {
        String serNo = "REC-20000103-0001";
        jdbc.update("INSERT INTO " + orig + ".ACHOrigRecvAccount (recvAcctSerNo, recvAcctVersion, memberNo, acctNo, routingNo, acctType, "
                + "acctOwner, sourceID, lastModBy) VALUES (?, 1, ?, '123456789', '021000021', 'CHECKING', ?, 'ONLINE_BANKING', ?)",
                serNo, MEMBER, name, MEMBER);
        return transfers.submit(MEMBER, new TransferRequest(CHECKING, serNo, new BigDecimal("100.00"), date, "ONETIME", null, "RENT"))
                .transferSerNo();
    }

    private Map<String, Object> transfer(String serNo) {
        return jdbc.queryForMap("SELECT processStatus, ofacStatus, memoStatus FROM " + orig + ".ACHOriginatorTransfer WHERE transferSerNo = ?", serNo);
    }

    private BigDecimal available() {
        return jdbc.queryForObject("SELECT availBal FROM SimCoreAccount WHERE acctNo = ?", BigDecimal.class, CHECKING);
    }

    @Test
    void aSubmittedTransferIsMadeReadyToPostOnItsDateOnly() {
        String serNo = submit("JANE SMITH");
        assertEquals("PENDING_BATCH", transfer(serNo).get("processStatus"));
        BigDecimal held = available(); // the hold placed at Confirm (Q3)

        window.run(date.minusDays(1), "09:00");
        assertEquals("PENDING_BATCH", transfer(serNo).get("processStatus"));

        OriginationWindow.Run run = window.run(date, "09:00");
        assertEquals(Map.of("processStatus", "READY_TO_POST", "ofacStatus", "OFAC_PASSED", "memoStatus", "POSTED"), transfer(serNo));
        assertEquals(held, available()); // no second hold
        assertTrue(run.extracted() >= 1 && run.ready() >= 1);
        assertTrue(eod.status().pending().stream().anyMatch(i -> i.transferSerNo().equals(serNo))); // still on the EOD screen
    }

    @Test
    void aFrozenAccountRejectsTheTransferAndGivesTheHoldBack() {
        String serNo = submit("JANE SMITH");
        jdbc.update("UPDATE SimCoreAccount SET acctStatus = 'FROZEN' WHERE acctNo = ?", CHECKING);

        window.run(date, "09:00");

        assertEquals("REJECTED", transfer(serNo).get("processStatus"));
        assertEquals("RELEASED", transfer(serNo).get("memoStatus"));
        assertEquals(new BigDecimal("20000.00"), available());
        assertTrue(eod.status().leftOut().stream().anyMatch(i -> i.transferSerNo().equals(serNo)
                && i.reason().equals("Failed the account, limit or funds check")));
    }

    @Test
    void aLimitLoweredAfterSubmitRejectsTheTransfer() {
        String serNo = submit("JANE SMITH");
        jdbc.update("INSERT INTO " + orig + ".ACHOrigLimitRule (ruleName, acctType, dayNo, limitAmt, status, lastModBy) "
                + "VALUES ('X-WIN-RULE', 'ALL', 7, 50.00, 'ACTIVE', 'TEST')");

        window.run(date, "09:00");

        assertEquals("REJECTED", transfer(serNo).get("processStatus"));
        assertEquals(new BigDecimal("20000.00"), available());
    }

    @Test
    void anOfacHitWaitsForComplianceAndAClearedOneGoesOnAtTheNextWindow() {
        String serNo = submit(LISTED);
        jdbc.update("INSERT INTO OfacSdnName (sdnName, program) VALUES (?, 'DEMO')", LISTED); // listed after the member confirmed
        BigDecimal held = available();

        window.run(date, "09:00");
        assertEquals(Map.of("processStatus", "PENDING", "ofacStatus", "FLAGGED", "memoStatus", "POSTED"), transfer(serNo));
        assertEquals(held, available()); // the hold stays while compliance reviews
        assertTrue(api.flagged().stream().anyMatch(f -> f.transferId().equals(serNo)));

        var r = api.resolve(new ResolveRequest(serNo, "CLEARED_FALSE_POSITIVE", null, "Different date of birth."), OFFICER);
        assertEquals("FLAGGED", r.previousStatus());
        assertEquals("CLEARED", r.currentStatus());
        assertEquals("achcomp", jdbc.queryForObject("SELECT complianceOfficerID FROM " + orig + ".ACHOrigOfacReview WHERE transferSerNo = ?",
                String.class, serNo));

        window.run(date, "12:00");
        assertEquals(Map.of("processStatus", "READY_TO_POST", "ofacStatus", "CLEARED", "memoStatus", "POSTED"), transfer(serNo));
        assertThrows(IllegalArgumentException.class,
                () -> api.resolve(new ResolveRequest(serNo, "CLEARED_FALSE_POSITIVE", null, null), OFFICER)); // not flagged now
    }

    @Test
    void aBlockedTransferIsRejectedAndItsHoldGivenBack() {
        String serNo = submit(LISTED);
        jdbc.update("INSERT INTO OfacSdnName (sdnName, program) VALUES (?, 'DEMO')", LISTED);
        window.run(date, "09:00");

        api.resolve(new ResolveRequest(serNo, "BLOCKED_CONFIRMED_MATCH", null, "Confirmed match."), OFFICER);

        assertEquals(Map.of("processStatus", "REJECTED", "ofacStatus", "BLOCKED", "memoStatus", "RELEASED"), transfer(serNo));
        assertTrue(eod.status().leftOut().stream().anyMatch(i -> i.transferSerNo().equals(serNo) && i.reason().equals("OFAC blocked")));
        assertEquals(new BigDecimal("20000.00"), available());
    }

    @Test
    void theStatusEndpointOnlyMovesATransferThatPassedTheChecks() {
        String serNo = submit("JANE SMITH");
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET ofacStatus = 'FLAGGED' WHERE transferSerNo = ?", serNo);
        assertEquals(0, api.status(new StatusRequest(List.of(serNo), "READY_TO_POST")).updatedCount());

        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET ofacStatus = 'OFAC_PASSED' WHERE transferSerNo = ?", serNo);
        var response = api.status(new StatusRequest(List.of(serNo), "READY_TO_POST"));
        assertEquals(1, response.updatedCount());
        assertTrue(response.postingBatchTag().startsWith("EOD-POST-"));
        assertEquals("READY_TO_POST", transfer(serNo).get("processStatus"));
    }
}
