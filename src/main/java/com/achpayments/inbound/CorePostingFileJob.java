package com.achpayments.inbound;

import static com.achpayments.common.AppLogRepository.ACH_RCV;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.achpayments.common.AppLogRepository;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * Ba's blueprint, ACH Receiving Processing step 7: the end-of-day core posting file. Every entry that's ready to post
 * (RECEIVED, AUTO_CORRECTED or MEMO_POSTED, settled by the business date) becomes one line; the simulated core applies it:
 * the ledger balance always moves, and the available balance moves too unless step 6 already memo posted it.
 * One file per business date: a second run returns the first file's totals and writes nothing.
 *
 * <p>The file is pipe-delimited: {@code H|businessDate|postingJobID|recordCount}, one
 * {@code D|tranID|account|DR or CR|amount|coreTranCode|traceNo|effectiveDt} per entry, then
 * {@code T|recordCount|totalDebits|totalCredits}. Prenotes carry no money, so they're marked POSTED without a line.
 */
@Service
public class CorePostingFileJob {

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Result(String postingJobId, LocalDate businessDate, int totalRecordsPosted, BigDecimal totalDebitVolume,
            BigDecimal totalCreditVolume, String postingFileName, String status) {
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String READY = "READY_FOR_CORE_INGESTION";

    private final JdbcTemplate jdbc;
    private final AppLogRepository appLog;
    private final Clock clock;
    private final String recv;
    private final Path postingDir;

    public CorePostingFileJob(JdbcTemplate jdbc, AppLogRepository appLog, Clock clock, @Value("${app.db.receiving}") String recv,
            @Value("${app.core.posting-dir}") String postingDir) {
        this.jdbc = jdbc;
        this.appLog = appLog;
        this.clock = clock;
        this.recv = recv;
        this.postingDir = Path.of(postingDir);
    }

    @Transactional
    public Result generate(LocalDate businessDate) {
        List<Result> done = jdbc.query("SELECT postingJobID, businessDt, recordCount, totalDebitAmt, totalCreditAmt, fileName FROM " + recv
                + ".ACHRecvPostingJob WHERE businessDt = ?", (rs, i) -> new Result(rs.getString(1), rs.getDate(2).toLocalDate(),
                rs.getInt(3), rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getString(6), READY), businessDate);
        if (!done.isEmpty()) {
            return done.get(0);
        }
        String jobID = "JOB-POST-" + businessDate.format(DAY) + "-EOD";
        String fileName = "CORE_ACH_POSTING_" + businessDate.format(DAY) + ".DAT";
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT tranID, creditFlag, amount, COALESCE(correctedAcctNo, recvAcctID) AS acct, "
                + "coreTranCode, traceNo, effectiveDt, processStatus FROM " + recv + ".ACHRecvTransaction "
                + "WHERE processStatus IN ('RECEIVED', 'AUTO_CORRECTED', 'MEMO_POSTED') AND coreTranCode IS NOT NULL "
                + "AND COALESCE(settlementDt, effectiveDt) <= ? ORDER BY tranID", businessDate);

        List<String> details = new ArrayList<>();
        BigDecimal debits = BigDecimal.ZERO, credits = BigDecimal.ZERO;
        for (Map<String, Object> row : rows) {
            if (!ReceivedEntryProcessor.PRENOTE.equals(row.get("coreTranCode"))) {
                boolean credit = Boolean.TRUE.equals(row.get("creditFlag")); // TINYINT(1) reads as a Boolean
                BigDecimal amount = (BigDecimal) row.get("amount");
                BigDecimal signed = credit ? amount : amount.negate();
                boolean memoPosted = ReceivedEntryProcessor.MEMO_POSTED.equals(row.get("processStatus"));
                jdbc.update("UPDATE SimCoreAccount SET ledgerBal = ledgerBal + ?, availBal = availBal + ? WHERE acctNo = ?",
                        signed, memoPosted ? BigDecimal.ZERO : signed, row.get("acct"));
                details.add(String.join("|", "D", String.valueOf(row.get("tranID")), (String) row.get("acct"), credit ? "CR" : "DR",
                        amount.toPlainString(), (String) row.get("coreTranCode"), (String) row.get("traceNo"), String.valueOf(row.get("effectiveDt"))));
                if (credit) {
                    credits = credits.add(amount);
                } else {
                    debits = debits.add(amount);
                }
            }
            jdbc.update("UPDATE " + recv + ".ACHRecvTransaction SET processStatus = ?, postedTm = ? WHERE tranID = ?",
                    ReceivedEntryProcessor.POSTED, LocalDateTime.now(clock).withNano(0), row.get("tranID"));
        }
        jdbc.update("INSERT INTO " + recv + ".ACHRecvPostingJob (postingJobID, businessDt, fileName, recordCount, totalDebitAmt, totalCreditAmt) "
                + "VALUES (?, ?, ?, ?, ?, ?)", jobID, businessDate, fileName, details.size(), debits, credits);

        List<String> lines = new ArrayList<>();
        lines.add(String.join("|", "H", businessDate.toString(), jobID, String.valueOf(details.size())));
        lines.addAll(details);
        lines.add(String.join("|", "T", String.valueOf(details.size()), debits.toPlainString(), credits.toPlainString()));
        try { // last, so a file that can't be written rolls the postings back
            Files.createDirectories(postingDir);
            Files.write(postingDir.resolve(fileName), lines, StandardCharsets.US_ASCII);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        appLog.log(ACH_RCV, "INFO", "%s: %s posted %d entries, debits %s, credits %s".formatted(jobID, fileName, details.size(), debits, credits));
        return new Result(jobID, businessDate, details.size(), debits, credits, fileName, READY);
    }
}
