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
