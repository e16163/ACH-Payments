package com.achpayments.inbound;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.achpayments.inbound.ReceivingFileController.FileRow;

/** The ACH Receiving File list, on the real test databases. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class ReceivingFileControllerIntegrationTest {

    private static final String EMPTY_FILE = "ACHFILE-TEST-EMPTY";

    @Autowired
    ReceivingFileController controller;
    @Autowired
    JdbcTemplate jdbc;
    @Value("${app.db.receiving}")
    String recv;

    @AfterEach
    void removeFile() {
        jdbc.update("DELETE FROM " + recv + ".ACHRecvFile WHERE fileID = ?", EMPTY_FILE);
    }

    @Test
    void listsBasSampleFileWithCountsFromItsEntries() {
        assertEquals(new FileRow("ACHFILE-88392", "ACHFILE-88392.ach", LocalDate.of(2026, 11, 1), LocalTime.of(16, 0),
                "PROCESSED", 1, new BigDecimal("3450.50"), new BigDecimal("0.00")), sample());
    }

    @Test
    void aNewerFileWithNoEntriesComesFirstWithZeroTotals() {
        jdbc.update("INSERT INTO " + recv + ".ACHRecvFile (fileID, fileName, fileHeaderDt, fileHeaderTm, fedStatus) "
                + "VALUES (?, 'empty.ach', '2030-01-02', '06:00:00', 'PENDING')", EMPTY_FILE);
        FileRow first = controller.files().get(0);
        assertEquals(EMPTY_FILE, first.fileID());
        assertEquals(0, first.entryCount());
        assertEquals(0, first.totalCreditAmt().signum());
        assertEquals(0, first.totalDebitAmt().signum());
    }

    private FileRow sample() {
        return controller.files().stream().filter(f -> f.fileID().equals("ACHFILE-88392")).findFirst().orElseThrow();
    }
}
