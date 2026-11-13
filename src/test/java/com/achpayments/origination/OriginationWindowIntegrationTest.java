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
    private static final Principal OPS = () -> "achorig";

    @Autowired OriginationWindow window;
    @Autowired OriginationProcessingController api;
    @Autowired TransferService transfers;
    @Autowired TransferDates dates;
    @Autowired JdbcTemplate jdbc;
    @Autowired java.time.Clock clock;
    @Autowired com.achpayments.common.BankingCalendar calendar;
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
        // Files sent from a window (processID WIN-...): entries, batches, the audit trail (refuses DELETE; TRUNCATE skips the trigger)
        jdbc.update("DELETE FROM AppLog WHERE logMesg LIKE '%FILE\\_%'");
        jdbc.update("DELETE t FROM " + orig + ".ACHOrigTransaction t JOIN " + orig + ".ACHOrigFile f ON f.fileID = t.fileID WHERE f.processID LIKE 'WIN-%'");
        jdbc.execute("TRUNCATE TABLE " + orig + ".ACHOrigFedAction");
        jdbc.update("INSERT INTO " + orig + ".ACHOrigFedAction (fileID, processID, actionID, actionTm, lastModBy) "
                + "VALUES ('FILE_20261102_01', 'PROC_FED_01', 401, '2026-11-02 08:30:00', 'FED_BATCH_SYS')");
        jdbc.update("DELETE b FROM " + orig + ".ACHOrigBatch b JOIN " + orig + ".ACHOrigFile f ON f.fileID = b.fileID WHERE f.processID LIKE 'WIN-%'");
        jdbc.update("DELETE FROM " + orig + ".ACHOrigFile WHERE processID LIKE 'WIN-%'");
        jdbc.update("DELETE FROM " + orig + ".ACHOrigWindowRun");
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
        return submit(name, "REC-20000103-0001");
    }

    /** Submits and holds the money as Confirm does for a same-day transfer, whatever time of day the test runs. */
    private String submit(String name, String serNo) {
        String transferSerNo = submitOnly(name, serNo, date);
        if ("PENDING".equals(transfer(transferSerNo).get("memoStatus"))) { // after 3 PM, date is the next banking day
            window.placeHold(window.load(transferSerNo, false).orElseThrow());
        }
        return transferSerNo;
    }

    private String submitOnly(String name, String serNo, LocalDate effectiveDt) {
        jdbc.update("INSERT INTO " + orig + ".ACHOrigRecvAccount (recvAcctSerNo, recvAcctVersion, memberNo, acctNo, routingNo, acctType, "
                + "acctOwner, sourceID, lastModBy) VALUES (?, 1, ?, '123456789', '021000021', 'CHECKING', ?, 'ONLINE_BANKING', ?)",
                serNo, MEMBER, name, MEMBER);
        return transfers.submit(MEMBER, new TransferRequest(CHECKING, serNo, new BigDecimal("100.00"), effectiveDt, "ONETIME", null, "RENT"))
                .transferSerNo();
    }

    /** Ba (2026-11-13): a later-dated transfer keeps the available balance until the window on its effective date holds it. */
    @Test
    void aLaterDatedTransferIsHeldByTheWindowOnItsEffectiveDate() {
        LocalDate later = calendar.nextBankingDay(date);
        String serNo = submitOnly("JANE SMITH", "REC-20000103-0001", later);
        assertEquals("PENDING", transfer(serNo).get("memoStatus"));
        assertEquals(new BigDecimal("20000.00"), available());

        window.run(later, "09:00");
        assertEquals(Map.of("processStatus", "READY_TO_POST", "ofacStatus", "OFAC_PASSED", "memoStatus", "POSTED"), transfer(serNo));
        assertEquals(new BigDecimal("19900.00"), available());
        assertEquals("ACH transfer to JANE SMITH", jdbc.queryForObject("SELECT tranDesc FROM SimCoreAcctTran WHERE tranRef = ?",
                String.class, serNo));
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

    /** Ba (2026-11-12): pick a schedule, see the transfers it made ready today, send them to the Fed as one file. */
    @Test
    void aScheduleListsAndSendsOnlyTheTransfersItMadeReady() {
        LocalDate today = LocalDate.now(clock);
        org.junit.jupiter.api.Assumptions.assumeTrue(calendar.isBankingDay(today), "the Fed takes files on banking days only");
        Map<String, Integer> ids = api.windows().schedules().stream()
                .collect(java.util.stream.Collectors.toMap(OriginationProcessingController.Schedule::windowTime,
                        OriginationProcessingController.Schedule::scheduleId));
        assertEquals(List.of("06:00", "09:00", "12:00", "15:00"), ids.keySet().stream().sorted().toList()); // from ACHOrigSchedule

        String nine = submit("JANE SMITH", "REC-20000103-0001");
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET effectiveDt = ? WHERE transferSerNo = ?", today, nine); // due today
        api.triggerWindow(new OriginationProcessingController.WindowRequest("09:00", null, null), OPS);
        String noon = submit("JOHN DOE", "REC-20000103-0002");
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET effectiveDt = ? WHERE transferSerNo = ?", today, noon);
        api.triggerWindow(new OriginationProcessingController.WindowRequest("12:00", null, null), OPS);

        assertEquals(List.of(nine), api.ready(ids.get("09:00")).stream().map(OriginationProcessingController.Ready::transferId).toList());
        assertEquals(List.of(noon), api.ready(ids.get("12:00")).stream().map(OriginationProcessingController.Ready::transferId).toList());
        var nineRun = api.windows().schedules().stream().filter(x -> x.windowTime().equals("09:00")).findFirst().orElseThrow().lastRun();
        assertEquals(List.of(1, "achorig"), List.of(nineRun.readyToPost(), nineRun.runBy()));

        var sent = api.sendToFed(new OriginationProcessingController.SendRequest(ids.get("09:00")), OPS);
        assertEquals("SENT", sent.fedStatus());
        var files = api.files(ids.get("09:00"));
        assertEquals(1, files.size());
        assertEquals(List.of(sent.fileID(), ids.get("09:00"), 1), List.of(files.get(0).fileId(), files.get(0).scheduleId(), files.get(0).totalCreditCount()));
        assertEquals(1, files.get(0).batches().size());
        assertEquals(1, files.get(0).batches().get(0).entryCount());
        assertEquals("PROCESSED", transfer(nine).get("processStatus"));
        assertEquals(List.of(), api.ready(ids.get("09:00")));
        assertEquals(List.of(noon), api.ready(ids.get("12:00")).stream().map(OriginationProcessingController.Ready::transferId).toList());
        assertEquals(List.of(), api.files(ids.get("12:00")));
    }

    @Test
    void transactionsSearchByEffectiveDateAndShowTheWholeTransfer() {
        LocalDate day = date;
        String first = submit("JANE SMITH", "REC-20000103-0001");
        String later = submit("JOHN DOE", "REC-20000103-0002");
        jdbc.update("UPDATE " + orig + ".ACHOriginatorTransfer SET effectiveDt = ? WHERE transferSerNo = ?", day.plusDays(7), later);

        var oneDay = api.transactions(day, day, null, 1, 10).items().stream().map(OriginationProcessingController.Txn::transferId).toList();
        assertTrue(oneDay.contains(first) && !oneDay.contains(later), oneDay.toString());
        var week = api.transactions(day, day.plusDays(7), null, 1, 100).items().stream().map(OriginationProcessingController.Txn::transferId).toList();
        assertTrue(week.containsAll(List.of(first, later)), week.toString()); // both ends included
        assertEquals(0, api.transactions(day, day.plusDays(7), "NO_SUCH_STATUS", 1, 10).total());
        assertThrows(IllegalArgumentException.class, () -> api.transactions(day.plusDays(1), day, null, 1, 10));

        Map<String, Object> detail = api.transaction(first);
        assertEquals("JANE SMITH", detail.get("recvName"));
        assertEquals(new BigDecimal("100.00"), detail.get("transferAmt"));
        assertEquals(day, detail.get("effectiveDt")); // a LocalDate, so the JSON says 2026-11-13
        assertTrue(detail.containsKey("scheduleID") && detail.containsKey("feeAmt"), detail.keySet().toString()); // every column
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
        assertEquals(new BigDecimal("20000.00"), jdbc.queryForObject("SELECT availBalAfter FROM SimCoreAcctTran WHERE tranRef = ? "
                + "AND creditFlag = 1 AND tranDesc = 'Hold released, ACH transfer to " + LISTED + " rejected'", BigDecimal.class, serNo));
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
