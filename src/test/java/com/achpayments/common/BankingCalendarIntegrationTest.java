package com.achpayments.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** The migration's seeded holidays reach the BankingCalendar bean. Needs TEST_JDBC_URL (README section 7). */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
class BankingCalendarIntegrationTest {

    @Autowired
    BankingCalendar calendar;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void seededFedHolidaysAreLoaded() {
        assertEquals(19, jdbc.queryForObject("SELECT COUNT(*) FROM BankingCalendar", Integer.class));
        assertFalse(calendar.isBankingDay(LocalDate.parse("2026-11-26"))); // Thanksgiving
        assertFalse(calendar.isBankingDay(LocalDate.parse("2027-07-05"))); // July 4th observed
        assertEquals(LocalDate.parse("2026-07-06"), calendar.nextBankingDay(LocalDate.parse("2026-07-03")));
    }
}
