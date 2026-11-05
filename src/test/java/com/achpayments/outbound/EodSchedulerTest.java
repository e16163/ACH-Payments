package com.achpayments.outbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.achpayments.common.AppLogRepository;
import com.achpayments.param.ParamCache;

/** Step 1: the cutoff comes from AppParam, in Eastern time, and the job runs once a day. */
class EodSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private final EodFileJob job = mock(EodFileJob.class);
    private final ParamCache params = mock(ParamCache.class);
    private final AppLogRepository appLog = mock(AppLogRepository.class);

    private EodScheduler at(String time, String cutoff) {
        when(params.get("ACH_ORIG", "FED_FILE_CUTOFF_TIME")).thenReturn(Optional.of(cutoff));
        Instant instant = LocalDate.parse("2026-11-05").atTime(java.time.LocalTime.parse(time)).atZone(ET).toInstant();
        return new EodScheduler(job, params, appLog, Clock.fixed(instant, ET));
    }

    @Test
    void waitsForTheCutoff() {
        at("16:59:59", "17:00:00").tick();
        verify(job, never()).run(any());
    }

    @Test
    void runsOnceAtTheCutoff() {
        EodScheduler scheduler = at("17:00:00", "17:00:00");
        scheduler.tick();
        scheduler.tick();
        verify(job, times(1)).run(LocalDate.parse("2026-11-05"));
    }

    @Test
    void aBadCutoffValueRaisesOneFatalAlertAndDoesNotRun() {
        EodScheduler scheduler = at("17:00:00", "5 PM");
        scheduler.tick();
        scheduler.tick();
        verify(job, never()).run(any());
        verify(appLog, times(1)).log(org.mockito.ArgumentMatchers.eq("FATAL"), anyString(), any());
    }
}
