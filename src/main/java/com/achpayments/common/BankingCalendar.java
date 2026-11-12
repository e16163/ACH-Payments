package com.achpayments.common;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/**
 * Banking days: Monday–Friday, excluding Federal Reserve holidays (the BankingCalendar table).
 * Used for settlement dates, Nacha deadlines and the Transfer UI date picker.
 */
public class BankingCalendar {

    private final Set<LocalDate> holidays;

    public BankingCalendar(Set<LocalDate> holidays) {
        this.holidays = Set.copyOf(holidays);
    }

    public boolean isBankingDay(LocalDate date) {
        DayOfWeek day = date.getDayOfWeek();
        return day != DayOfWeek.SATURDAY && day != DayOfWeek.SUNDAY && !holidays.contains(date);
    }

    /** The date {@code n} banking days after {@code date} (n ≥ 1). E.g. Friday + 1 = the next Monday, unless it's a holiday. */
    public LocalDate addBankingDays(LocalDate date, int n) {
        if (n < 1) {
            throw new IllegalArgumentException("n must be at least 1, was " + n);
        }
        LocalDate d = date;
        for (int counted = 0; counted < n; ) {
            d = d.plusDays(1);
            if (isBankingDay(d)) {
                counted++;
            }
        }
        return d;
    }

    public LocalDate nextBankingDay(LocalDate date) {
        return addBankingDays(date, 1);
    }

    /** The day the processing screens work on: the date itself, or the next banking day on a weekend or holiday. */
    public LocalDate businessDate(LocalDate date) {
        return isBankingDay(date) ? date : nextBankingDay(date);
    }

    /** For the actions a processing screen offers: none on a weekend or holiday. */
    public void requireBankingDay(LocalDate date) {
        if (!isBankingDay(date)) {
            throw new IllegalArgumentException("Today isn't a banking day. Processing opens on " + nextBankingDay(date) + ".");
        }
    }

    /** The Federal Reserve holidays, e.g. for the Transfer UI date picker. */
    public Set<LocalDate> holidays() {
        return holidays;
    }
}
