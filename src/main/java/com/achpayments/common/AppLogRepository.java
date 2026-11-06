package com.achpayments.common;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The one way to write to AppLog (UC-SYS-LOG-01). Call this instead of writing the INSERT yourself. Inside a transaction, the row
 * commits or rolls back with the action it describes. Never pass passwords, MFA codes or full account numbers.
 */
@Repository
public class AppLogRepository {

    /** The systemID of rows that belong to no one application, like a failed sign-in. The others are AppParam app names. */
    public static final String ACH_SYS = "ACH_SYS";
    public static final String ACH_ORIG = "ACH_ORIG";
    public static final String ACH_RCV = "ACH_RCV";

    private static final Logger LOG = LoggerFactory.getLogger(AppLogRepository.class);
    private static final int MAX_MESSAGE = 1000; // VARCHAR(1000)
    private static final int MAX_DETAILS = 60_000; // TEXT holds 65,535 bytes

    private final JdbcTemplate jdbc;
    private final Path fallbackFile;

    public AppLogRepository(JdbcTemplate jdbc, @Value("${app.log.fallback-file}") String fallbackFile) {
        this.jdbc = jdbc;
        this.fallbackFile = Path.of(fallbackFile);
    }

    public void log(String systemID, String level, String message) {
        log(systemID, level, message, null);
    }

    /** Step 2: {@code cause}, if any, is saved as the full stack trace. */
    public void log(String systemID, String level, String message, Throwable cause) {
        String mesg = truncate(oneLine(message), MAX_MESSAGE);
        String details = cause == null ? null : truncate(stackTrace(cause), MAX_DETAILS);
        try {
            jdbc.update("INSERT INTO AppLog (systemID, logLevel, logMesg, logExceptionData) VALUES (?, ?, ?, ?)",
                    systemID, level, mesg, details);
        } catch (DataAccessException e) {
            LOG.warn("AppLog insert failed, writing to {}", fallbackFile, e);
            writeToFile(systemID, level, mesg, details); // EX-01
        }
    }

    /** EX-01: never calls log(), so a database failure can't recurse. */
    private synchronized void writeToFile(String systemID, String level, String mesg, String details) {
        String entry = "%s %s %s %s%n".formatted(LocalDateTime.now(), level, systemID, mesg) + (details == null ? "" : details);
        try {
            Files.createDirectories(fallbackFile.toAbsolutePath().getParent());
            Files.writeString(fallbackFile, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.error("Couldn't write to {}: {}", fallbackFile, entry, e);
        }
    }

    /** Line breaks in a message could fake another log entry (OWASP log injection). */
    private static String oneLine(String s) {
        return s.replaceAll("\\p{Cntrl}+", " ");
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String stackTrace(Throwable e) {
        StringWriter out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
