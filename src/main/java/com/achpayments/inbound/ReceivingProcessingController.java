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
 * snake_case fields. /api/v1/ach/receiving/** needs the ACH_RECV_PROCESS permission (SecurityConfig).
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

    /** One window run on the ACH Receiving Processing screen: steps 2-6. */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WindowRun(String executionId, String windowTime, int files, int validated, int entries, int matched, int autoCorrected,
            int manualReview, int rejected, int memoPosted, String runBy, java.time.LocalDateTime startedAt) {
    }

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record WindowsStatus(LocalDate businessDate, boolean bankingDay, List<ReceivingWindow.Window> schedules, List<String> waitingFiles, List<WindowRun> runs,
            CorePostingFileJob.Result postingFile) {
    }

    private final ReceivingWindow window;
    private final ReceivingEngine engine;
    private final ReceivedEntryProcessor processor;
    private final CorePostingFileJob posting;
    private final JdbcTemplate jdbc;
    private final AppLogRepository appLog;
    private final java.time.Clock clock;
    private final com.achpayments.common.BankingCalendar calendar;
    private final String recv;

    public ReceivingProcessingController(ReceivingWindow window, ReceivingEngine engine, ReceivedEntryProcessor processor,
            CorePostingFileJob posting, JdbcTemplate jdbc, AppLogRepository appLog, java.time.Clock clock, com.achpayments.common.BankingCalendar calendar,
            @Value("${app.db.receiving}") String recv) {
        this.clock = clock;
        this.calendar = calendar;
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
        calendar.requireBankingDay(LocalDate.now(clock));
        String time = windowPath.substring(0, 2) + ":" + windowPath.substring(2);
        appLog.log(ACH_RCV, "INFO", "User '%s' triggered processing window %s".formatted(user.getName(), time));
        ReceivingWindow.Run run = window.run(time, body == null ? null : body.fileName(), user.getName());
        return new TriggerResponse(run.executionId(), run.status(), run.windowTime(), run.startedAt());
    }

    /** The ACH Receiving Processing screen: the day's (or, on a weekend or holiday, the next banking day's) windows, the files waiting, each run, and step 7. */
    @org.springframework.web.bind.annotation.GetMapping("/windows")
    public WindowsStatus windows() {
        LocalDate today = calendar.businessDate(LocalDate.now(clock));
        List<WindowRun> runs = window.runs(today).stream().map(r -> new WindowRun((String) r.get("executionID"), (String) r.get("windowTime"),
                n(r, "fileCnt"), n(r, "validatedCnt"), n(r, "entryCnt"), n(r, "matchedCnt"), n(r, "correctedCnt"), n(r, "reviewCnt"),
                n(r, "rejectedCnt"), n(r, "memoPostedCnt"), (String) r.get("runBy"), (java.time.LocalDateTime) r.get("startTm"))).toList();
        return new WindowsStatus(today, calendar.isBankingDay(LocalDate.now(clock)), window.schedule(), engine.waiting(), runs, posting.find(today).orElse(null));
    }

    private static int n(Map<String, Object> row, String column) {
        return ((Number) row.get(column)).intValue();
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
