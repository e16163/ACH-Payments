package com.achpayments.origination;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.achpayments.common.AppLogRepository;
import com.achpayments.common.BankingCalendar;

/**
 * Ba's blueprint, ACH Origination Processing: on banking days, runs the latest window that has passed
 * (ACHOrigSchedule) once. Checks once a minute, like ReceivingScheduler; the file itself goes at the EOD cutoff.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.eod.scheduler-enabled", havingValue = "true")
public class OriginationScheduler {

    private final OriginationWindow window;
    private final BankingCalendar calendar;
    private final AppLogRepository appLog;
    private final Clock clock;
    private LocalDateTime lastWindow;
    private LocalDate lastAlert;

    public OriginationScheduler(OriginationWindow window, BankingCalendar calendar, AppLogRepository appLog, Clock clock) {
        this.window = window;
        this.calendar = calendar;
        this.appLog = appLog;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        try {
            runIfDue();
        } catch (RuntimeException e) {
            appLog.log(ACH_ORIG, "FATAL", "Origination window stopped: " + e, e);
        }
    }

    private void runIfDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (!calendar.isBankingDay(today)) {
            return;
        }
        try {
            window.schedule().stream().map(w -> LocalTime.parse(w.windowTime())).sorted()
                    .filter(t -> !t.isAfter(now.toLocalTime())).reduce((a, b) -> b).ifPresent(t -> {
                        LocalDateTime due = today.atTime(t);
                        if (!due.equals(lastWindow)) {
                            lastWindow = due;
                            window.run(today, t.toString());
                        }
                    });
        } catch (DateTimeParseException e) {
            if (!today.equals(lastAlert)) { // one alert a day, not one a minute
                lastAlert = today;
                appLog.log(ACH_ORIG, "FATAL", "Origination window not run: ACHOrigSchedule has a time that isn't like 06:00 ("
                        + e.getParsedString() + ")");
            }
        }
    }
}
