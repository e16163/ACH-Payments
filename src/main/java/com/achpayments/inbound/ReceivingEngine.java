package com.achpayments.inbound;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.achpayments.common.AppLogRepository;
import com.achpayments.nacha.AchFile.FileHeader;
import com.achpayments.nacha.AchFileParser;
import com.achpayments.nacha.AchFileValidator;
import com.achpayments.nacha.AchParseException;
import com.achpayments.nacha.NachaDates;
import com.achpayments.param.ParamCache;

/**
 * UC-ACH-RCV-01: ingests every file waiting in fed/inbound. Decryption is simulated (PLAN A2, A6). Records are read one
 * batch at a time and saved in one transaction, so a failure at the Type 9 record leaves no entries behind. Synchronized,
 * because the 6 AM job and the Receive button must not ingest the same file twice.
 */
@Service
public class ReceivingEngine {

    public static final String VALIDATED = "VALIDATED", SIGNATURE_FAILED = "REJECTED_SIG_FAIL", OUT_OF_BALANCE = "OUT_OF_BALANCE",
            DUPLICATE = "DUPLICATE", REJECTED = "REJECTED";
    private static final int BLOCK_SIZE = 10;

    public record Result(String fileID, String fileName, String processStatus, String message) {
    }

    /** Stops a file. Anything else that goes wrong (the database is down, say) leaves it in fed/inbound to try again. */
    private static class FileRejected extends RuntimeException {
        final String processStatus;

        FileRejected(String processStatus, String message) {
            super(message);
            this.processStatus = processStatus;
        }
    }

    private final ReceivingFileStore store;
    private final FileSignature signature;
    private final TransactionTemplate transaction;
    private final ApplicationEventPublisher events;
    private final ParamCache params;
    private final AppLogRepository appLog;
    private final Path inbound, quarantine, archive, outbound;

    public ReceivingEngine(ReceivingFileStore store, FileSignature signature, TransactionTemplate transaction,
            ApplicationEventPublisher events, ParamCache params, AppLogRepository appLog,
            @Value("${app.fed.inbound-dir}") String inbound, @Value("${app.fed.outbound-dir}") String outbound) {
        this.store = store;
        this.signature = signature;
        this.transaction = transaction;
        this.events = events;
        this.params = params;
        this.appLog = appLog;
        this.inbound = Path.of(inbound);
        this.quarantine = this.inbound.resolveSibling("quarantine");
        this.archive = this.inbound.resolveSibling("archive");
        this.outbound = Path.of(outbound);
    }

    public synchronized List<Result> ingestAll() {
        List<Result> results = new ArrayList<>();
        for (Path file : waitingFiles()) {
            try {
                results.add(ingest(file));
            } catch (IOException | RuntimeException e) {
                appLog.log("FATAL", "Inbound file " + file.getFileName() + " stopped and stays in the inbound folder: " + e, stackTrace(e));
            }
        }
        return results;
    }

    private TreeSet<Path> waitingFiles() {
        TreeSet<Path> files = new TreeSet<>();
        if (Files.isDirectory(inbound)) {
            try (DirectoryStream<Path> dir = Files.newDirectoryStream(inbound, "*.ach")) {
                dir.forEach(files::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private Result ingest(Path file) throws IOException {
        String fileName = file.getFileName().toString();
        String fileID = store.insertFile(fileName);
        appLog.log("INFO", fileID + " In-Flight: received " + fileName, null);

        if (!signature.isValid(file)) { // EX-01
            store.markFailed(fileID, SIGNATURE_FAILED);
            String message = "Security Operations alert: " + fileName + " failed signature verification and was quarantined.";
            appLog.log("FATAL", fileID + " " + message, null);
            moveTo(quarantine, fileID, file);
            return new Result(fileID, fileName, SIGNATURE_FAILED, message);
        }
        appLog.log("INFO", fileID + " Decrypted: signature verified", null);

        FileHeader[] header = new FileHeader[1];
        try {
            transaction.executeWithoutResult(status -> save(file, fileID, header));
        } catch (FileRejected e) {
            return reject(file, fileID, header[0], e.processStatus, e);
        } catch (AchParseException | DateTimeException | NumberFormatException e) {
            return reject(file, fileID, header[0], REJECTED, e);
        }
        store.saveHeader(fileID, header[0]);
        appLog.log("INFO", fileID + " Validated: entries saved", null);
        moveTo(archive, fileID, file);
        events.publishEvent(new AchFileIngestedEvent(fileID));
        return new Result(fileID, fileName, VALIDATED, fileName + " was validated and saved.");
    }

    /** Steps 2-6. An exception here rolls back everything this file wrote. */
    private void save(Path file, String fileID, FileHeader[] header) {
        AchFileValidator.Check check = new AchFileValidator.Check();
        long[] serial = {store.firstSerial()};
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
            AchFileParser.Summary summary = AchFileParser.stream(reader, h -> {
                checkHeader(h);
                header[0] = h;
                if (store.isDuplicate(fileID, h)) { // EX-03
                    throw new FileRejected(DUPLICATE, "Same File Creation Date, Time and File ID Modifier as a file already received");
                }
            }, batch -> {
                check.add(batch);
                serial[0] = store.insertBatch(fileID, batch, serial[0]);
            });
            List<String> problems = check.finish(summary.control());
            if (!problems.isEmpty()) {
                throw new FileRejected(check.outOfBalance() ? OUT_OF_BALANCE : REJECTED, String.join("; ", problems));
            }
            if (summary.recordCount() % BLOCK_SIZE != 0) {
                throw new FileRejected(REJECTED, "The file has " + summary.recordCount() + " records, not a multiple of " + BLOCK_SIZE);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        store.markValidated(fileID, check);
    }

    /** Step 2. */
    private void checkHeader(FileHeader h) {
        String rdfi = params.get("ACH_RCV", "RDFI_ROUTING_NO").orElseThrow(() -> new IllegalStateException("Missing parameter ACH_RCV/RDFI_ROUTING_NO"));
        if (!rdfi.equals(h.immediateDestination())) {
            throw new FileRejected(REJECTED, "The file is addressed to " + h.immediateDestination() + ", not to our routing number " + rdfi);
        }
        if (!h.fileIdModifier().matches("[A-Z0-9]")) {
            throw new FileRejected(REJECTED, "File ID Modifier '" + h.fileIdModifier() + "' isn't one letter or digit");
        }
        NachaDates.date(h.fileCreationDate());
        NachaDates.time(h.fileCreationTime());
    }

    private Result reject(Path file, String fileID, FileHeader header, String status, Exception e) throws IOException {
        store.markFailed(fileID, status);
        if (header != null) {
            store.saveHeader(fileID, header);
        }
        String message = file.getFileName() + " " + status + ": " + e.getMessage();
        if (status.equals(OUT_OF_BALANCE)) { // EX-02
            Files.createDirectories(outbound);
            Files.writeString(outbound.resolve(fileID + ".rejection.txt"), "FedACH file rejection: " + message + System.lineSeparator());
        }
        appLog.log(status.equals(DUPLICATE) ? "WARN" : "ERROR", "Operations alert: " + fileID + " " + message, status.equals(OUT_OF_BALANCE) ? stackTrace(e) : null);
        moveTo(quarantine, fileID, file);
        return new Result(fileID, file.getFileName().toString(), status, message);
    }

    /** Keeps the file: names start with the fileID, so the same name received again replaces nothing. */
    private void moveTo(Path folder, String fileID, Path file) throws IOException {
        Files.createDirectories(folder);
        Files.move(file, folder.resolve(fileID + "-" + file.getFileName()), StandardCopyOption.ATOMIC_MOVE);
        Path sig = FileSignature.signatureOf(file);
        if (Files.exists(sig)) {
            Files.move(sig, folder.resolve(fileID + "-" + sig.getFileName()), StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static String stackTrace(Exception e) {
        StringWriter out = new StringWriter();
        e.printStackTrace(new PrintWriter(out));
        return out.toString();
    }
}
