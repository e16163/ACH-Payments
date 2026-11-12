package com.achpayments.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
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
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.achpayments.common.AppLogRepository;
import com.achpayments.common.BankingCalendar;
import com.achpayments.param.ParamCache;

/** Ba's processing windows and the core posting file: times from ACHRecvSchedule and AppParam, in Eastern time, on banking days. */
class ReceivingSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private final ReceivingWindow window = mock(ReceivingWindow.class);
    private final CorePostingFileJob posting = mock(CorePostingFileJob.class);
    private final ParamCache params = mock(ParamCache.class);
    private final AppLogRepository appLog = mock(AppLogRepository.class);
    private final BankingCalendar calendar = new BankingCalendar(Set.of(LocalDate.of(2026, 11, 26)));

    private ReceivingScheduler at(String instant) {
        return at(instant, "06:00,09:00,12:00,15:00,18:00");
    }

    private ReceivingScheduler at(String instant, String windows) {
        when(window.schedule()).thenReturn(java.util.Arrays.stream(windows.split(","))
                .map(t -> new ReceivingWindow.Window(0, t, t + " window")).toList());
        when(params.get("ACH_RCV", "CORE_POSTING_TIME")).thenReturn(Optional.of("19:00"));
        return new ReceivingScheduler(window, posting, params, calendar, appLog, Clock.fixed(Instant.parse(instant), ET));
    }

    @Test
    void waitsForTheFirstWindow() {
        at("2026-11-06T10:59:00Z").tick(); // 5:59 AM EST, a Friday
        verify(window, never()).run(any(), any());
    }

    @Test
    void runsEachWindowOnce() {
        ReceivingScheduler s = at("2026-11-06T14:00:00Z"); // 9:00 AM
        s.tick();
        s.tick();
        verify(window, times(1)).run(eq("09:00"), isNull());
    }

    @Test
    void afterARestartOnlyTheLatestPassedWindowRuns() {
        at("2026-11-06T18:30:00Z").tick(); // 1:30 PM
        verify(window).run(eq("12:00"), isNull());
        verify(window, never()).run(eq("06:00"), any());
        verify(window, never()).run(eq("09:00"), any());
    }

    @Test
    void thePostingFileRunsOnceAfterItsTime() {
        ReceivingScheduler s = at("2026-11-07T00:00:00Z"); // 7:00 PM Friday
        s.tick();
        s.tick();
        verify(posting, times(1)).generate(LocalDate.of(2026, 11, 6));
        verify(window).run(eq("18:00"), isNull());
    }

    @Test
    void skipsWeekendsAndHolidays() {
        at("2026-11-07T15:00:00Z").tick(); // Saturday
        at("2026-11-26T15:00:00Z").tick(); // Thanksgiving
        verify(window, never()).run(any(), any());
        verify(posting, never()).generate(any());
    }

    @Test
    void aBadTimeRaisesOneAlertAndDoesNotRun() {
        ReceivingScheduler s = at("2026-11-06T14:00:00Z", "6am");
        s.tick();
        s.tick();
        verify(window, never()).run(any(), any());
        verify(appLog, times(1)).log(eq("ACH_RCV"), eq("FATAL"), startsWith("Inbound processing not run"));
    }

    @Test
    void anErrorInTheScheduledRunIsLoggedWithItsStackTrace() {
        ReceivingScheduler s = at("2026-11-06T14:00:00Z");
        IllegalStateException failure = new IllegalStateException("disk gone");
        when(window.run(any(), any())).thenThrow(failure);
        s.tick();
        verify(appLog).log(eq("ACH_RCV"), eq("FATAL"), startsWith("Inbound processing stopped"), eq(failure));
    }
}
