package com.achpayments.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Runs against a real MySQL test database (never the app's own), with the schema built by Flyway.
 * Skipped unless TEST_JDBC_URL is set; see README "Running the database tests".
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class ParamServiceIntegrationTest {

    @Autowired
    ParamService service;

    @Autowired
    ParamRepository repo;

    @Autowired
    ParamCache cache;

    @Autowired
    JdbcTemplate jdbc;

    @BeforeEach
    void resetToSeedData() {
        jdbc.update("DELETE FROM AppLog WHERE logMesg LIKE 'User %saved parameter%'");
        jdbc.update("DELETE FROM AppParam WHERE NOT (paramName IN ('FED_IMMEDIATE_DEST', 'ORIG_COMPANY_NAME', 'FED_SIM_FAIL_ATTEMPTS', 'MEMO_POST_IMMEDIATE', 'FED_FILE_CUTOFF_TIME', 'RDFI_ROUTING_NO', 'CORE_POSTING_TIME', 'MAX_ENTRY_AMOUNT'))");
        jdbc.update("UPDATE AppParam SET paramRawValue = '17:00:00', lastModBy = 'SYS_ADMIN'"
                + " WHERE paramName = 'FED_FILE_CUTOFF_TIME'");
        cache.clear();
    }

    @Test
    void migrationLoadsTheDesignSampleParameters() {
        assertEquals("17:00:00", repo.find("ACH_ORIG", "FED_FILE_CUTOFF_TIME").orElseThrow().paramRawValue());
        assertEquals("TRUE", repo.find("ACH_RCV", "MEMO_POST_IMMEDIATE").orElseThrow().paramRawValue());
    }

    @Test
    void savingIsLoggedUnderTheParametersApp() {
        service.save("ACH_RCV", "MAX_RETRY_COUNT", "Max resubmissions", "INTEGER", "2", "admin");
        assertEquals("ACH_RCV INFO User 'admin' saved parameter ACH_RCV/MAX_RETRY_COUNT = 2", jdbc.queryForObject(
                "SELECT CONCAT_WS(' ', systemID, logLevel, logMesg) FROM AppLog WHERE logMesg LIKE 'User %saved parameter%'", String.class));
    }

    @Test
    void anInvalidSaveIsNotLogged() {
        org.junit.jupiter.api.Assertions.assertThrows(ParamService.InvalidParamException.class,
                () -> service.save("ACH_ORIG", "MAX_RETRY_COUNT", "Max resubmissions", "INTEGER", "abc", "admin"));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM AppLog WHERE logMesg LIKE 'User %saved parameter%'", Integer.class));
    }

    @Test
    void insertsANewParameterThenUpdatesItOnTheCompositeKey() {
        AppParam added = service.save("ACH_ORIG", "MAX_RETRY_COUNT", "Max resubmissions", "INTEGER", "2", "admin");
        assertEquals("2", added.paramRawValue());
        assertEquals("admin", added.lastModBy());

        AppParam updated = service.save("ACH_ORIG", "MAX_RETRY_COUNT", "Max resubmissions", "INTEGER", "1", "admin2");
        assertEquals("1", updated.paramRawValue());
        assertEquals("admin2", updated.lastModBy());
        assertEquals(1, repo.findBySystem("ACH_ORIG").stream().filter(p -> p.paramName().equals("MAX_RETRY_COUNT")).count());
    }

    @Test
    void savingSetsLastModTmEvenWhenTheValueIsUnchanged() {
        jdbc.update("UPDATE AppParam SET lastModTm = '2020-01-01 00:00:00' WHERE paramName = 'MEMO_POST_IMMEDIATE'");

        AppParam saved = service.save("ACH_RCV", "MEMO_POST_IMMEDIATE",
                "Enable immediate memo posting upon transaction ingestion", "BOOLEAN", "TRUE", "admin");

        assertTrue(saved.lastModTm().isAfter(LocalDateTime.now().minusMinutes(5)), saved.lastModTm().toString());
    }

    @Test
    void baSExampleCreditLimitIsSavedWithItsNameAsTyped() {
        AppParam saved = service.save("ACH_ORIG", "credit_limit", "Credit limit for ACH Origination transfers",
                "DECIMAL", "5000.00", "admin");

        assertEquals("credit_limit", saved.paramName());
        assertEquals("5000.00", saved.paramRawValue());
    }

    @Test
    void unknownAppIsRejectedAndNothingIsSaved() {
        ParamService.InvalidParamException e = assertThrows(ParamService.InvalidParamException.class,
                () -> service.save("ACH_FOO", "credit_limit", "d", "DECIMAL", "1", "admin"));

        assertEquals("Unknown app: ACH_FOO.", e.getMessage());
        assertTrue(repo.find("ACH_FOO", "credit_limit").isEmpty());
    }

    @Test
    void typeMismatchIsRejectedAndNothingIsSaved() {
        assertThrows(ParamService.InvalidParamException.class,
                () -> service.save("ACH_ORIG", "MAX_RETRY_COUNT", "d", "INTEGER", "five", "admin"));

        assertTrue(repo.find("ACH_ORIG", "MAX_RETRY_COUNT").isEmpty());
    }

    @Test
    void saveClearsTheRuntimeCacheSoTheNewValueIsUsedImmediately() {
        assertEquals("17:00:00", cache.get("ACH_ORIG", "FED_FILE_CUTOFF_TIME").orElseThrow());

        service.save("ACH_ORIG", "FED_FILE_CUTOFF_TIME", "Cutoff", "STRING", "18:00:00", "admin");

        assertEquals("18:00:00", cache.get("ACH_ORIG", "FED_FILE_CUTOFF_TIME").orElseThrow());
    }
}
