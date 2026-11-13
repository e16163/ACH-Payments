package com.achpayments.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.achpayments.common.BankingCalendar;

class TransferDatesTest {

    private static final ZoneId NY = ZoneId.of("America/New_York");

    /** At this time on this day, with Thursday 2026-11-26 (Thanksgiving) a holiday and 3 PM the last origination window. */
    private static TransferDates at(String day, int hour, int minute) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn("15:00");
        return new TransferDates(new BankingCalendar(Set.of(LocalDate.parse("2026-11-26"))),
                Clock.fixed(LocalDate.parse(day).atTime(hour, minute).atZone(NY).toInstant(), NY), jdbc, "orig");
    }

    // "Today" is Wednesday 2026-11-25, after the 3 PM cutoff.
    private final TransferDates dates = at("2026-11-25", 15, 0);

    @Test
    void beforeTheLastWindowTodayIsAllowed() {
        TransferDates morning = at("2026-11-25", 14, 59);
        assertEquals(LocalDate.parse("2026-11-25"), morning.earliest());
        assertEquals(Optional.empty(), morning.check(LocalDate.parse("2026-11-25")));
    }

    @Test
    void fromTheLastWindowOnTheEarliestIsTheNextBankingDaySkippingTheHoliday() {
        assertEquals(LocalDate.parse("2026-11-27"), dates.earliest());
        assertEquals(Optional.empty(), dates.check(LocalDate.parse("2026-11-27")));
    }

    @Test
    void onAWeekendTheEarliestIsMondayEvenInTheMorning() {
        assertEquals(LocalDate.parse("2026-11-30"), at("2026-11-28", 9, 0).earliest());
    }

    @Test
    void rejectsTodayAfterTheCutoffTheHolidayAndAWeekend() {
        assertTrue(dates.check(LocalDate.parse("2026-11-25")).isPresent()); // today, after 3 PM
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
