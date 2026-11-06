package com.achpayments.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.UnaryOperator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.achpayments.transfer.TransferRejected.Kind;

/** X5 and X6 "Done when", on the real test databases. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class TransferServiceIntegrationTest {

    private static final String MEMBER = "X-TEST-MEMBER";
    private static final String CHECKING = "X-TEST-CHK";

    @Autowired
    TransferService service;

    @Autowired
    TransferDates dates;

    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    AccountVerifier verifier; // the test double from X3

    @Value("${app.db.origination}")
    String orig;

    @BeforeEach
    void setUp() {
        when(verifier.isEligibleForAch(any(), any(), any())).thenReturn(true);
        jdbc.update("INSERT INTO SimCoreAccount (acctNo, memberNo, acctName, acctType, acctOwner, ledgerBal, availBal) "
                + "VALUES (?, ?, 'Test Checking', 'CHECKING', 'TEST MEMBER', 100000.00, 100000.00)", CHECKING, MEMBER);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM ACHMemoPost WHERE acctNo = ?", CHECKING);
        jdbc.update("DELETE FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ?", MEMBER);
        jdbc.update("DELETE FROM " + orig + ".ACHOrigRecvAccount WHERE memberNo = ?", MEMBER);
        jdbc.update("DELETE FROM SimCoreAccount WHERE acctNo = ?", CHECKING);
    }

    private TransferRequest request(String amount) {
        return new TransferRequest(CHECKING, "Jane Smith", "021000021", "123456789", "CHECKING", new BigDecimal(amount),
                dates.earliest(), "ONETIME", null, "Rent");
    }

    private TransferRequest with(String amount, UnaryOperator<TransferRequest> change) {
        return change.apply(request(amount));
    }

    private Kind rejection(TransferRequest r) {
        return assertThrows(TransferRejected.class, () -> service.preview(MEMBER, r)).kind();
    }

    private BigDecimal available() {
        return jdbc.queryForObject("SELECT availBal FROM SimCoreAccount WHERE acctNo = ?", BigDecimal.class, CHECKING);
    }

    // ---- X5: limits (EX-02), OFAC, account verification (EX-03) ----

    @Test
    void exactlyAtTheDailyLimitPassesOneCentOverIsRejectedWithBasMessage() {
        // The design's DAILY_CHECKING_DEBIT_CAP: CHECKING, 1 day, $25,000
        assertEquals(new BigDecimal("25000.00"), service.preview(MEMBER, request("25000.00")).amount());
        TransferRejected e = assertThrows(TransferRejected.class, () -> service.preview(MEMBER, request("25000.01")));
        assertEquals(Kind.LIMIT, e.kind());
        assertEquals("This transfer exceeds your daily external limit of $25,000. Please reduce the amount or contact support.",
                e.getMessage());
    }

    @Test
    void earlierTransfersTodayCountTowardTheLimit() {
        service.submit(MEMBER, request("20000.00"));
        assertEquals(Kind.LIMIT, rejection(request("5000.01")));
        assertEquals(new BigDecimal("5000.00"), service.preview(MEMBER, request("5000.00")).amount());
    }

    @Test
    void anOfacHitIsSavedAsFlaggedUnderReviewWithoutAHold() {
        TransferRequest r = new TransferRequest(CHECKING, "Blocked-Test  person", "021000021", "123456789", "CHECKING",
                new BigDecimal("100.00"), dates.earliest(), "ONETIME", null, null);
        assertTrue(service.preview(MEMBER, r).underReview());
        TransferService.Receipt receipt = service.submit(MEMBER, r);
        assertEquals("FLAGGED", receipt.ofacStatus());
        assertEquals("REVIEW", receipt.memoStatus());
        assertEquals(new BigDecimal("100000.00"), available());
    }

    @Test
    void whenTheAccountCantBeVerifiedTheTransferIsBlocked() {
        when(verifier.isEligibleForAch(any(), any(), any())).thenReturn(false);
        assertEquals(Kind.NOT_VERIFIED, rejection(request("10.00")));
        assertThrows(TransferRejected.class, () -> service.submit(MEMBER, request("10.00")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ?",
                Integer.class, MEMBER));
    }

    // ---- X6: submit ----

    @Test
    void submitSavesThePendingTransferAndPlacesTheMemoHold() {
        TransferService.Receipt receipt = service.submit(MEMBER, request("1500.00"));

        assertTrue(receipt.transferSerNo().matches("TRF-\\d{8}-\\d{4}"), receipt.transferSerNo());
        assertEquals("PENDING_BATCH", receipt.processStatus());
        assertEquals("OFAC_PASSED", receipt.ofacStatus());
        assertEquals(dates.estimatedArrival(dates.earliest()), receipt.estimatedArrival());
        assertEquals("POSTED", receipt.memoStatus());
        assertEquals("JPMorgan Chase Bank, N.A.", receipt.bankName());
        assertEquals("6789", receipt.acctLast4());
        assertEquals(new BigDecimal("98500.00"), available());

        var row = jdbc.queryForMap("SELECT processStatus, sourceID, originatorID, companyEntryDesc, feeAmt, lastModBy, "
                + "transferAmt, effectiveDt, origAcctType FROM " + orig + ".ACHOriginatorTransfer WHERE transferSerNo = ?",
                receipt.transferSerNo());
        assertEquals("PENDING_BATCH", row.get("processStatus"));
        assertEquals("ONLINE_BANKING", row.get("sourceID"));
        assertEquals("9000000001", row.get("originatorID"));
        assertEquals("RENT", row.get("companyEntryDesc"));
        assertEquals(new BigDecimal("0.00"), row.get("feeAmt"));
        assertEquals(MEMBER, row.get("lastModBy"));
        assertEquals(dates.earliest(), ((java.sql.Date) row.get("effectiveDt")).toLocalDate());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM ACHMemoPost WHERE tranSource = 'ORIG' AND tranRef = ? "
                + "AND memoStatus = 'POSTED' AND amount = 1500.00", Integer.class, receipt.transferSerNo()));
    }

    @Test
    void serialNumbersAreUniqueAndTheRecipientIsSavedOnce() {
        String first = service.submit(MEMBER, request("10.00")).transferSerNo();
        String second = service.submit(MEMBER, request("10.00")).transferSerNo();
        assertFalse(first.equals(second));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + orig + ".ACHOrigRecvAccount WHERE memberNo = ?",
                Integer.class, MEMBER));
    }

    @Test
    void notEnoughMoneyIsRejectedAndNothingIsSaved() {
        jdbc.update("UPDATE SimCoreAccount SET availBal = 50.00 WHERE acctNo = ?", CHECKING);
        assertEquals(Kind.FUNDS, rejection(request("50.01")));
        assertThrows(TransferRejected.class, () -> service.submit(MEMBER, request("50.01")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ?",
                Integer.class, MEMBER));
    }

    @Test
    void payloadsThatSkipUiValidationAreRejected() {
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest("100049281920", r.recvName(), r.routingNo(),
                r.acctNo(), r.acctType(), r.amount(), r.effectiveDt(), r.frequency(), null, r.memo())))); // someone else's account
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.recvName(), "021000022",
                r.acctNo(), r.acctType(), r.amount(), r.effectiveDt(), r.frequency(), null, r.memo())))); // EX-01
        assertEquals(Kind.INVALID, rejection(request("10.001")));
        assertEquals(Kind.INVALID, rejection(request("0.00")));
        assertEquals(Kind.INVALID, rejection(request("-5.00")));
        assertEquals(Kind.INVALID, rejection(request("100000000000.00")));
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.recvName(), r.routingNo(),
                r.acctNo(), r.acctType(), r.amount(), LocalDate.now(), r.frequency(), null, r.memo())))); // today
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.recvName(), r.routingNo(),
                r.acctNo(), r.acctType(), r.amount(), r.effectiveDt(), r.frequency(), null, "ELEVENCHARS")))); // memo > 10
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), "", r.routingNo(),
                r.acctNo(), r.acctType(), r.amount(), r.effectiveDt(), r.frequency(), null, r.memo()))));
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.recvName(), r.routingNo(),
                "12AB", r.acctType(), r.amount(), r.effectiveDt(), r.frequency(), null, r.memo()))));
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.recvName(), r.routingNo(),
                r.acctNo(), "MONEY_MARKET", r.amount(), r.effectiveDt(), r.frequency(), null, r.memo()))));
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.recvName(), r.routingNo(),
                r.acctNo(), r.acctType(), r.amount(), r.effectiveDt(), "WEEKLY", 1, r.memo())))); // recurring needs 2+
        assertEquals(Kind.INVALID, rejection(null));
    }

    @Test
    void recurringTransfersStoreTheDesignFrequencyFields() {
        TransferService.Receipt receipt = service.submit(MEMBER, with("10.00", r -> new TransferRequest(r.origAcctNo(),
                r.recvName(), r.routingNo(), r.acctNo(), r.acctType(), r.amount(), r.effectiveDt(), "MONTHLY", 6, r.memo())));
        var row = jdbc.queryForMap("SELECT recurringFlag, pmtFrequency, transferReqNo, transferReqRemaining, nextPmtDt FROM "
                + orig + ".ACHOriginatorTransfer WHERE transferSerNo = ?", receipt.transferSerNo());
        assertEquals(true, row.get("recurringFlag"));
        assertEquals("MONTHLY", row.get("pmtFrequency"));
        assertEquals(1, row.get("transferReqNo")); // Design: the position in the series
        assertEquals(5, row.get("transferReqRemaining"));
        assertEquals(6, receipt.transferCount());
    }

    @Test
    void membersOnlySeeTheirOwnTransfers() {
        String serNo = service.submit(MEMBER, request("10.00")).transferSerNo();
        assertTrue(service.receipt(MEMBER, serNo).isPresent());
        assertTrue(service.receipt("SOMEONE-ELSE", serNo).isEmpty());
    }
}
