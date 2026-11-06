package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

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
import com.achpayments.param.ParamCache;

/** The Fed's morning file: checks once a minute, like EodScheduler, so a change to FED_RECV_FILE_TIME needs no restart. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.receiving.scheduler-enabled", havingValue = "true")
public class ReceivingScheduler {

    private final ReceivingEngine engine;
    private final ParamCache params;
    private final BankingCalendar calendar;
    private final AppLogRepository appLog;
    private final Clock clock;
    private LocalDate lastRun;

    public ReceivingScheduler(ReceivingEngine engine, ParamCache params, BankingCalendar calendar, AppLogRepository appLog, Clock clock) {
        this.engine = engine;
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
            appLog.log(ACH_RCV, "FATAL", "Inbound pickup stopped: " + e, e);
        }
    }

    private void runIfDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (today.equals(lastRun) || !calendar.isBankingDay(today)) {
            return;
        }
        String time = params.get("ACH_RCV", "FED_RECV_FILE_TIME").orElse("");
        try {
            if (now.toLocalTime().isBefore(LocalTime.parse(time))) {
                return;
            }
        } catch (DateTimeParseException e) {
            lastRun = today; // one alert a day, not one a minute
            appLog.log(ACH_RCV, "FATAL", "Inbound pickup not run: FED_RECV_FILE_TIME '" + time + "' isn't a time like 06:00:00");
            return;
        }
        lastRun = today;
        engine.ingestAll();
    }
}
