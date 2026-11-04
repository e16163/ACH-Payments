package com.achpayments.transfer;

import java.time.LocalDate;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.common.BankingCalendar;

/** UC-ACH-ORG-01 (External ACH Transfer) API for the member screens. */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    /** Feeds the date picker: the earliest allowed date and the holidays to disable (weekends are disabled too). */
    public record DateRules(LocalDate earliest, List<LocalDate> holidays) {
    }

    private final TransferDates dates;
    private final BankingCalendar calendar;

    public TransferController(TransferDates dates, BankingCalendar calendar) {
        this.dates = dates;
        this.calendar = calendar;
    }

    @GetMapping("/dates")
    public DateRules dateRules() {
        return new DateRules(dates.earliest(), calendar.holidays().stream().sorted().toList());
    }
}
