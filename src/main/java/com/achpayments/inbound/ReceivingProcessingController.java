package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import java.math.BigDecimal;
import java.security.Principal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.achpayments.common.AppLogRepository;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Ba's blueprint, "ACH Receiving Processing System — API Specification" section II: one endpoint per step, with Ba's
 * snake_case fields. POST /api/v1/ach/receiving/** is for ROLE_ACH_RECV_OPS (SecurityConfig).
 */
@RestController
@RequestMapping("/api/v1/ach/receiving")
public class ReceivingProcessingController {

    private static final List<String> WINDOWS = List.of("0600", "0900", "1200", "1500", "1800");

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TriggerRequest(String windowTime, String fileName, String executionMode) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record TriggerResponse(String executionId, String status, String windowTime, OffsetDateTime startedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ValidateRequest(String executionId, String rawFileContent, Boolean storeRawText) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ValidateResponse(String fileId, String validationStatus, int totalRecords, boolean blockCountValid,
            boolean hashValidationPassed, boolean rawTextStored, List<String> errors) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record FileRequest(String fileId, Boolean forceEligibleOnly) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Summary(BigDecimal totalDebitAmount, BigDecimal totalCreditAmount) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ExtractResponse(String fileId, int batchesExtracted, int transactionsExtracted, Summary summary, String status) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record PostingRequest(LocalDate businessDate, String outputFormat) {
    }

    private final ReceivingWindow window;
    private final ReceivingEngine engine;
    private final ReceivedEntryProcessor processor;
    private final CorePostingFileJob posting;
    private final JdbcTemplate jdbc;
    private final AppLogRepository appLog;
    private final String recv;

    public ReceivingProcessingController(ReceivingWindow window, ReceivingEngine engine, ReceivedEntryProcessor processor,
            CorePostingFileJob posting, JdbcTemplate jdbc, AppLogRepository appLog, @Value("${app.db.receiving}") String recv) {
        this.window = window;
        this.engine = engine;
        this.processor = processor;
        this.posting = posting;
        this.jdbc = jdbc;
        this.appLog = appLog;
        this.recv = recv;
    }

    /** 1. Runs the window now and answers when it's done, so the status is COMPLETED rather than Ba's PROCESSING. */
    @PostMapping("/trigger/{window}")
    public TriggerResponse trigger(@PathVariable("window") String windowPath, @RequestBody(required = false) TriggerRequest body,
            Principal user) {
        if (!WINDOWS.contains(windowPath)) {
            throw new IllegalArgumentException("Unknown processing window " + windowPath + "; use one of " + WINDOWS + ".");
        }
        String time = windowPath.substring(0, 2) + ":" + windowPath.substring(2);
        appLog.log(ACH_RCV, "INFO", "User '%s' triggered processing window %s".formatted(user.getName(), time));
        ReceivingWindow.Run run = window.run(time, body == null ? null : body.fileName());
        return new TriggerResponse(run.executionId(), run.status(), run.windowTime(), run.startedAt());
    }

    /** 2. */
    @PostMapping("/validate")
    public ValidateResponse validate(@RequestBody ValidateRequest body) {
        boolean store = body.storeRawText() == null || body.storeRawText();
        ReceivingEngine.Validation v = engine.validate(body.rawFileContent(), store);
        return new ValidateResponse(v.fileID(), v.processStatus(), v.totalRecords(), v.blockCountValid(), v.hashValid(), store, v.errors());
    }

    /** 3. */
    @PostMapping("/extract")
    public ExtractResponse extract(@RequestBody FileRequest body) {
        engine.extract(body.fileId());
        Map<String, Object> t = jdbc.queryForMap("SELECT COUNT(DISTINCT batchID) AS batches, COUNT(*) AS entries, "
                + "COALESCE(SUM(CASE WHEN creditFlag = 0 THEN amount END), 0) AS debits, "
                + "COALESCE(SUM(CASE WHEN creditFlag = 1 THEN amount END), 0) AS credits FROM " + recv
                + ".ACHRecvTransaction WHERE fileID = ?", body.fileId());
        return new ExtractResponse(body.fileId(), ((Number) t.get("batches")).intValue(), ((Number) t.get("entries")).intValue(),
                new Summary((BigDecimal) t.get("debits"), (BigDecimal) t.get("credits")), "PERSISTED");
    }

    /** 4. */
    @PostMapping("/process-transcodes-and-accounts")
    public ReceivedEntryProcessor.TranscodeResult transcodes(@RequestBody FileRequest body) {
        return processor.processTranscodesAndAccounts(body.fileId());
    }

    /** 5. */
    @PostMapping("/evaluate-rejections")
    public ReceivedEntryProcessor.RejectionResult rejections(@RequestBody FileRequest body) {
        return processor.evaluateRejections(body.fileId());
    }

    /** 6. */
    @PostMapping("/memo-post")
    public ReceivedEntryProcessor.MemoResult memoPost(@RequestBody FileRequest body) {
        return processor.memoPost(body.fileId(), body.forceEligibleOnly() == null || body.forceEligibleOnly());
    }

    /** 7. Only CORE_FLAT_FILE is written. */
    @PostMapping("/generate-posting-file")
    public CorePostingFileJob.Result postingFile(@RequestBody PostingRequest body) {
        if (body.businessDate() == null) {
            throw new IllegalArgumentException("business_date is required.");
        }
        if (body.outputFormat() != null && !body.outputFormat().equals("CORE_FLAT_FILE")) {
            throw new IllegalArgumentException("Only output_format CORE_FLAT_FILE is supported.");
        }
        return posting.generate(body.businessDate());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
    }
}
