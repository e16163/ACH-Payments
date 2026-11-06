package com.achpayments.common;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class FailedSignInLogTest {

    private Instant now = Instant.parse("2026-11-06T12:00:00Z");
    private final AppLogRepository appLog = mock(AppLogRepository.class);
    private final FailedSignInLog log = new FailedSignInLog(appLog, new Clock() {
        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    });

    private void fail(int times) {
        for (int i = 0; i < times; i++) {
            log.record("achrecv");
        }
    }

    @Test
    void logsThirtyThenOneNoticeThenNothingMore() {
        fail(100);
        verify(appLog, times(30)).log("ACH_SYS", "WARN", "Failed sign-in for user 'achrecv'");
        verify(appLog, times(1)).log(eq("ACH_SYS"), eq("WARN"), startsWith("More than 30 failed sign-ins this minute"));
        verify(appLog, times(31)).log(anyString(), anyString(), anyString());
    }

    @Test
    void theCountStartsAgainNextMinute() {
        fail(31);
        now = now.plus(Duration.ofMinutes(1));
        fail(1);
        verify(appLog, times(31)).log("ACH_SYS", "WARN", "Failed sign-in for user 'achrecv'");
    }
}
