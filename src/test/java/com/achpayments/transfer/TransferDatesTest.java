package com.achpayments.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.achpayments.common.BankingCalendar;

class TransferDatesTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    // "Today" is Wednesday 2026-11-25; Thursday 11-26 is Thanksgiving.
    private final TransferDates dates = new TransferDates(
            new BankingCalendar(Set.of(LocalDate.parse("2026-11-26"))),
            Clock.fixed(LocalDate.parse("2026-11-25").atTime(10, 0).atZone(NY).toInstant(), NY));

    @Test
    void earliestIsTheNextBankingDaySkippingTheHoliday() {
        assertEquals(LocalDate.parse("2026-11-27"), dates.earliest());
        assertEquals(Optional.empty(), dates.check(LocalDate.parse("2026-11-27")));
    }

    @Test
    void rejectsTodayTheHolidayAndAWeekend() {
        assertTrue(dates.check(LocalDate.parse("2026-11-25")).isPresent()); // today
        assertTrue(dates.check(LocalDate.parse("2026-11-26")).isPresent()); // Thanksgiving
        assertTrue(dates.check(LocalDate.parse("2026-11-28")).isPresent()); // Saturday
        assertTrue(dates.check(LocalDate.parse("2026-11-29")).isPresent()); // Sunday
        assertTrue(dates.check(null).isPresent());
    }

    @Test
    void acceptsALaterBankingDay() {
        assertEquals(Optional.empty(), dates.check(LocalDate.parse("2026-11-30"))); // Monday
    }
}
