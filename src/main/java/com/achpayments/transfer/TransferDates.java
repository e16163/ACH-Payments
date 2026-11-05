package com.achpayments.transfer;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;

import org.springframework.stereotype.Component;

import com.achpayments.common.BankingCalendar;

/**
 * UC-ACH-ORG-01 step 4 and "Date Picker Restrictions": the effective date must be a banking day (no weekends or
 * Federal Reserve holidays), and the earliest one is the next banking day (USE-CASES T5: no Same Day for retail).
 */
@Component
public class TransferDates {

    private final BankingCalendar calendar;
    private final Clock clock;

    public TransferDates(BankingCalendar calendar, Clock clock) {
        this.calendar = calendar;
        this.clock = clock;
    }

    public LocalDate earliest() {
        return calendar.nextBankingDay(LocalDate.now(clock));
    }

    /**
     * UC-ACH-EOD-01 E1: a transfer goes in the 5 PM file on its effective date, after the last Same Day deadline,
     * so it settles the next banking day.
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
