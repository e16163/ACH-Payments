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
        List<String> problems = new ArrayList<>();
        long fileHash = 0, fileDebits = 0, fileCredits = 0, fileEntryAddenda = 0, records = 2;
        long previousBatchNumber = 0;

        for (Batch batch : file.batches()) {
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
                } else {
                    credits += e.amountCents();
                }
                entryAddenda += 1 + e.addenda().size();
            }
            hash %= HASH_MODULUS;

            compare(problems, where + "entry/addenda count", c.entryAddendaCount(), entryAddenda);
            compare(problems, where + "entry hash", c.entryHash(), hash);
            compare(problems, where + "total debits", c.totalDebitCents(), debits);
            compare(problems, where + "total credits", c.totalCreditCents(), credits);
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

        AchFile.FileControl fc = file.control();
        compare(problems, "File Control batch count", fc.batchCount(), file.batches().size());
        compare(problems, "File Control entry/addenda count", fc.entryAddendaCount(), fileEntryAddenda);
        compare(problems, "File Control entry hash", fc.entryHash(), fileHash % HASH_MODULUS);
        compare(problems, "File Control total debits", fc.totalDebitCents(), fileDebits);
        compare(problems, "File Control total credits", fc.totalCreditCents(), fileCredits);
        compare(problems, "File Control block count", fc.blockCount(), (records + 9) / 10);
        return problems;
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

    private static void compare(List<String> problems, String what, long stated, long actual) {
        if (stated != actual) {
            problems.add(what + " says " + stated + " but the entries add up to " + actual);
        }
    }

    private static boolean isNumeric(String s) {
        return !s.isEmpty() && s.chars().allMatch(ch -> ch >= '0' && ch <= '9');
    }
}
