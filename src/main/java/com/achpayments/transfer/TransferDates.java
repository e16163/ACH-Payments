package com.achpayments.transfer;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.achpayments.common.BankingCalendar;

/**
 * UC-ACH-ORG-01 step 4 and "Date Picker Restrictions": the effective date must be a banking day (no weekends or
 * Federal Reserve holidays). Ba (2026-11-13): before the day's last origination window (3 PM), it can be today, and the
 * next window sends it; after that, the earliest is the next banking day, sent at its 6 AM window.
 */
@Component
public class TransferDates {

    private final BankingCalendar calendar;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final String orig;

    public TransferDates(BankingCalendar calendar, Clock clock, JdbcTemplate jdbc, @Value("${app.db.origination}") String orig) {
        this.calendar = calendar;
        this.clock = clock;
        this.jdbc = jdbc;
        this.orig = orig;
    }

    /** The same-day cutoff: the last active origination window (ACHOrigSchedule), or null when there's none. */
    public LocalTime cutoff() {
        String lastWindow = jdbc.queryForObject("SELECT MAX(windowTime) FROM " + orig + ".ACHOrigSchedule WHERE status = 'ACTIVE'",
                String.class);
        return lastWindow == null ? null : LocalTime.parse(lastWindow);
    }

    public LocalDate earliest() {
        LocalDate today = LocalDate.now(clock);
        LocalTime cutoff = cutoff();
        boolean sameDay = calendar.isBankingDay(today) && cutoff != null && LocalTime.now(clock).isBefore(cutoff);
        return sameDay ? today : calendar.nextBankingDay(today);
    }

    /**
     * UC-ACH-EOD-01 E1: a transfer is sent on its effective date, and Same Day settlement isn't offered yet,
     * so it's promised for the next banking day.
     */
    public LocalDate estimatedArrival(LocalDate effectiveDt) {
        return calendar.nextBankingDay(effectiveDt);
    }

    /** Payment {@code index} of a series (0 = the first), counted from the first date so Jan 31 → Feb 28 → Mar 31. */
    public LocalDate occurrence(LocalDate first, String frequency, int index) {
        LocalDate date = switch (frequency) {
            case "WEEKLY" -> first.plusWeeks(index);
            case "BIWEEKLY" -> first.plusWeeks(2L * index);
            case "MONTHLY" -> first.plusMonths(index);
            default -> throw new IllegalArgumentException("Not a recurring frequency: " + frequency);
        };
        return calendar.isBankingDay(date) ? date : calendar.nextBankingDay(date);
    }

    /** An error message, or empty if the date is allowed. */
    public Optional<String> check(LocalDate effectiveDt) {
        if (effectiveDt == null) {
            return Optional.of("Choose a transfer date.");
        }
        if (effectiveDt.isBefore(earliest())) {
            return Optional.of("The earliest transfer date is " + earliest() + ".");
        }
        if (!calendar.isBankingDay(effectiveDt)) {
            return Optional.of("Choose a banking day. Weekends and Federal Reserve holidays aren't available.");
        }
        return Optional.empty();
    }
}
