package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.achpayments.common.AppLogRepository;
import com.achpayments.common.BankingCalendar;
import com.achpayments.param.ParamCache;

/**
 * Ba's blueprint, ACH Receiving Processing steps 1 and 7: on banking days, runs the latest processing window that has
 * passed (ACHRecvSchedule) once, then the core posting file at ACH_RCV/CORE_POSTING_TIME. Checks once a minute,
 * like EodScheduler, so a parameter change needs no restart. After a restart only the latest window runs; it picks up
 * every waiting file, so nothing is missed.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.receiving.scheduler-enabled", havingValue = "true")
public class ReceivingScheduler {

    private final ReceivingWindow window;
    private final CorePostingFileJob posting;
    private final ParamCache params;
    private final BankingCalendar calendar;
    private final AppLogRepository appLog;
    private final Clock clock;
    private LocalDateTime lastWindow;
    private LocalDate lastPosting, lastAlert;

    public ReceivingScheduler(ReceivingWindow window, CorePostingFileJob posting, ParamCache params, BankingCalendar calendar,
            AppLogRepository appLog, Clock clock) {
        this.window = window;
        this.posting = posting;
        this.params = params;
        this.calendar = calendar;
        this.appLog = appLog;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        try {
            runIfDue();
        } catch (RuntimeException e) {
            appLog.log(ACH_RCV, "FATAL", "Inbound processing stopped: " + e, e);
        }
    }

    private void runIfDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (!calendar.isBankingDay(today)) {
            return;
        }
        List<LocalTime> windows;
        LocalTime postingTime;
        try {
            windows = window.schedule().stream().map(w -> LocalTime.parse(w.windowTime())).sorted().toList();
            postingTime = LocalTime.parse(params.get("ACH_RCV", "CORE_POSTING_TIME").orElse(""));
        } catch (DateTimeParseException e) {
            if (!today.equals(lastAlert)) { // one alert a day, not one a minute
                lastAlert = today;
                appLog.log(ACH_RCV, "FATAL", "Inbound processing not run: ACHRecvSchedule or CORE_POSTING_TIME has a time "
                        + "that isn't like 06:00 (" + e.getParsedString() + ")");
            }
            return;
        }
        windows.stream().filter(t -> !t.isAfter(now.toLocalTime())).reduce((a, b) -> b).ifPresent(t -> {
            LocalDateTime due = today.atTime(t);
            if (!due.equals(lastWindow)) {
                lastWindow = due;
                window.run(t.toString(), null);
            }
        });
        if (!now.toLocalTime().isBefore(postingTime) && !today.equals(lastPosting)) {
            lastPosting = today;
            posting.generate(today);
        }
    }
}
