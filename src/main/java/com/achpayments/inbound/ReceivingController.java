package com.achpayments.inbound;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.DataClassRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.inbound.FedInboundSimulator.Sample;

/** The received files, and UC-ACH-RCV-02 phase 2. Who gets here is set in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/ach/receiving")
public class ReceivingController {

    public record FileRow(String fileID, String fileName, LocalDate fileHeaderDt, LocalTime fileHeaderTm, String fedStatus,
            String processStatus, int entryCount, BigDecimal totalCreditAmt, BigDecimal totalDebitAmt) {
    }

    public record Overview(long tranID, BigDecimal amount, int creditFlag, String originatorID, String companyName, String recvName,
            LocalDateTime createTm, LocalDate settlementDt, String traceNo) {
    }

    public record Page(List<Overview> items, long total, int page, int limit) {
    }

    /** Ba's 24 ACHRecvTransaction columns. */
    public record Detail(long tranID, String transferSerNo, int transferVersion, String fileID, String batchID, int creditFlag,
            String tranCode, String companyName, String companyID, String companyDiscData, String stdEntryCode, String companyEntryDesc,
            LocalDate descriptiveDt, LocalDate effectiveDt, LocalDate settlementDt, String originatorID, BigDecimal amount, String recvAcctID,
            String recvName, String transactionDiscData, String traceNo, String fedStatus, String sourceID, LocalDateTime createTm) {
    }

    public record ReceiveRequest(Sample sample) {
    }

    private final JdbcTemplate jdbc;
    private final ReceivingEngine engine;
    private final FedInboundSimulator fed;
    private final String recv;

    public ReceivingController(JdbcTemplate jdbc, ReceivingEngine engine, FedInboundSimulator fed, @Value("${app.db.receiving}") String recv) {
        this.jdbc = jdbc;
        this.engine = engine;
        this.fed = fed;
        this.recv = recv;
    }

    /** Newest first. The counts come from the entries, which also covers Ba's sample file, whose totals are 0. */
    @GetMapping("/files")
    public List<FileRow> files() {
        return jdbc.query("SELECT f.fileID, f.fileName, f.fileHeaderDt, f.fileHeaderTm, f.fedStatus, f.processStatus, "
                + "COUNT(t.tranID) AS entryCount, "
                + "COALESCE(SUM(CASE WHEN t.creditFlag = 1 THEN t.amount END), 0) AS totalCreditAmt, "
                + "COALESCE(SUM(CASE WHEN t.creditFlag = 0 THEN t.amount END), 0) AS totalDebitAmt "
                + "FROM " + recv + ".ACHRecvFile f LEFT JOIN " + recv + ".ACHRecvTransaction t ON t.fileID = f.fileID "
                + "GROUP BY f.fileID, f.fileName, f.fileHeaderDt, f.fileHeaderTm, f.fedStatus, f.processStatus "
                + "ORDER BY f.fileHeaderDt DESC, f.fileID DESC", new DataClassRowMapper<>(FileRow.class));
    }

    /** Simulates the Fed delivering a file, then ingests it, so a demo needn't wait for 6 AM. */
    @PostMapping("/files/receive")
    public List<ReceivingEngine.Result> receive(@RequestBody ReceiveRequest body) throws IOException {
        if (body.sample() == null) {
            throw new IllegalArgumentException("Choose a file to receive.");
        }
        fed.deliver(body.sample());
        return engine.ingestAll();
    }

    /** Steps 4-5, from Ba's SQL. It filters by file, so the page's file comes as fileID; without one, every file is listed. */
    @GetMapping("/transactions")
    public Page transactions(@RequestParam(required = false) String fileID, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit, @RequestParam(defaultValue = "traceNo") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDir) {
        if (page < 1 || limit < 1 || limit > 100 || !sortBy.equals("traceNo") || !List.of("ASC", "DESC").contains(sortDir)) {
            throw new IllegalArgumentException("Invalid page, limit or sort.");
        }
        String where = fileID == null ? "" : " WHERE fileID = ?";
        Object[] filter = fileID == null ? new Object[0] : new Object[] {fileID};
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvTransaction" + where, Long.class, filter);
        List<Overview> items = jdbc.query("SELECT tranID, amount, creditFlag, originatorID, companyName, recvName, createTm, settlementDt, traceNo "
                + "FROM " + recv + ".ACHRecvTransaction" + where + " ORDER BY traceNo " + sortDir + ", tranID LIMIT " + limit
                + " OFFSET " + (page - 1L) * limit, new DataClassRowMapper<>(Overview.class), filter);
        return new Page(items, total == null ? 0 : total, page, limit);
    }

    /** Steps 6-7. */
    @GetMapping("/transactions/{tranID}")
    public Detail transaction(@PathVariable long tranID) {
        return jdbc.queryForObject("SELECT tranID, transferSerNo, transferVersion, fileID, batchID, creditFlag, tranCode, companyName, "
                + "companyID, companyDiscData, stdEntryCode, companyEntryDesc, descriptiveDt, effectiveDt, settlementDt, originatorID, amount, "
                + "recvAcctID, recvName, transactionDiscData, traceNo, fedStatus, sourceID, createTm FROM " + recv
                + ".ACHRecvTransaction WHERE tranID = ?", new DataClassRowMapper<>(Detail.class), tranID);
    }

    @ExceptionHandler(EmptyResultDataAccessException.class)
    public ResponseEntity<Map<String, String>> unknown() {
        return ResponseEntity.status(404).body(Map.of("error", "No such transaction."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
