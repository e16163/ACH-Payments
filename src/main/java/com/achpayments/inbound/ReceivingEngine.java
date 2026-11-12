package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
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

    /** PASSED: checked by the validate endpoint, entries not saved yet. VALIDATED: checked and its entries saved. */
    public static final String PASSED = "PASSED", VALIDATED = "VALIDATED", SIGNATURE_FAILED = "REJECTED_SIG_FAIL", OUT_OF_BALANCE = "OUT_OF_BALANCE",
            DUPLICATE = "DUPLICATE", REJECTED = "REJECTED";
    private static final int BLOCK_SIZE = 10;

    public record Result(String fileID, String fileName, String processStatus, String message) {
    }

    /** Blueprint step 2. */
    public record Validation(String fileID, String processStatus, int totalRecords, boolean blockCountValid, boolean hashValid,
            List<String> errors) {
    }

    /** What reading a file found: an empty problem list means it passed. */
    private record Checked(int records, List<String> problems, String failStatus) {
        void throwIfFailed() {
            if (failStatus != null) {
                throw new FileRejected(failStatus, String.join("; ", problems));
            }
        }
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

    public List<Result> ingestAll() {
        return ingestAll(null);
    }

    /** Every waiting file, or only the one named (blueprint step 1's file_name). */
    public synchronized List<Result> ingestAll(String onlyFileName) {
        List<Result> results = new ArrayList<>();
        for (Path file : waitingFiles()) {
            if (onlyFileName != null && !file.getFileName().toString().equals(onlyFileName)) {
                continue;
            }
            try {
                results.add(ingest(file));
            } catch (IOException | RuntimeException e) {
                appLog.log(ACH_RCV, "FATAL", "Inbound file " + file.getFileName() + " stopped and stays in the inbound folder: " + e, e);
            }
        }
        return results;
    }

    /** The files in fed/inbound that the next window will pick up. */
    public List<String> waiting() {
        return waitingFiles().stream().map(p -> p.getFileName().toString()).toList();
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
        appLog.log(ACH_RCV, "INFO", fileID + " In-Flight: received " + fileName);
        store.saveRawText(fileID, Files.readString(file, StandardCharsets.US_ASCII)); // blueprint step 2: kept for audit

        if (!signature.isValid(file)) { // EX-01
            store.markFailed(fileID, SIGNATURE_FAILED);
            String message = "Security Operations alert: " + fileName + " failed signature verification and was quarantined.";
            appLog.log(ACH_RCV, "FATAL", fileID + " " + message);
            moveTo(quarantine, fileID, file);
            return new Result(fileID, fileName, SIGNATURE_FAILED, message);
        }
        appLog.log(ACH_RCV, "INFO", fileID + " Decrypted: signature verified");

        FileHeader[] header = new FileHeader[1];
        try {
            transaction.executeWithoutResult(status -> {
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.US_ASCII)) {
                    read(reader, fileID, header, true).throwIfFailed();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (FileRejected e) {
            return reject(file, fileID, header[0], e.processStatus, e);
        } catch (AchParseException | DateTimeException | NumberFormatException e) {
            return reject(file, fileID, header[0], REJECTED, e);
        }
        store.saveHeader(fileID, header[0]);
        appLog.log(ACH_RCV, "INFO", fileID + " Validated: entries saved");
        moveTo(archive, fileID, file);
        events.publishEvent(new AchFileIngestedEvent(fileID));
        return new Result(fileID, fileName, VALIDATED, fileName + " was validated and saved.");
    }

    /**
     * Blueprint step 2 (validate endpoint): checks a file sent as text and keeps the text; no entries are saved yet.
     * A file that fails gets the same status as one from the inbound folder.
     */
    public synchronized Validation validate(String rawText, boolean storeRawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new IllegalArgumentException("raw_file_content is empty.");
        }
        String fileID = store.insertFile("API upload");
        if (storeRawText) {
            store.saveRawText(fileID, rawText);
        }
        FileHeader[] header = new FileHeader[1];
        Checked checked;
        try {
            checked = read(new BufferedReader(new StringReader(rawText)), fileID, header, false);
        } catch (FileRejected e) {
            checked = new Checked(0, List.of(e.getMessage()), e.processStatus);
        } catch (AchParseException | DateTimeException | NumberFormatException e) {
            checked = new Checked(0, List.of(e.getMessage()), REJECTED);
        }
        String status = checked.failStatus() == null ? PASSED : checked.failStatus();
        store.markFailed(fileID, status); // sets processStatus, PASSED included
        if (header[0] != null) {
            store.saveHeader(fileID, header[0]);
        }
        appLog.log(ACH_RCV, status.equals(PASSED) ? "INFO" : "WARN", fileID + " validate: " + status
                + (checked.problems().isEmpty() ? "" : " " + String.join("; ", checked.problems())));
        boolean hashValid = checked.problems().stream().noneMatch(p -> p.contains("entry hash"));
        boolean blocksValid = checked.problems().stream().noneMatch(p -> p.contains("block count") || p.contains("multiple of"));
        return new Validation(fileID, status, checked.records(), blocksValid, hashValid, checked.problems());
    }

    /** Blueprint step 3 (extract endpoint): saves the batches and entries of a file the validate step passed. */
    public synchronized void extract(String fileID) {
        String status = store.processStatus(fileID);
        if (!PASSED.equals(status)) {
            throw new IllegalArgumentException("File " + fileID + " is " + status + "; only a PASSED file can be extracted.");
        }
        String raw = store.rawText(fileID);
        if (raw == null) {
            throw new IllegalArgumentException("File " + fileID + " was validated without store_raw_text, so there is nothing to extract.");
        }
        FileHeader[] header = new FileHeader[1];
        transaction.executeWithoutResult(s -> read(new BufferedReader(new StringReader(raw)), fileID, header, true).throwIfFailed());
        appLog.log(ACH_RCV, "INFO", fileID + " Validated: entries saved");
        events.publishEvent(new AchFileIngestedEvent(fileID));
    }

    /**
     * Steps 2-6: one pass over the file. With save, each batch's entries are inserted as it's read and the file is marked
     * VALIDATED if nothing is wrong; the caller's transaction rolls them back otherwise.
     */
    private Checked read(BufferedReader reader, String fileID, FileHeader[] header, boolean save) {
        AchFileValidator.Check check = new AchFileValidator.Check();
        long[] serial = {save ? store.firstSerial() : 0};
        AchFileParser.Summary summary = AchFileParser.stream(reader, h -> {
            checkHeader(h);
            header[0] = h;
            if (store.isDuplicate(fileID, h)) { // EX-03
                throw new FileRejected(DUPLICATE, "Same File Creation Date, Time and File ID Modifier as a file already received");
            }
        }, batch -> {
            check.add(batch);
            if (save) {
                serial[0] = store.insertBatch(fileID, batch, serial[0]);
            }
        });
        List<String> problems = check.finish(summary.control());
        if (!problems.isEmpty()) {
            return new Checked(summary.recordCount(), problems, check.outOfBalance() ? OUT_OF_BALANCE : REJECTED);
        }
        if (summary.recordCount() % BLOCK_SIZE != 0) {
            return new Checked(summary.recordCount(),
                    List.of("The file has " + summary.recordCount() + " records, not a multiple of " + BLOCK_SIZE), REJECTED);
        }
        if (save) {
            store.markValidated(fileID, check);
        }
        return new Checked(summary.recordCount(), List.of(), null);
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
        appLog.log(ACH_RCV, status.equals(DUPLICATE) ? "WARN" : "ERROR", "Operations alert: " + fileID + " " + message, status.equals(OUT_OF_BALANCE) ? e : null);
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
}
