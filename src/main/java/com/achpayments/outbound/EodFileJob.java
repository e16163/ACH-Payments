package com.achpayments.outbound;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.achpayments.common.AppLogRepository;
import com.achpayments.common.BankingCalendar;

/** UC-ACH-EOD-01: one run of the batch job. Synchronized, because a run must never overlap another. */
@Service
public class EodFileJob {

    static final int MAX_ATTEMPTS = 3; // EX-02

    public record RunResult(String fileID, String fedStatus, String message) {
    }

    private final EodFileStore store;
    private final FedSimulator fed;
    private final AppLogRepository appLog;
    private final BankingCalendar calendar;
    private final Clock clock;
    private final long backoffMillis;

    public EodFileJob(EodFileStore store, FedSimulator fed, AppLogRepository appLog, BankingCalendar calendar, Clock clock,
            @Value("${app.fed.retry-backoff-ms}") long backoffMillis) {
        this.store = store;
        this.fed = fed;
        this.appLog = appLog;
        this.calendar = calendar;
        this.clock = clock;
        this.backoffMillis = backoffMillis;
    }

    /** Idempotency note: files left QUEUED by an interrupted run go first, then anything newly due. */
    public synchronized RunResult run(java.time.LocalDate businessDate) {
        if (!calendar.isBankingDay(businessDate)) {
            appLog.log("INFO", "EOD file: " + businessDate + " is not a banking day", null);
            return new RunResult(null, null, businessDate + " isn't a banking day, so no file was built.");
        }
        store.queuedFiles().forEach(this::transmit);
        String processID = processID();
        Optional<String> fileID;
        try {
            fileID = store.compile(businessDate, processID);
        } catch (RuntimeException e) {
            appLog.log("FATAL", "EOD file could not be built: " + e.getMessage(), e.toString());
            return new RunResult(null, "ERROR", "The file could not be built. See AppLog.");
        }
        if (fileID.isEmpty()) {
            return new RunResult(null, null, "Nothing is due for " + businessDate + ".");
        }
        String status = transmit(fileID.get());
        return new RunResult(fileID.get(), status, fileID.get() + ": " + status);
    }

    /** An operator's resend of a file in ERROR. */
    public synchronized String resend(String fileID) {
        if (!"ERROR".equals(store.fedStatus(fileID))) {
            throw new IllegalArgumentException("Only a file in ERROR can be resent.");
        }
        return transmit(fileID);
    }

    /** Step 7 and EX-02: up to 3 attempts, waiting twice as long after each failure. */
    private String transmit(String fileID) {
        String text = store.fileText(fileID);
        String run = processID();
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            String processID = run + "-A" + attempt;
            try {
                fed.send(store.fileName(fileID), text, attempt);
                store.markSent(fileID, processID);
                return "SENT";
            } catch (IOException e) {
                store.recordFailedAttempt(fileID, processID);
                appLog.log("WARN", "Fed send of " + fileID + " failed on attempt " + attempt + ": " + e.getMessage(), null);
                if (attempt == MAX_ATTEMPTS) {
                    store.markError(fileID, processID);
                    appLog.log("FATAL", "Operations alert: " + fileID + " could not be sent to the Fed after "
                            + MAX_ATTEMPTS + " attempts", e.toString());
                } else {
                    sleep(backoffMillis << (attempt - 1));
                }
            }
        }
        return "ERROR";
    }

    private String processID() {
        return "EOD-" + LocalDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS"));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
