package com.achpayments.backoffice;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

/** Every back-office list runs against the real schema and finds its V14 sample rows. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class BackOfficeIntegrationTest {

    @Autowired
    BackOfficeController controller;

    @ParameterizedTest
    @CsvSource({"origination,returns,1", "origination,return-actions,1", "origination,reversals,1", "origination,resubmissions,1",
            "origination,nocs,2", "origination,noc-actions,2", "origination,account-subs,1", "origination,trancode-subs,1",
            "receiving,returns,1", "receiving,return-requests,1", "receiving,reversals,1", "receiving,nocs,2",
            "receiving,account-subs,1", "receiving,trancode-subs,1"})
    void everyListFindsItsSampleRows(String side, String dataset, int rows) {
        assertEquals(rows, controller.rows(side, dataset).size());
    }

    @Test
    void theReceivingReversalListShowsOnlyEntriesMarkedReversal() {
        Map<String, Object> row = controller.rows("receiving", "reversals").getFirst();
        assertEquals("REVERSAL", row.get("companyEntryDesc"));
        assertEquals("051000031234568", row.get("traceNo"));
        assertEquals(LocalDate.of(2026, 11, 5), row.get("effectiveDt"));
    }

    @Test
    void anUnknownListIsRefused() {
        assertThrows(ResponseStatusException.class, () -> controller.rows("receiving", "ACHRecvAccount"));
    }
}
