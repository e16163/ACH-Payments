package com.achpayments.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.achpayments.nacha.NachaDates;
import com.achpayments.origination.TraceNumberGenerator;

/** D5: the schema rules hold on the real test databases. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class SchemaRulesIntegrationTest {

    private static final String TEST_ODFI = "876543210";

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.db.receiving}")
    String recv;

    @Value("${app.db.origination}")
    String orig;

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM " + recv + ".ACHRecvTransaction WHERE traceNo = '021000020000001'");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvBatch WHERE batchID LIKE 'D5-%'");
        jdbc.update("DELETE FROM " + recv + ".ACHRecvFile WHERE fileID LIKE 'D5-%'");
        jdbc.update("DELETE FROM " + orig + ".ACHOrigTransaction WHERE traceNo LIKE '87654321%'");
    }

    @Test
    void inboundBatchWithFreeTextDescriptiveDateLoadsWithNullDate() {
        insertRecvFileAndBatch(); // descriptive date "NOV 26"

        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvBatch WHERE batchID = 'D5-B1'", Integer.class));
        assertNull(jdbc.queryForObject("SELECT descriptiveDt FROM " + recv + ".ACHRecvBatch WHERE batchID = 'D5-B1'", Object.class));
    }

    @Test
    void orphanReturnsAreRejected() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + orig
                + ".ACHOrigReturn (tranID, returnTraceNo, amount, returnCode, companyName, companyID, returnAcctNo, "
                + "returnRtn, returnDt, lastModBy) VALUES (999999999, '021000020000001', 10.00, 'R01', 'ACME', "
                + "'1234567890', '12345', '021000021', '2026-11-03', 'test')"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO " + recv
                + ".ACHRecvReturn (tranID, returnTraceNo, amount, returnCode, companyName, companyID, returnAcctNo, "
                + "returnRtn, returnDt, lastModBy) VALUES (999999999, '021000020000001', 10.00, 'R01', 'ACME', "
                + "'1234567890', '12345', '021000021', '2026-11-03', 'test')"));
    }

    @Test
    void orphanTransactionsAreRejected() {
        insertRecvFileAndBatch();
        // a batch that doesn't exist
        assertThrows(DataIntegrityViolationException.class, () -> insertOrigTransaction("876543210000001", null, "NO-SUCH-BATCH"));
        assertThrows(DataIntegrityViolationException.class, () -> insertRecvTransaction(null, "NO-SUCH-BATCH"));
        // a file that doesn't exist, with a real batch
        assertThrows(DataIntegrityViolationException.class, () -> insertRecvTransaction("NO-SUCH-FILE", "D5-B1"));
    }

    @Test
    void aFileWithoutABatchIsRejected() {
        // V6: MySQL 26.10 skips the fileID foreign key when batchID is NULL, so a CHECK requires the batch
        assertThrows(DataAccessException.class, () -> insertRecvTransaction("NO-SUCH-FILE", null));
        assertThrows(DataAccessException.class, () -> insertOrigTransaction("876543210000002", "NO-SUCH-FILE", null));
        assertThrows(DataAccessException.class, () -> insertRecvTransaction("D5-FILE", null));
    }

    @Test
    void traceNumbersNeverRepeatEvenUnderConcurrency() throws Exception {
        TraceNumberGenerator gen = new TraceNumberGenerator(jdbc, orig);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<List<String>>> futures = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            futures.add(pool.submit(() -> {
                List<String> mine = new ArrayList<>();
                for (int i = 0; i < 2000; i++) mine.add(gen.next(TEST_ODFI));
                return mine;
            }));
        }
        Set<String> all = new HashSet<>();
        for (Future<List<String>> f : futures) all.addAll(f.get());
        pool.shutdown();

        assertEquals(16_000, all.size());
        assertEquals(true, all.stream().allMatch(s -> s.matches("87654321\\d{7}")));
    }

    @Test
    void traceNumbersContinueAfterTheHighestStoredOneAfterARestart() {
        insertOrigTransaction("876543210000500", null, null);
        TraceNumberGenerator afterRestart = new TraceNumberGenerator(jdbc, orig);
        assertEquals("876543210000501", afterRestart.next(TEST_ODFI));
    }

    private void insertOrigTransaction(String traceNo, String fileID, String batchID) {
        jdbc.update("INSERT INTO " + orig + ".ACHOrigTransaction (fileID, batchID, creditFlag, tranCode, companyName, "
                + "companyID, stdEntryCode, effectiveDt, originatorID, amount, recvAcctID, recvName, traceNo) VALUES "
                + "(?, ?, 1, '22', 'DEMO CU', '9000000001', 'WEB', '2026-11-04', '9000000001', 10.00, '12345', "
                + "'JANE DOE', ?)", fileID, batchID, traceNo);
    }

    private void insertRecvTransaction(String fileID, String batchID) {
        jdbc.update("INSERT INTO " + recv + ".ACHRecvTransaction (fileID, batchID, creditFlag, tranCode, companyName, "
                + "companyID, stdEntryCode, effectiveDt, originatorID, amount, recvAcctID, recvName, traceNo) VALUES "
                + "(?, ?, 1, '22', 'ACME', '1234567890', 'PPD', '2026-11-04', '1234567890', 10.00, '12345', "
                + "'JANE DOE', '021000020000001')", fileID, batchID);
    }

    private void insertRecvFileAndBatch() {
        jdbc.update("INSERT INTO " + recv + ".ACHRecvFile (fileID, fileName, fileHeaderDt, fileHeaderTm) "
                + "VALUES ('D5-FILE', 'inbound.ach', '2026-11-03', '10:00:00')");
        jdbc.update("INSERT INTO " + recv + ".ACHRecvBatch (batchID, fileID, batchNo, companyName, companyID, "
                + "stdEntryCode, descriptiveDt, effectiveDt, odfiID) VALUES ('D5-B1', 'D5-FILE', 1, 'ACME CORP', "
                + "'1234567890', 'PPD', ?, '2026-11-04', '02100002')", NachaDates.descriptiveDate("NOV 26"));
    }
}
