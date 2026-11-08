package com.achpayments.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final String JANE = "REC-20000101-0001"; // an old date, so it never takes today's serial numbers

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
        recipient(JANE, "JANE SMITH", "021000021", "123456789", "ACTIVE");
    }

    private void recipient(String serNo, String owner, String routingNo, String acctNo, String status) {
        jdbc.update("INSERT INTO " + orig + ".ACHOrigRecvAccount (recvAcctSerNo, recvAcctVersion, memberNo, acctNo, routingNo, "
                + "acctType, acctOwner, acctStatus, sourceID, lastModBy) VALUES (?, 1, ?, ?, ?, 'CHECKING', ?, ?, 'ONLINE_BANKING', ?)",
                serNo, MEMBER, acctNo, routingNo, owner, status, MEMBER);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM AppLog WHERE logMesg LIKE 'Member %'");
        jdbc.update("DELETE FROM ACHMemoPost WHERE acctNo = ?", CHECKING);
        jdbc.update("DELETE FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ?", MEMBER);
        jdbc.update("DELETE FROM " + orig + ".ACHOrigRecvAccount WHERE memberNo = ?", MEMBER);
        jdbc.update("DELETE FROM SimCoreAccount WHERE acctNo = ?", CHECKING);
    }

    private TransferRequest request(String amount) {
        return new TransferRequest(CHECKING, JANE, new BigDecimal(amount), dates.earliest(), "ONETIME", null, "Rent");
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
    void exactlyAtTheDailyLimitPassesOneCentOverIsRejectedWithTheDesignMessage() {
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
        recipient("REC-20000101-0002", "BLOCKED-TEST PERSON", "021000021", "123456789", "ACTIVE");
        TransferRequest r = new TransferRequest(CHECKING, "REC-20000101-0002", new BigDecimal("100.00"), dates.earliest(), "ONETIME", null, null);
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
    void serialNumbersAreUniqueAndNoRecipientIsAdded() {
        String first = service.submit(MEMBER, request("10.00")).transferSerNo();
        String second = service.submit(MEMBER, request("10.00")).transferSerNo();
        assertFalse(first.equals(second));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + orig + ".ACHOrigRecvAccount WHERE memberNo = ?",
                Integer.class, MEMBER));
    }

    @Test
    void aSubmittedTransferIsLoggedOnce() {
        TransferService.Receipt receipt = service.submit(MEMBER, request("20.00"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM AppLog WHERE systemID = 'ACH_ORIG' AND logLevel = 'INFO' AND logMesg LIKE ?",
                Integer.class, "Member " + MEMBER + " submitted transfer " + receipt.transferSerNo() + "%"));
    }

    @Test
    void aRejectedTransferLogsNoSubmission() {
        jdbc.update("UPDATE SimCoreAccount SET availBal = 50.00 WHERE acctNo = ?", CHECKING);
        assertThrows(TransferRejected.class, () -> service.submit(MEMBER, request("50.01")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM AppLog WHERE logMesg LIKE 'Member %'", Integer.class));
    }

    @Test
    void notEnoughMoneyIsRejectedAndNothingIsSaved() {
        jdbc.update("UPDATE SimCoreAccount SET availBal = 50.00 WHERE acctNo = ?", CHECKING);
        assertEquals(Kind.FUNDS, rejection(request("50.01")));
        assertThrows(TransferRejected.class, () -> service.submit(MEMBER, request("50.01")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + orig + ".ACHOriginatorTransfer WHERE memberNo = ?",
                Integer.class, MEMBER));
    }

    private TransferRequest to(String externalAccountId) {
        return with("10.00", r -> new TransferRequest(r.origAcctNo(), externalAccountId, r.amount(), r.effectiveDt(), r.frequency(), null, r.memo()));
    }

    @Test
    void payloadsThatSkipUiValidationAreRejected() {
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest("100049281920", r.externalAccountId(), r.amount(),
                r.effectiveDt(), r.frequency(), null, r.memo())))); // someone else's account
        assertEquals(Kind.INVALID, rejection(request("10.001")));
        assertEquals(Kind.INVALID, rejection(request("0.00")));
        assertEquals(Kind.INVALID, rejection(request("-5.00")));
        assertEquals(Kind.INVALID, rejection(request("100000000000.00")));
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.externalAccountId(), r.amount(),
                LocalDate.now(), r.frequency(), null, r.memo())))); // today
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.externalAccountId(), r.amount(),
                r.effectiveDt(), r.frequency(), null, "ELEVENCHARS")))); // memo > 10
        assertEquals(Kind.INVALID, rejection(with("10.00", r -> new TransferRequest(r.origAcctNo(), r.externalAccountId(), r.amount(),
                r.effectiveDt(), "WEEKLY", 1, r.memo())))); // recurring needs 2+
        assertEquals(Kind.INVALID, rejection(null));
    }

    @Test
    void theRecipientMustBeOneOfTheMembersOwnExternalAccounts() {
        String message = "Choose one of your external bank accounts to transfer to.";
        assertEquals(message, assertThrows(TransferRejected.class, () -> service.preview(MEMBER, to(null))).getMessage());
        assertEquals(message, assertThrows(TransferRejected.class, () -> service.preview(MEMBER, to("REC-NOPE"))).getMessage());
        jdbc.update("UPDATE " + orig + ".ACHOrigRecvAccount SET memberNo = 'X-TEST-OTHER' WHERE recvAcctSerNo = ?", JANE);
        assertEquals(message, assertThrows(TransferRejected.class, () -> service.preview(MEMBER, request("10.00"))).getMessage()); // someone else's
        jdbc.update("UPDATE " + orig + ".ACHOrigRecvAccount SET memberNo = ? WHERE recvAcctSerNo = ?", MEMBER, JANE);
        jdbc.update("UPDATE " + orig + ".ACHOrigRecvAccount SET acctStatus = 'INACTIVE' WHERE recvAcctSerNo = ?", JANE);
        assertEquals(message, assertThrows(TransferRejected.class, () -> service.preview(MEMBER, request("10.00"))).getMessage());
    }

    @Test
    void aValidRoutingNumberMissingFromTheDirectoryPassesWithNoBankNameAndABadCheckDigitDoesNot() {
        recipient("REC-20000101-0003", "NAVY MEMBER", "256074974", "55501234", "ACTIVE"); // valid check digit, not in ABADirectory
        recipient("REC-20000101-0004", "TYPO", "123456789", "55501234", "ACTIVE"); // fails the check digit
        assertNull(service.preview(MEMBER, to("REC-20000101-0003")).bankName());
        assertEquals(RoutingService.INVALID, assertThrows(TransferRejected.class, () -> service.preview(MEMBER, to("REC-20000101-0004"))).getMessage());
    }

    @Test
    void recurringTransfersStoreTheDesignFrequencyFields() {
        TransferService.Receipt receipt = service.submit(MEMBER, with("10.00", r -> new TransferRequest(r.origAcctNo(),
                r.externalAccountId(), r.amount(), r.effectiveDt(), "MONTHLY", 6, r.memo())));
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

    // ---- External Bank Accounts (Ba's ach-origination-system.yaml) ----

    private TransferService.NewExternalAccount account(String acctNo) {
        return new TransferService.NewExternalAccount("JPMorgan Chase", "021000021", acctNo, "SAVINGS", "Jane  Smith");
    }

    private String invalidAdd(TransferService.NewExternalAccount r) {
        TransferRejected e = assertThrows(TransferRejected.class, () -> service.addExternalAccount(MEMBER, r));
        assertEquals(Kind.INVALID, e.kind());
        return e.getMessage();
    }

    @Test
    void aNewExternalAccountIsPendingMicroDepositsAndShowsFirstInTheList() {
        TransferService.AddedExternalAccount added = service.addExternalAccount(MEMBER, account("55501234"));
        assertEquals("ACTIVE_PENDING_MICRO_DEPOSIT", added.status());

        String bank = jdbc.queryForObject("SELECT bankName FROM ABADirectory WHERE routingNo = '021000021'", String.class);
        var list = service.externalAccounts(MEMBER);
        assertEquals(2, list.size());
        assertEquals(new TransferService.ExternalAccount(added.externalAccountId(), bank, "••••1234", "SAVINGS",
                "ACTIVE_PENDING_MICRO_DEPOSIT"), list.get(0));
        assertEquals("ACTIVE", list.get(1).status());
        assertEquals("••••6789", list.get(1).maskedAccountNumber());
        assertTrue(service.externalAccounts("SOMEONE-ELSE").stream().noneMatch(a -> a.externalAccountId().equals(added.externalAccountId())));

        var row = jdbc.queryForMap("SELECT acctStatus, acctOwner, memberNo, lastModBy, sourceID FROM " + orig
                + ".ACHOrigRecvAccount WHERE recvAcctSerNo = ?", added.externalAccountId());
        assertEquals(java.util.Map.of("acctStatus", "PENDING_MICRO_DEP", "acctOwner", "JANE SMITH", "memberNo", MEMBER,
                "lastModBy", MEMBER, "sourceID", "ONLINE_BANKING"), row);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM AppLog WHERE systemID = 'ACH_ORIG' AND logMesg = ?", Integer.class,
                "Member " + MEMBER + " added external account " + added.externalAccountId() + " ending 1234, pending micro-deposit verification"));
    }

    @Test
    void aTransferToAPendingAccountUsesItAndMakesItActive() {
        String serNo = service.addExternalAccount(MEMBER, account("55501234")).externalAccountId();
        TransferService.Receipt receipt = service.submit(MEMBER, to(serNo));
        assertEquals("JANE SMITH", receipt.recvName());
        assertEquals("1234", receipt.acctLast4());
        var list = service.externalAccounts(MEMBER);
        assertEquals(2, list.size());
        assertEquals(serNo, list.get(0).externalAccountId());
        assertEquals("ACTIVE", list.get(0).status());
    }

    @Test
    void anExternalAccountIsCheckedLikeATransferRecipientAndOnlyAddedOnce() {
        service.addExternalAccount(MEMBER, account("55501234"));
        assertEquals("This account is already in your external bank accounts.", invalidAdd(account("55501234")));
        assertEquals("Enter all 9 digits of the routing number.", invalidAdd(new TransferService.NewExternalAccount("Bank", "02100002", "55501234", "SAVINGS", "Jane")));
        assertEquals("Enter all 9 digits of the routing number.", invalidAdd(new TransferService.NewExternalAccount("Bank", "0210000211", "55501234", "SAVINGS", "Jane")));
        assertEquals("Enter all 9 digits of the routing number.", invalidAdd(new TransferService.NewExternalAccount("Bank", null, "55501234", "SAVINGS", "Jane")));
        assertEquals("Enter the account number: 4 to 17 digits.", invalidAdd(account("123")));
        assertEquals("Choose Checking or Savings.", invalidAdd(new TransferService.NewExternalAccount("Bank", "021000021", "55509999", "MONEY_MARKET", "Jane")));
        assertEquals("Enter the bank name.", invalidAdd(new TransferService.NewExternalAccount(" ", "021000021", "55509999", "SAVINGS", "Jane")));
        assertEquals("Enter the account holder's name: up to 22 letters, numbers and spaces.",
                invalidAdd(new TransferService.NewExternalAccount("Bank", "021000021", "55509999", "SAVINGS", "<script>")));
        assertEquals("Fill in the account details.", invalidAdd(null));
        assertEquals(2, service.externalAccounts(MEMBER).size()); // Jane's from setUp and the one added
    }

    @Test
    void forNowAnyNineDigitRoutingNumberCanBeAdded() {
        // 021000022 fails the check digit and 256074974 isn't in ABADirectory: both pass until real validation is written
        service.addExternalAccount(MEMBER, new TransferService.NewExternalAccount("Some Bank", "021000022", "55501234", "SAVINGS", "Jane"));
        service.addExternalAccount(MEMBER, new TransferService.NewExternalAccount("Navy Federal", "256074974", "55501234", "SAVINGS", "Jane"));
        assertNull(service.externalAccounts(MEMBER).get(0).bankName());
    }
}
