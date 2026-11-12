package com.achpayments.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** ACH Business Rules Management, on the real test databases. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class BusinessRulesIntegrationTest {

    private static final String RULE = "{\"when\": {\"any\": [{\"field\": \"stdEntryCode\", \"op\": \"in\", \"value\": [\"PPD\"]}]}, "
            + "\"then\": [{\"set\": \"earlyPosting\", \"value\": false}]}";

    @Autowired BusinessRules rules;
    @Autowired JdbcTemplate jdbc;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM ACHBusinessRule WHERE ruleName LIKE 'Test %'");
        jdbc.update("DELETE FROM AppLog WHERE logMesg LIKE '%business rule%Test %'");
    }

    @Test
    void basSamplesAreSeeded() {
        List<Object> names = rules.rules("ACH_RCV").stream().map(r -> r.get("ruleName")).toList();
        assertTrue(names.contains("Early posting: Company 23416789 credits"), names.toString());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM AppParam WHERE paramName = 'TRUSTED_COMPANY_IDS'", Integer.class));
        assertEquals(2, rules.load("ACH_RCV").size()); // both seeds pass the check
    }

    @Test
    void aNewRuleAndAnEditRoundTripAndAreLogged() {
        Map<String, Object> saved = rules.save(null, "ACH_RCV", "Test rule", "  ", "If PPD set earlyPosting = false", RULE, "admin");
        long id = ((Number) saved.get("ruleID")).longValue();
        assertEquals("admin", saved.get("lastModBy"));
        assertEquals(null, saved.get("ruleDesc")); // blank is stored as NULL

        Map<String, Object> edited = rules.save(id, "IGNORED", "Test rule 2", "Desc", "pseudo", RULE, "admin2");
        assertEquals(List.of("ACH_RCV", "Test rule 2", "Desc", "admin2"),
                List.of(edited.get("systemName"), edited.get("ruleName"), edited.get("ruleDesc"), edited.get("lastModBy")));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM AppLog WHERE systemID = 'ACH_RCV' AND logMesg = ?", Integer.class,
                "User 'admin2' saved business rule ACH_RCV/Test rule 2"));
    }

    @Test
    void aBadRuleIsRefusedAndNothingIsSaved() {
        assertThrows(IllegalArgumentException.class, () -> rules.save(null, "ACH_RCV", "Test bad", null, "p", "{not json", "admin"));
        assertThrows(IllegalArgumentException.class, () -> rules.save(null, "NO_SUCH_APP", "Test bad", null, "p", RULE, "admin"));
        assertThrows(IllegalArgumentException.class, () -> rules.save(null, "ACH_RCV", "Test bad", null, " ", RULE, "admin"));
        rules.save(null, "ACH_RCV", "Test dup", null, "p", RULE, "admin");
        var dup = assertThrows(IllegalArgumentException.class, () -> rules.save(null, "ACH_RCV", "Test dup", null, "p", RULE, "admin"));
        assertEquals("ACH_RCV already has a rule named \"Test dup\".", dup.getMessage());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM ACHBusinessRule WHERE ruleName = 'Test bad'", Integer.class));
    }
}
