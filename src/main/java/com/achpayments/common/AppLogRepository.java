package com.achpayments.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Writes rows to the design's AppLog table. */
@Repository
public class AppLogRepository {

    private final JdbcTemplate jdbc;

    public AppLogRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void log(String level, String message, String exceptionData) {
        jdbc.update("INSERT INTO AppLog (logLevel, logMesg, logExceptionData) VALUES (?, ?, ?)",
                level, truncate(message, 1000), exceptionData);
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
