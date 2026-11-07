package com.achpayments.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.param.ParamApps;

/** UC-SYS-LOG-01 phase 2, read-only. Who gets here is set in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/admin/logs")
public class AppLogController {

    private static final Set<String> LEVELS = Set.of("DEBUG", "INFO", "WARN", "ERROR", "FATAL");

    public record Row(long logAppId, String systemID, String logLevel, String logMesg, LocalDateTime logTm) {
    }

    public record Page(List<Row> items, long total, int page, int limit) {
    }

    public record Detail(long logAppId, String systemID, String logLevel, String logMesg, String logExceptionData, LocalDateTime logTm) {
    }

    private final JdbcTemplate jdbc;

    public AppLogController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Steps 4-6. {@code from} and {@code to} are whole days, both included. */
    @GetMapping
    public Page logs(@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "ALL") String level, @RequestParam(required = false) String systemID,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        if (page < 1 || limit < 1 || limit > 100 || !(level.equals("ALL") || LEVELS.contains(level))) {
            throw new IllegalArgumentException("Invalid page, limit or level.");
        }
        if (from != null && to != null && from.isAfter(to)) {
            throw new IllegalArgumentException("The From date can't be after the To date.");
        }
        // The app list: the 9 apps (as in System Configuration) plus ACH_SYS, which holds sign-in and security rows
        if (systemID != null && !ParamApps.APPS.containsKey(systemID) && !systemID.equals(AppLogRepository.ACH_SYS)) {
            throw new IllegalArgumentException("Unknown app.");
        }
        List<String> conditions = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (systemID != null) {
            conditions.add("systemID = ?");
            values.add(systemID);
        }
        if (!level.equals("ALL")) {
            conditions.add("logLevel = ?");
            values.add(level);
        }
        if (search != null && !search.isBlank()) {
            conditions.add("logMesg LIKE ?");
            values.add("%" + search.strip().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (from != null) {
            conditions.add("logTm >= ?");
            values.add(from);
        }
        if (to != null) {
            conditions.add("logTm < ?");
            values.add(to.plusDays(1));
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        Object[] filter = values.toArray();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM AppLog" + where, Long.class, filter);
        List<Row> items = jdbc.query("SELECT logAppId, systemID, logLevel, logMesg, logTm FROM AppLog" + where
                + " ORDER BY logTm DESC, logAppId DESC LIMIT " + limit + " OFFSET " + (page - 1L) * limit,
                new DataClassRowMapper<>(Row.class), filter);
        return new Page(items, total == null ? 0 : total, page, limit);
    }

    /** Steps 7-8. */
    @GetMapping("/{logAppId}")
    public Detail log(@PathVariable long logAppId) {
        return jdbc.queryForObject("SELECT logAppId, systemID, logLevel, logMesg, logExceptionData, logTm FROM AppLog WHERE logAppId = ?",
                new DataClassRowMapper<>(Detail.class), logAppId);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> unknown() {
        return ResponseEntity.status(404).body(Map.of("error", "No such log entry."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
