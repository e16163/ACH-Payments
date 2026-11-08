package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

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

    private final ReceivingEngine engine;
    private final ReceivedEntryProcessor processor;
    private final AppLogRepository appLog;
    private final Clock clock;

    public ReceivingWindow(ReceivingEngine engine, ReceivedEntryProcessor processor, AppLogRepository appLog, Clock clock) {
        this.engine = engine;
        this.processor = processor;
        this.appLog = appLog;
        this.clock = clock;
    }

    /** windowTime is HH:mm; fileName, when given, limits the run to that file. */
    public Run run(String windowTime, String fileName) {
        OffsetDateTime started = OffsetDateTime.now(clock).withNano(0);
        String executionId = "EXEC-REC-" + LocalDate.now(clock).format(DateTimeFormatter.BASIC_ISO_DATE) + "-" + windowTime.replace(":", "");
        appLog.log(ACH_RCV, "INFO", executionId + ": processing window " + windowTime + " started");
        List<ReceivingEngine.Result> files = engine.ingestAll(fileName);
        files.stream().filter(f -> f.processStatus().equals(ReceivingEngine.VALIDATED)).forEach(f -> processor.process(f.fileID()));
        appLog.log(ACH_RCV, "INFO", executionId + ": " + files.size() + " file(s) processed");
        return new Run(executionId, "COMPLETED", windowTime, started, files);
    }
}
