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

/** D6: the sample rows are loaded (V7) with the values. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class DemoSeedIntegrationTest {

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.db.receiving}")
    String recv;

    @Value("${app.db.origination}")
    String orig;

    private int count(String sql) {
        return jdbc.queryForObject(sql, Integer.class);
    }

    @Test
    void allOfBasSampleRowsAreLoaded() {
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigTransaction WHERE traceNo = '051000031234567' "
                + "AND amount = 3450.50 AND recvName = 'JOHN DOE' AND fedStatus = 'SETTLED'"));
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigRecvAccount WHERE recvAcctSerNo = 'REC-SRV-20261102-01'"));
        assertEquals(List.of("DAILY_CHECKING_DEBIT_CAP", "DAILY_SAVINGS_LIMIT", "MONTHLY_CORPORATE_CAP", "WEEKLY_RETAIL_LIMIT"),
                jdbc.queryForList("SELECT ruleName FROM " + orig + ".ACHOrigLimitRule ORDER BY ruleName", String.class));
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOriginatorTransfer WHERE transferSerNo = 'TRF-20261101-9921' "
                + "AND transferAmt = 1250.00 AND recvAcctSerNo = 'REC-SRV-20261102-01' AND originatorID = '9000000001'"));
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigResubAction WHERE processID = 'PROC_RESUB_02'"));
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigNOCAction WHERE nocID = 50912"));
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigFedAction WHERE processID = 'PROC_FED_01'"));
        assertEquals(1, count("SELECT COUNT(*) FROM " + orig + ".ACHOrigReversalAction WHERE processID = 'PROC_REV_01'"));
        assertEquals(1, count("SELECT COUNT(*) FROM " + recv + ".ACHRecvTransaction WHERE traceNo = '051000031234567'"));
        assertEquals(1, count("SELECT COUNT(*) FROM " + recv + ".ACHRecvAccount WHERE recvAcctSerNo = 'REC-SRV-20261102-01'"));
        assertEquals(2, count("SELECT COUNT(*) FROM AppParam WHERE paramName IN ('MEMO_POST_IMMEDIATE', 'FED_FILE_CUTOFF_TIME')"));
        assertEquals(1, count("SELECT COUNT(*) FROM AppLog WHERE logMesg LIKE 'Failed to parse incoming ACH file HEADER%'"));
    }

    @Test
    void everySeededRoutingNumberPassesTheCheckDigit() {
        List<String> routing = jdbc.queryForList("SELECT routingNo FROM " + orig + ".ACHOriginator "
                + "UNION ALL SELECT routingNo FROM " + orig + ".ACHOrigRecvAccount "
                + "UNION ALL SELECT routingNo FROM " + recv + ".ACHRecvAccount", String.class);
        assertTrue(routing.size() >= 4);
        routing.forEach(r -> assertTrue(AchFileValidator.isValidRouting(r), r));
    }
}
