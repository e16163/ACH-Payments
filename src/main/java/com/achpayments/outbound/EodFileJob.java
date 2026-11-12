package com.achpayments.outbound;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.achpayments.common.AppLogRepository;
import com.achpayments.common.BankingCalendar;
import com.achpayments.origination.OriginationWindow;

/** UC-ACH-EOD-01: one run of the batch job. Synchronized, because a run must never overlap another. */
@Service
public class EodFileJob {

    static final int MAX_ATTEMPTS = 3; // EX-02

    public record RunResult(String fileID, String fedStatus, String message) {
    }

    private final EodFileStore store;
    private final OriginationWindow window;
    private final FedSimulator fed;
    private final AppLogRepository appLog;
    private final BankingCalendar calendar;
    private final Clock clock;
    private final long backoffMillis;

    public EodFileJob(EodFileStore store, OriginationWindow window, FedSimulator fed, AppLogRepository appLog, BankingCalendar calendar, Clock clock,
            @Value("${app.fed.retry-backoff-ms}") long backoffMillis) {
        this.store = store;
        this.window = window;
        this.fed = fed;
        this.appLog = appLog;
        this.calendar = calendar;
        this.clock = clock;
        this.backoffMillis = backoffMillis;
    }

    /** The 5 PM file: a last window, then everything ready that no operator sent from a window. */
    public synchronized RunResult run(java.time.LocalDate businessDate) {
        return send(businessDate, true, processID(), null);
    }

    /** Send to the Fed, from the ACH Origination Processing screen: a file of the transfers that schedule made ready. */
    public synchronized RunResult sendNow(java.time.LocalDate businessDate, String windowId, int scheduleID) {
        return send(businessDate, false, windowId, scheduleID);
    }

    /** Idempotency note: files left QUEUED by an interrupted run go first, then anything newly due. */
    private RunResult send(java.time.LocalDate businessDate, boolean sweep, String processID, Integer scheduleID) {
        if (!calendar.isBankingDay(businessDate)) {
            appLog.log(ACH_ORIG, "INFO", "EOD file: " + businessDate + " is not a banking day");
            return new RunResult(null, null, businessDate + " isn't a banking day, so no file was built.");
        }
        store.queuedFiles().forEach(this::transmit);
        if (sweep) {
            // A last origination window first (Ba's blueprint steps 1-7), so a transfer cleared by compliance after 3 PM, or a
            // business date chosen with Run now, is made ready to post.
            window.run(businessDate, "EOD", "EOD_BATCH");
        }
        Optional<String> fileID;
        try {
            fileID = store.compile(businessDate, processID, scheduleID);
        } catch (RuntimeException e) {
            appLog.log(ACH_ORIG, "FATAL", "EOD file could not be built: " + e.getMessage(), e);
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
                appLog.log(ACH_ORIG, "INFO", "Fed send of " + fileID + " succeeded on attempt " + attempt);
                return "SENT";
            } catch (IOException e) {
                store.recordFailedAttempt(fileID, processID);
                appLog.log(ACH_ORIG, "WARN", "Fed send of " + fileID + " failed on attempt " + attempt + ": " + e.getMessage());
                if (attempt == MAX_ATTEMPTS) {
                    store.markError(fileID, processID);
                    appLog.log(ACH_ORIG, "FATAL", "Operations alert: " + fileID + " could not be sent to the Fed after "
                            + MAX_ATTEMPTS + " attempts", e);
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
