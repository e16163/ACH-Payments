package com.achpayments.outbound;

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
import com.achpayments.param.ParamCache;

/**
 * UC-ACH-EOD-01 step 1. Checks once a minute, so a change to FED_FILE_CUTOFF_TIME applies without a restart.
 * Runs once a day; if the app restarts after the cutoff it runs again, which is safe because a run is idempotent.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.eod.scheduler-enabled", havingValue = "true")
public class EodScheduler {

    private final EodFileJob job;
    private final ParamCache params;
    private final AppLogRepository appLog;
    private final Clock clock;
    private LocalDate lastRun;

    public EodScheduler(EodFileJob job, ParamCache params, AppLogRepository appLog, Clock clock) {
        this.job = job;
        this.params = params;
        this.appLog = appLog;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 60_000)
    public void tick() {
        try {
            runIfDue();
        } catch (RuntimeException e) {
            appLog.log(ACH_ORIG, "FATAL", "EOD run stopped: " + e, e);
        }
    }

    private void runIfDue() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        if (today.equals(lastRun)) {
            return;
        }
        String cutoff = params.get("ACH_ORIG", "FED_FILE_CUTOFF_TIME").orElse("");
        try {
            if (now.toLocalTime().isBefore(LocalTime.parse(cutoff))) {
                return;
            }
        } catch (DateTimeParseException e) {
            lastRun = today; // one alert a day, not one a minute
            appLog.log(ACH_ORIG, "FATAL", "EOD file not run: FED_FILE_CUTOFF_TIME '" + cutoff + "' isn't a time like 17:00:00");
            return;
        }
        lastRun = today;
        job.run(today);
    }
}
