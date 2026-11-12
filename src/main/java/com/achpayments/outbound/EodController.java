package com.achpayments.outbound;

import static com.achpayments.common.AppLogRepository.ACH_ORIG;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.common.AppLogRepository;
import com.achpayments.param.ParamCache;

/** API for the ACH Origination File page (USE-CASES E3). Only ROLE_ACH_ORIG_OPS gets here; see SecurityConfig. */
@RestController
public class EodController {

    public record FileRow(String fileID, String fileName, String fedStatus, LocalDate fileHeaderDt, int totalCreditCount,
            BigDecimal totalCreditAmt, LocalDateTime sendTm) {
    }

    public record Item(String transferSerNo, String recvName, BigDecimal amount, LocalDate effectiveDt, String reason) {
    }

    public record Status(String cutoff, LocalDate nextBusinessDate, List<Item> pending, List<Item> leftOut, List<FileRow> files) {
    }

    private final EodFileJob job;
    private final EodFileStore store;
    private final JdbcTemplate jdbc;
    private final ParamCache params;
    private final AppLogRepository appLog;
    private final Clock clock;
    private final String orig;

    public EodController(EodFileJob job, EodFileStore store, JdbcTemplate jdbc, ParamCache params, AppLogRepository appLog, Clock clock,
            @Value("${app.db.origination}") String orig) {
        this.job = job;
        this.store = store;
        this.jdbc = jdbc;
        this.params = params;
        this.appLog = appLog;
        this.clock = clock;
        this.orig = orig;
    }

    private List<Item> items(String where, String reason) {
        return jdbc.query("SELECT t.transferSerNo, a.acctOwner, t.transferAmt, t.effectiveDt FROM " + orig + ".ACHOriginatorTransfer t "
                + "JOIN " + orig + ".ACHOrigRecvAccount a ON a.recvAcctSerNo = t.recvAcctSerNo AND a.recvAcctVersion = t.recvAcctVersion "
                + "WHERE t.transferVersion = 1 AND t.effectiveDt IS NOT NULL AND " + where + " ORDER BY t.effectiveDt, t.transferSerNo",
                (rs, i) -> new Item(rs.getString(1), rs.getString(2), rs.getBigDecimal(3), rs.getObject(4, LocalDate.class), reason));
    }

    @GetMapping("/api/eod/status")
    public Status status() {
        // Waiting for a window, or made ready to post by one (Ba's blueprint, steps 1-7)
        List<Item> pending = items("t.processStatus IN ('PENDING_BATCH', 'READY_TO_POST') AND t.ofacStatus IN ('OFAC_PASSED', 'CLEARED')", null);
        List<Item> flagged = items("t.processStatus = 'PENDING' AND t.ofacStatus = 'FLAGGED'", "OFAC review");
        List<Item> blocked = items("t.processStatus = 'REJECTED' AND t.ofacStatus = 'BLOCKED'", "OFAC blocked");
        List<Item> rejected = items("t.processStatus = 'REJECTED' AND t.ofacStatus <> 'BLOCKED' AND t.lastModBy IN ('EOD_BATCH', 'ORIG_WINDOW')",
                "Failed the account, limit or funds check");
        LocalDate today = LocalDate.now(clock);
        LocalDate next = pending.isEmpty() ? today : pending.get(0).effectiveDt();
        List<FileRow> files = jdbc.query("SELECT fileID, fileName, fedStatus, fileHeaderDt, totalCreditCount, totalCreditAmt, sendTm FROM "
                + orig + ".ACHOrigFile WHERE processID LIKE 'EOD-%' ORDER BY fileID DESC",
                (rs, i) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class), rs.getInt(5),
                        rs.getBigDecimal(6), rs.getObject(7, LocalDateTime.class)));
        return new Status(params.get("ACH_ORIG", "FED_FILE_CUTOFF_TIME").orElse(""), next.isBefore(today) ? today : next,
                pending, java.util.stream.Stream.of(flagged, blocked, rejected).flatMap(List::stream).toList(), files);
    }

    @PostMapping("/api/eod/files/{fileID}/resend")
    public Map<String, String> resend(@PathVariable String fileID, Principal user) {
        String status = job.resend(fileID);
        appLog.log(ACH_ORIG, level(status), "User '%s' resent %s: %s".formatted(user.getName(), fileID, status));
        return Map.of("fedStatus", status);
    }

    private static String level(String fedStatus) {
        return "ERROR".equals(fedStatus) ? "ERROR" : "INFO";
    }

    @GetMapping("/api/eod/files/{fileID}/content")
    public Map<String, String> content(@PathVariable String fileID) {
        return Map.of("fileName", store.fileName(fileID), "content", store.fileText(fileID));
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> unknownFile() {
        return ResponseEntity.status(404).body(Map.of("error", "No such file."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
