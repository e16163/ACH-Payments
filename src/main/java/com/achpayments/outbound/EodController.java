package com.achpayments.outbound;

import java.math.BigDecimal;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.param.ParamCache;

/** API for the admin ACH Files page (USE-CASES E3). Only ROLE_SYS_ADMIN gets here; see SecurityConfig. */
@RestController
public class EodController {

    public record RunRequest(LocalDate businessDate) {
    }

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
    private final Clock clock;
    private final String orig;

    public EodController(EodFileJob job, EodFileStore store, JdbcTemplate jdbc, ParamCache params, Clock clock,
            @Value("${app.db.origination}") String orig) {
        this.job = job;
        this.store = store;
        this.jdbc = jdbc;
        this.params = params;
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
        List<Item> pending = items("t.processStatus = 'PENDING_BATCH' AND t.ofacStatus = 'OFAC_PASSED'", null);
        List<Item> flagged = items("t.processStatus = 'PENDING' AND t.ofacStatus = 'FLAGGED'", "OFAC review");
        List<Item> rejected = items("t.processStatus = 'REJECTED' AND t.lastModBy = 'EOD_BATCH'", "Insufficient funds");
        LocalDate today = LocalDate.now(clock);
        LocalDate next = pending.isEmpty() ? today : pending.get(0).effectiveDt();
        List<FileRow> files = jdbc.query("SELECT fileID, fileName, fedStatus, fileHeaderDt, totalCreditCount, totalCreditAmt, sendTm FROM "
                + orig + ".ACHOrigFile WHERE processID LIKE 'EOD-%' ORDER BY fileID DESC",
                (rs, i) -> new FileRow(rs.getString(1), rs.getString(2), rs.getString(3), rs.getObject(4, LocalDate.class), rs.getInt(5),
                        rs.getBigDecimal(6), rs.getObject(7, LocalDateTime.class)));
        return new Status(params.get("ACH_ORIG", "FED_FILE_CUTOFF_TIME").orElse(""), next.isBefore(today) ? today : next,
                pending, java.util.stream.Stream.concat(flagged.stream(), rejected.stream()).toList(), files);
    }

    /** Run now. Ba's step 2 allows a "scheduled business date", which is what lets a demo send a future-dated transfer. */
    @PostMapping("/api/eod/run")
    public EodFileJob.RunResult run(@RequestBody RunRequest body) {
        if (body.businessDate() == null) {
            throw new IllegalArgumentException("Choose a business date.");
        }
        return job.run(body.businessDate());
    }

    @PostMapping("/api/eod/files/{fileID}/resend")
    public Map<String, String> resend(@PathVariable String fileID) {
        return Map.of("fedStatus", job.resend(fileID));
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
