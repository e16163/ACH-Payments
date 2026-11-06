package com.achpayments.common;

import java.time.Clock;

/** UC-SYS-LOG-01 L11: anyone can fail a sign-in, so a script mustn't be able to fill AppLog that way (OWASP). */
public class FailedSignInLog {

    static final int MAX_PER_MINUTE = 30;

    private final AppLogRepository appLog;
    private final Clock clock;
    private long minute;
    private int count;

    public FailedSignInLog(AppLogRepository appLog, Clock clock) {
        this.appLog = appLog;
        this.clock = clock;
    }

    public void record(String name) {
        int number = next();
        if (number <= MAX_PER_MINUTE) {
            appLog.log(AppLogRepository.ACH_SYS, "WARN", "Failed sign-in for user '%s'".formatted(name));
        } else if (number == MAX_PER_MINUTE + 1) {
            appLog.log(AppLogRepository.ACH_SYS, "WARN", "More than %d failed sign-ins this minute: the rest aren't logged".formatted(MAX_PER_MINUTE));
        }
    }

    private synchronized int next() {
        long now = clock.millis() / 60_000;
        if (now != minute) {
            minute = now;
            count = 0;
        }
        return ++count;
    }
}
