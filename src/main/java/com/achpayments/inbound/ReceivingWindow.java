package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.achpayments.common.AppLogRepository;

/**
 * Ba's blueprint, ACH Receiving Processing step 1: a processing window picks up the files waiting in fed/inbound (steps 2-3),
 * then runs steps 4-6 on each file it validated.
 */
@Service
public class ReceivingWindow {

    public record Run(String executionId, String status, String windowTime, OffsetDateTime startedAt, List<ReceivingEngine.Result> files) {
    }

    /** One row of the processing schedule (V19, ACHRecvSchedule). */
    @tools.jackson.databind.annotation.JsonNaming(tools.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Window(int scheduleId, String windowTime, String windowName) {
    }

    private final ReceivingEngine engine;
    private final ReceivedEntryProcessor processor;
    private final AppLogRepository appLog;
    private final Clock clock;
    private final JdbcTemplate jdbc;
    private final String recv;

    public ReceivingWindow(ReceivingEngine engine, ReceivedEntryProcessor processor, AppLogRepository appLog, Clock clock, JdbcTemplate jdbc,
            @Value("${app.db.receiving}") String recv) {
        this.engine = engine;
        this.processor = processor;
        this.appLog = appLog;
        this.clock = clock;
        this.jdbc = jdbc;
        this.recv = recv;
    }

    /** A scheduled window. */
    public Run run(String windowTime, String fileName) {
        return run(windowTime, fileName, "SCHEDULER");
    }

    /** windowTime is HH:mm; fileName, when given, limits the run to that file. */
    public Run run(String windowTime, String fileName, String runBy) {
        OffsetDateTime started = OffsetDateTime.now(clock).withNano(0);
        LocalDate today = started.toLocalDate();
        String executionId = "EXEC-REC-" + today.format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + windowTime.replace(":", "");
        appLog.log(ACH_RCV, "INFO", executionId + ": processing window " + windowTime + " started");
        List<ReceivingEngine.Result> files = engine.ingestAll(fileName);
        int validated = 0, entries = 0, matched = 0, corrected = 0, review = 0, rejected = 0, posted = 0;
        for (ReceivingEngine.Result f : files) {
            if (f.processStatus().equals(ReceivingEngine.VALIDATED)) {
                ReceivedEntryProcessor.Counts c = processor.process(f.fileID());
                validated++;
                entries += c.entries();
                matched += c.matched();
                corrected += c.corrected();
                review += c.review();
                rejected += c.rejected();
                posted += c.memoPosted();
            }
        }
        jdbc.update("INSERT INTO " + recv + ".ACHRecvWindowRun (executionID, scheduleID, businessDt, windowTime, fileCnt, validatedCnt, "
                + "entryCnt, matchedCnt, correctedCnt, reviewCnt, rejectedCnt, memoPostedCnt, runBy, startTm, endTm) VALUES (?, (SELECT scheduleID "
                + "FROM " + recv + ".ACHRecvSchedule WHERE windowTime = ?), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", executionId, windowTime, today,
                windowTime, files.size(), validated, entries,
                matched, corrected, review, rejected, posted, runBy, started.toLocalDateTime(), LocalDateTime.now(clock).withNano(0));
        appLog.log(ACH_RCV, "INFO", executionId + ": " + files.size() + " file(s) processed");
        return new Run(executionId, "COMPLETED", windowTime, started, files);
    }

    /** The active windows, in time order. */
    public List<Window> schedule() {
        return jdbc.query("SELECT scheduleID, windowTime, windowName FROM " + recv + ".ACHRecvSchedule WHERE status = 'ACTIVE' "
                + "ORDER BY windowTime", (rs, i) -> new Window(rs.getInt(1), rs.getString(2), rs.getString(3)));
    }

    /** The window runs on a day, in order, for the ACH Receiving Processing screen. */
    public List<Map<String, Object>> runs(LocalDate day) {
        return jdbc.queryForList("SELECT executionID, windowTime, fileCnt, validatedCnt, entryCnt, matchedCnt, correctedCnt, reviewCnt, "
                + "rejectedCnt, memoPostedCnt, runBy, startTm FROM " + recv + ".ACHRecvWindowRun WHERE businessDt = ? ORDER BY runID", day);
    }
}
