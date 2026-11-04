package com.achpayments.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.achpayments.nacha.AchFileValidator;

/** X1: the routing directory and OFAC list. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class ReferenceDataIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.db.receiving}")
    String recv;

    @Value("${app.db.origination}")
    String orig;

    @Test
    void everyDirectoryRoutingNumberPassesTheCheckDigit() {
        List<String> routing = jdbc.queryForList("SELECT routingNo FROM ABADirectory", String.class);
        assertTrue(routing.size() >= 6);
        routing.forEach(r -> assertTrue(AchFileValidator.isValidRouting(r), r));
    }

    @Test
    void everyRoutingNumberInTheDemoDataIsInTheDirectory() {
        List<String> missing = jdbc.queryForList("SELECT routingNo FROM " + orig + ".ACHOriginator "
                + "UNION SELECT routingNo FROM " + orig + ".ACHOrigRecvAccount "
                + "UNION SELECT routingNo FROM " + recv + ".ACHRecvAccount", String.class).stream()
                .filter(r -> jdbc.queryForObject("SELECT COUNT(*) FROM ABADirectory WHERE routingNo = ?", Integer.class, r) == 0)
                .toList();
        assertEquals(List.of(), missing);
    }

    @Test
    void theOfacListIsLoaded() {
        assertEquals(4, jdbc.queryForObject("SELECT COUNT(*) FROM OfacSdnName", Integer.class));
    }
}
