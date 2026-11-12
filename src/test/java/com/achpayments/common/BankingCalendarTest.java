package com.achpayments.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;

class BankingCalendarTest {

    // Two of the seeded holidays: Thanksgiving (Thu) and Martin Luther King Jr. Day 2027 (a Monday).
    private final BankingCalendar cal = new BankingCalendar(Set.of(
            LocalDate.parse("2026-11-26"), LocalDate.parse("2027-01-18")));

    @Test
    void weekdaysAreBankingDaysWeekendsAndHolidaysAreNot() {
        assertTrue(cal.isBankingDay(LocalDate.parse("2026-11-04")));  // Wednesday
        assertFalse(cal.isBankingDay(LocalDate.parse("2026-11-07"))); // Saturday
        assertFalse(cal.isBankingDay(LocalDate.parse("2026-11-08"))); // Sunday
        assertFalse(cal.isBankingDay(LocalDate.parse("2026-11-26"))); // Thanksgiving
    }

    @Test
    void addBankingDaysSkipsWeekends() {
        assertEquals(LocalDate.parse("2026-11-09"), cal.nextBankingDay(LocalDate.parse("2026-11-06"))); // Fri -> Mon
        assertEquals(LocalDate.parse("2026-11-10"), cal.addBankingDays(LocalDate.parse("2026-11-06"), 2));
    }

    @Test
    void addBankingDaysSkipsAHoliday() {
        // Wed + 1 = Fri, because Thursday is Thanksgiving
        assertEquals(LocalDate.parse("2026-11-27"), cal.nextBankingDay(LocalDate.parse("2026-11-25")));
    }

    @Test
    void holidayOnAMondaySkipsTheWholeLongWeekend() {
        // Fri 2027-01-15 + 1 = Tue 2027-01-19 (Sat, Sun, MLK Monday skipped)
        assertEquals(LocalDate.parse("2027-01-19"), cal.nextBankingDay(LocalDate.parse("2027-01-15")));
        // 2 banking days from Thu 2027-01-14 = Tue 2027-01-19
        assertEquals(LocalDate.parse("2027-01-19"), cal.addBankingDays(LocalDate.parse("2027-01-14"), 2));
    }

    @Test
    void countsFromAWeekendDay() {
        assertEquals(LocalDate.parse("2026-11-09"), cal.nextBankingDay(LocalDate.parse("2026-11-07"))); // Sat -> Mon
    }

    @Test
    void rejectsZeroOrNegativeDays() {
        assertThrows(IllegalArgumentException.class, () -> cal.addBankingDays(LocalDate.parse("2026-11-04"), 0));
    }

    @Test
    void theProcessingScreensShowTheNextBankingDayOnAWeekendOrHoliday() {
        assertEquals(LocalDate.parse("2026-11-04"), cal.businessDate(LocalDate.parse("2026-11-04"))); // a Wednesday is itself
        assertEquals(LocalDate.parse("2026-11-09"), cal.businessDate(LocalDate.parse("2026-11-07"))); // Saturday -> Monday
        assertEquals(LocalDate.parse("2026-11-27"), cal.businessDate(LocalDate.parse("2026-11-26"))); // Thanksgiving -> Friday
        cal.requireBankingDay(LocalDate.parse("2026-11-04"));
        var e = assertThrows(IllegalArgumentException.class, () -> cal.requireBankingDay(LocalDate.parse("2026-11-07")));
        assertEquals("Today isn't a banking day. Processing opens on 2026-11-09.", e.getMessage());
    }
}
