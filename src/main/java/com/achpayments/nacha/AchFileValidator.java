package com.achpayments.nacha;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.achpayments.nacha.AchFile.Batch;
import com.achpayments.nacha.AchFile.BatchControl;
import com.achpayments.nacha.AchFile.BatchHeader;
import com.achpayments.nacha.AchFile.Entry;

/**
 * Checks the content rules of a parsed NACHA file (docs/PLAN.md section 3). The parser already checks
 * record length, record order and numeric fields. Returns every problem found; an empty list means valid.
 */
public final class AchFileValidator {

    private static final long HASH_MODULUS = 10_000_000_000L; // entry hash keeps the last 10 digits

    /** Transaction codes in scope: checking (2x) and savings (3x) credits, debits, prenotes and returns/NOCs. */
    private static final Set<String> TRANSACTION_CODES = Set.of(
            "21", "22", "23", "26", "27", "28", "31", "32", "33", "36", "37", "38");

    private AchFileValidator() {
    }

    public static List<String> validate(AchFile file) {
        Check check = new Check();
        file.batches().forEach(check::add);
        return check.finish(file.control());
    }

    /** The same checks one batch at a time, for a file too big to hold in memory (UC-ACH-RCV-01). */
    public static final class Check {

        private final List<String> problems = new ArrayList<>();
        private boolean outOfBalance;
        private int batchCount;
        private long fileHash, fileDebits, fileCredits, fileEntryAddenda, records = 2, previousBatchNumber, debitCount, creditCount;

        /** Whether a stated count, hash or total differs from the entries (UC-ACH-RCV-01 EX-02). */
        public boolean outOfBalance() {
            return outOfBalance;
        }

        public long debitCount() {
            return debitCount;
        }

        public long creditCount() {
            return creditCount;
        }

        public long totalDebitCents() {
            return fileDebits;
        }

        public long totalCreditCents() {
            return fileCredits;
        }

        public void add(Batch batch) {
            batchCount++;
            BatchHeader h = batch.header();
            BatchControl c = batch.control();
            String where = "Batch " + h.batchNumber() + ": ";

            if (h.batchNumber() <= previousBatchNumber) {
                problems.add(where + "batch numbers must ascend (previous was " + previousBatchNumber + ")");
            }
            previousBatchNumber = h.batchNumber();

            long hash = 0, debits = 0, credits = 0, entryAddenda = 0;
            for (Entry e : batch.entries()) {
                String at = where + "entry " + e.traceNumber() + ": ";
                if (!isValidRouting(e.receivingDfi() + e.checkDigit())) {
                    problems.add(at + "routing number " + e.receivingDfi() + e.checkDigit() + " fails the check digit");
                }
                transactionCodeProblem(h, e).ifPresent(p -> problems.add(at + p));
                if (isNumeric(e.receivingDfi())) {
                    hash += Long.parseLong(e.receivingDfi());
                }
                if (isDebit(e.transactionCode())) {
                    debits += e.amountCents();
                    debitCount++;
                } else {
                    credits += e.amountCents();
                    creditCount++;
                }
                entryAddenda += 1 + e.addenda().size();
            }
            hash %= HASH_MODULUS;

            compare(where + "entry/addenda count", c.entryAddendaCount(), entryAddenda);
            compare(where + "entry hash", c.entryHash(), hash);
            compare(where + "total debits", c.totalDebitCents(), debits);
            compare(where + "total credits", c.totalCreditCents(), credits);
            if (!c.serviceClassCode().equals(h.serviceClassCode())) {
                problems.add(where + "Batch Control service class " + c.serviceClassCode()
                        + " doesn't match the Batch Header's " + h.serviceClassCode());
            }
            if (!c.companyId().equals(h.companyId())) {
                problems.add(where + "Batch Control company ID doesn't match the Batch Header");
            }
            if (c.batchNumber() != h.batchNumber()) {
                problems.add(where + "Batch Control batch number " + c.batchNumber() + " doesn't match");
            }

            fileHash += hash;
            fileDebits += debits;
            fileCredits += credits;
            fileEntryAddenda += entryAddenda;
            records += 2 + entryAddenda;
        }

        public List<String> finish(AchFile.FileControl fc) {
            compare("File Control batch count", fc.batchCount(), batchCount);
            compare("File Control entry/addenda count", fc.entryAddendaCount(), fileEntryAddenda);
            compare("File Control entry hash", fc.entryHash(), fileHash % HASH_MODULUS);
            compare("File Control total debits", fc.totalDebitCents(), fileDebits);
            compare("File Control total credits", fc.totalCreditCents(), fileCredits);
            compare("File Control block count", fc.blockCount(), (records + 9) / 10);
            return problems;
        }

        private void compare(String what, long stated, long actual) {
            if (stated != actual) {
                outOfBalance = true;
                problems.add(what + " says " + stated + " but the entries add up to " + actual);
            }
        }
    }

    /** ABA routing check: weights 3-7-1 repeated; the weighted sum of all 9 digits is a multiple of 10. */
    public static boolean isValidRouting(String routing) {
        if (routing == null || routing.length() != 9 || !isNumeric(routing)) {
            return false;
        }
        int[] weights = {3, 7, 1, 3, 7, 1, 3, 7, 1};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += (routing.charAt(i) - '0') * weights[i];
        }
        return sum % 10 == 0;
    }

    /** Second digit 6-9 = debit; 1-4 = credit. */
    public static boolean isDebit(String transactionCode) {
        return transactionCode.length() == 2 && transactionCode.charAt(1) >= '6';
    }

    private static Optional<String> transactionCodeProblem(BatchHeader h, Entry e) {
        String code = e.transactionCode();
        if (!TRANSACTION_CODES.contains(code)) {
            return Optional.of("unknown transaction code " + code);
        }
        boolean debit = isDebit(code);
        if (h.serviceClassCode().equals("220") && debit) {
            return Optional.of("debit code " + code + " in a credits-only (220) batch");
        }
        if (h.serviceClassCode().equals("225") && !debit) {
            return Optional.of("credit code " + code + " in a debits-only (225) batch");
        }
        // NOCs (SEC code COR) use only the return/NOC codes; returns use them too, under the original SEC code.
        boolean returnOrNoc = code.endsWith("1") || code.endsWith("6");
        if (h.secCode().equals("COR") && !returnOrNoc) {
            return Optional.of("transaction code " + code + " isn't allowed with SEC code COR");
        }
        if (h.secCode().equals("TEL") && !debit) {
            return Optional.of("TEL entries must be debits, found " + code);
        }
        return Optional.empty();
    }

    private static boolean isNumeric(String s) {
        return !s.isEmpty() && s.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }
}
