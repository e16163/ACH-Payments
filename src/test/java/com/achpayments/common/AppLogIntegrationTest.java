package com.achpayments.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** UC-SYS-LOG-01 against real MySQL. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class AppLogIntegrationTest {

    private static final String TAG = "LOGTEST ";

    @Autowired
    AppLogRepository appLog;

    @Autowired
    AppLogController controller;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    TransactionTemplate transaction;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM AppLog WHERE logMesg LIKE 'LOGTEST%'");
    }

    private AppLogController.Page search(String level, String term) {
        return controller.logs(1, 20, level, null, term, null, null);
    }

    private long id(String message) {
        return jdbc.queryForObject("SELECT logAppId FROM AppLog WHERE logMesg = ?", Long.class, message);
    }

    @Test
    void savesTheSystemIdAndAMillisecondTimestamp() {
        Integer fractional = null;
        for (int i = 0; i < 5 && (fractional == null || fractional == 0); i++) { // a row can land exactly on a whole second
            jdbc.update("DELETE FROM AppLog WHERE logMesg = ?", TAG + "a");
            appLog.log("ACH_RCV", "INFO", TAG + "a");
            fractional = jdbc.queryForObject("SELECT MICROSECOND(logTm) FROM AppLog WHERE logMesg = ?", Integer.class, TAG + "a");
        }
        assertTrue(fractional > 0);
        assertEquals("ACH_RCV", controller.log(id(TAG + "a")).systemID());
    }

    @Test
    void logTmKeepsTheDesignsDefinitionApartFromMilliseconds() {
        assertEquals(java.util.Map.of("t", "datetime(3)", "n", "NO", "d", "CURRENT_TIMESTAMP(3)"), jdbc.queryForMap(
                "SELECT COLUMN_TYPE AS t, IS_NULLABLE AS n, COLUMN_DEFAULT AS d FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'AppLog' AND COLUMN_NAME = 'logTm'"));
    }

    @Test
    void theGridIsNewestFirstAndTwentyToAPage() {
        for (int i = 1; i <= 21; i++) {
            appLog.log("ACH_ORIG", "INFO", TAG + "row " + i);
        }
        AppLogController.Page first = search("ALL", TAG);
        assertEquals(21, first.total());
        assertEquals(20, first.items().size());
        assertEquals(TAG + "row 21", first.items().getFirst().logMesg());
        assertEquals(List.of(TAG + "row 1"), controller.logs(2, 20, "ALL", null, TAG, null, null).items().stream()
                .map(AppLogController.Row::logMesg).toList());
    }

    @Test
    void filtersByLevelAndKeyword() {
        appLog.log("ACH_ORIG", "ERROR", TAG + "alpha");
        appLog.log("ACH_ORIG", "INFO", TAG + "alpha");
        appLog.log("ACH_ORIG", "ERROR", TAG + "beta");
        assertEquals(1, search("ERROR", TAG + "ALPHA").total()); // the keyword ignores case
        assertEquals(2, search("ALL", TAG + "alpha").total());
    }

    @Test
    void filtersByApp() {
        appLog.log("ACH_ORIG", "INFO", TAG + "orig");
        appLog.log("ACH_RCV", "INFO", TAG + "rcv");
        appLog.log("ACH_SYS", "WARN", TAG + "sys");
        assertEquals(List.of(TAG + "rcv"), controller.logs(1, 20, "ALL", "ACH_RCV", TAG, null, null).items().stream()
                .map(AppLogController.Row::logMesg).toList());
        assertEquals(1, controller.logs(1, 20, "ALL", "ACH_SYS", TAG, null, null).total());
        assertEquals(0, controller.logs(1, 20, "ALL", "ACH_RCV_NOC", TAG, null, null).total());
        assertEquals(3, search("ALL", TAG).total());
        assertThrows(IllegalArgumentException.class, () -> controller.logs(1, 20, "ALL", "NOPE", null, null, null));
    }

    @Test
    void aKeywordIsPlainTextNotAPattern() {
        appLog.log("ACH_ORIG", "INFO", TAG + "abc");
        appLog.log("ACH_ORIG", "INFO", TAG + "100% done_now");
        assertEquals(0, search("ALL", TAG + "a%").total());
        assertEquals(0, search("ALL", TAG + "a_c").total());
        assertEquals(1, search("ALL", "100% done_").total());
    }

    @Test
    void theDatesAreWholeDaysAndBothIncluded() {
        appLog.log("ACH_ORIG", "INFO", TAG + "first");
        appLog.log("ACH_ORIG", "INFO", TAG + "second");
        jdbc.update("UPDATE AppLog SET logTm = '2020-03-01 23:59:59.999' WHERE logMesg = ?", TAG + "first");
        jdbc.update("UPDATE AppLog SET logTm = '2020-03-02 00:00:00.000' WHERE logMesg = ?", TAG + "second");
        LocalDate first = LocalDate.of(2020, 3, 1);
        LocalDate second = LocalDate.of(2020, 3, 2);
        assertEquals(1, controller.logs(1, 20, "ALL", null, TAG, first, first).total());
        assertEquals(1, controller.logs(1, 20, "ALL", null, TAG, second, second).total());
        assertEquals(2, controller.logs(1, 20, "ALL", null, TAG, first, second).total());
        assertEquals(1, controller.logs(1, 20, "ALL", null, TAG, second, null).total());
        assertEquals(1, controller.logs(1, 20, "ALL", null, TAG, null, first).total());
    }

    @Test
    void detailsCarryTheStackTraceAndTheGridDoesNot() {
        appLog.log("ACH_RCV", "ERROR", TAG + "trace", new IllegalStateException("boom"));
        appLog.log("ACH_RCV", "INFO", TAG + "plain");
        assertTrue(controller.log(id(TAG + "trace")).logExceptionData().contains("java.lang.IllegalStateException: boom"));
        assertNull(controller.log(id(TAG + "plain")).logExceptionData());
    }

    @Test
    void anUnknownEntryIsNotFound() {
        assertThrows(EmptyResultDataAccessException.class, () -> controller.log(-1));
    }

    @Test
    void aRolledBackActionLeavesNoRow() {
        transaction.executeWithoutResult(status -> {
            appLog.log("ACH_ORIG", "INFO", TAG + "rolled back");
            status.setRollbackOnly();
        });
        assertEquals(0, search("ALL", TAG + "rolled back").total());
    }
}
