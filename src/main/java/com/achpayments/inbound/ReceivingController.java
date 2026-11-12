package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
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

import com.achpayments.common.AppLogRepository;
import com.achpayments.inbound.FedInboundSimulator.Sample;

/** The received files, and UC-ACH-RCV-02 phase 2. Who gets here is set in SecurityConfig. */
@RestController
@RequestMapping("/api/v1/ach/receiving")
public class ReceivingController {

    public record FileRow(String fileID, String fileName, LocalDate fileHeaderDt, LocalTime fileHeaderTm, String fedStatus,
            String processStatus, int entryCount, BigDecimal totalCreditAmt, BigDecimal totalDebitAmt) {
    }

    public record Overview(long tranID, BigDecimal amount, int creditFlag, String originatorID, String companyName, String recvName,
            LocalDateTime createTm, LocalDate effectiveDt, LocalDate settlementDt, String traceNo, String processStatus, String returnCode) {
    }

    public record Page(List<Overview> items, long total, int page, int limit) {
    }

    /** The design's 24 ACHRecvTransaction columns, then the blueprint's processing columns (V17). */
    public record Detail(long tranID, String transferSerNo, int transferVersion, String fileID, String batchID, int creditFlag,
            String tranCode, String companyName, String companyID, String companyDiscData, String stdEntryCode, String companyEntryDesc,
            LocalDate descriptiveDt, LocalDate effectiveDt, LocalDate settlementDt, String originatorID, BigDecimal amount, String recvAcctID,
            String recvName, String transactionDiscData, String traceNo, String fedStatus, String sourceID, LocalDateTime createTm,
            String processStatus, String coreTranCode, String correctedAcctNo, String processReason, String returnCode,
            LocalDateTime memoPostedTm, LocalDateTime postedTm) {
    }

    public record ReceiveRequest(Sample sample) {
    }

    private final JdbcTemplate jdbc;
    private final FedInboundSimulator fed;
    private final AppLogRepository appLog;
    private final com.achpayments.common.BankingCalendar calendar;
    private final java.time.Clock clock;
    private final String recv;

    public ReceivingController(JdbcTemplate jdbc, FedInboundSimulator fed, AppLogRepository appLog, com.achpayments.common.BankingCalendar calendar,
            java.time.Clock clock, @Value("${app.db.receiving}") String recv) {
        this.jdbc = jdbc;
        this.fed = fed;
        this.appLog = appLog;
        this.calendar = calendar;
        this.clock = clock;
        this.recv = recv;
    }

    /** Newest first. The counts come from the entries, which also covers the sample file, whose totals are 0. */
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

    public record Delivered(String fileName, String message) {
    }

    /**
     * Simulates the Fed delivering a file to fed/inbound. As in Ba's blueprint, nothing happens to it until a processing window
     * picks it up (step 1); the ACH Receiving Processing screen can run one now.
     */
    @PostMapping("/files/receive")
    public Delivered receive(@RequestBody ReceiveRequest body, Principal user) throws IOException {
        if (body.sample() == null) {
            throw new IllegalArgumentException("Choose a file to receive.");
        }
        calendar.requireBankingDay(LocalDate.now(clock)); // the Fed doesn't deliver on a weekend or holiday
        fed.deliver(body.sample());
        String fileName = body.sample().name() + ".ach";
        appLog.log(ACH_RCV, "INFO", "User '%s' received sample %s: %s is waiting for the next processing window"
                .formatted(user.getName(), body.sample(), fileName));
        return new Delivered(fileName, fileName + " is in fed/inbound, waiting for the next processing window.");
    }

    /**
     * Steps 4-5, from the design's SQL. Ba (2026-11-12): the ACH Receiving Transactions screen searches by effective date, start to
     * end inclusive; fileID (View transactions on the processing screen) and status (e.g. MANUAL_REVIEW) narrow it further.
     */
    @GetMapping("/transactions")
    public Page transactions(@RequestParam(required = false) String fileID, @RequestParam(required = false) String status,
            @RequestParam(required = false) LocalDate start, @RequestParam(required = false) LocalDate end,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int limit, @RequestParam(defaultValue = "traceNo") String sortBy,
            @RequestParam(defaultValue = "ASC") String sortDir) {
        if (page < 1 || limit < 1 || limit > 100 || !sortBy.equals("traceNo") || !List.of("ASC", "DESC").contains(sortDir)) {
            throw new IllegalArgumentException("Invalid page, limit or sort.");
        }
        List<String> conditions = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        if (fileID != null) {
            conditions.add("fileID = ?");
            values.add(fileID);
        }
        if (status != null && !status.isBlank()) {
            conditions.add("processStatus = ?");
            values.add(status);
        }
        if ((start == null) != (end == null) || (start != null && start.isAfter(end))) {
            throw new IllegalArgumentException("Choose a start date on or before the end date.");
        }
        if (start != null) {
            conditions.add("effectiveDt BETWEEN ? AND ?");
            values.add(start);
            values.add(end);
        }
        String where = conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        Object[] filter = values.toArray();
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM " + recv + ".ACHRecvTransaction" + where, Long.class, filter);
        List<Overview> items = jdbc.query("SELECT tranID, amount, creditFlag, originatorID, companyName, recvName, createTm, effectiveDt, settlementDt, traceNo, "
                + "processStatus, returnCode FROM " + recv + ".ACHRecvTransaction" + where + " ORDER BY traceNo " + sortDir + ", tranID LIMIT " + limit
                + " OFFSET " + (page - 1L) * limit, new DataClassRowMapper<>(Overview.class), filter);
        return new Page(items, total == null ? 0 : total, page, limit);
    }

    /** Steps 6-7. */
    @GetMapping("/transactions/{tranID}")
    public Detail transaction(@PathVariable long tranID) {
        return jdbc.queryForObject("SELECT tranID, transferSerNo, transferVersion, fileID, batchID, creditFlag, tranCode, companyName, "
                + "companyID, companyDiscData, stdEntryCode, companyEntryDesc, descriptiveDt, effectiveDt, settlementDt, originatorID, amount, "
                + "recvAcctID, recvName, transactionDiscData, traceNo, fedStatus, sourceID, createTm, processStatus, coreTranCode, "
                + "correctedAcctNo, processReason, returnCode, memoPostedTm, postedTm FROM " + recv
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
