package com.achpayments.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

/** The 6 AM pickup: the time comes from AppParam, in Eastern time, once on each banking day. */
class ReceivingSchedulerTest {

    private static final ZoneId ET = ZoneId.of("America/New_York");
    private final ReceivingEngine engine = mock(ReceivingEngine.class);
    private final ParamCache params = mock(ParamCache.class);
    private final AppLogRepository appLog = mock(AppLogRepository.class);
    private final BankingCalendar calendar = new BankingCalendar(Set.of(LocalDate.of(2026, 11, 26)));

    private ReceivingScheduler at(String dateTime, String time) {
        when(params.get("ACH_RCV", "FED_RECV_FILE_TIME")).thenReturn(Optional.of(time));
        Clock clock = Clock.fixed(Instant.parse(dateTime), ET);
        return new ReceivingScheduler(engine, params, calendar, appLog, clock);
    }

    @Test
    void waitsFor6AmEastern() {
        at("2026-11-06T10:59:00Z", "06:00:00").tick(); // 5:59 AM EST, a Friday
        verify(engine, never()).ingestAll();
    }

    @Test
    void runsOnceAt6AmEastern() {
        ReceivingScheduler s = at("2026-11-06T11:00:00Z", "06:00:00");
        s.tick();
        s.tick();
        verify(engine, times(1)).ingestAll();
    }

    @Test
    void skipsWeekendsAndHolidays() {
        at("2026-11-07T12:00:00Z", "06:00:00").tick(); // Saturday
        at("2026-11-26T12:00:00Z", "06:00:00").tick(); // Thanksgiving
        verify(engine, never()).ingestAll();
    }

    @Test
    void aBadTimeRaisesOneAlertAndDoesNotRun() {
        ReceivingScheduler s = at("2026-11-06T12:00:00Z", "6am");
        s.tick();
        s.tick();
        verify(engine, never()).ingestAll();
        verify(appLog, times(1)).log(eq("ACH_RCV"), eq("FATAL"), startsWith("Inbound pickup not run"));
    }

    @Test
    void anErrorInTheScheduledRunIsLoggedWithItsStackTrace() {
        ReceivingScheduler s = at("2026-11-06T12:00:00Z", "06:00:00");
        IllegalStateException failure = new IllegalStateException("disk gone");
        when(engine.ingestAll()).thenThrow(failure);
        s.tick();
        verify(appLog).log(eq("ACH_RCV"), eq("FATAL"), startsWith("Inbound pickup stopped"), eq(failure));
    }
}
