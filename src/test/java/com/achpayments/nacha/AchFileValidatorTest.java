package com.achpayments.nacha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * One passing case (the sample file) and one failing case per rule. Each failing case changes exactly one
 * field of the sample. Sample line numbers (0-based): 0 file header, 1 batch 1 header, 2-3 entries,
 * 4 addenda, 5 batch 1 control, 6 batch 2 header, 7 entry, 8 batch 2 control, 9 file control.
 */
class AchFileValidatorTest {

    @Test
    void sampleFileIsValid() throws IOException {
        assertEquals(List.of(), validate(sampleLines()));
    }

    @Test
    void routingCheckDigit() {
        assertTrue(AchFileValidator.isValidRouting("256074974"));
        assertTrue(AchFileValidator.isValidRouting("021000021"));
        assertFalse(AchFileValidator.isValidRouting("051000031")); // Ba's sample routing number (USE-CASES T9)
        assertFalse(AchFileValidator.isValidRouting("25607497"));
        assertFalse(AchFileValidator.isValidRouting("25607497X"));
    }

    @Test
    void entryWithBadCheckDigitIsReported() throws IOException {
        assertFileHas(change(2, 12, "5"), "routing number 256074975 fails the check digit");
    }

    @Test
    void batchEntryHashMustMatch() throws IOException {
        assertFileHas(change(5, 11, "0051214995"), "Batch 1: entry hash says 51214995 but the entries add up to 51214994");
    }

    @Test
    void batchTotalsMustMatch() throws IOException {
        List<String> problems = validate(change(2, 30, "0000250001"));
        assertProblem(problems, "Batch 1: total credits says 425050 but the entries add up to 425051");
        assertProblem(problems, "File Control total credits says 425050 but the entries add up to 425051");
    }

    @Test
    void batchDebitTotalMustMatch() throws IOException {
        assertFileHas(change(8, 21, "000000008998"), "Batch 2: total debits says 8998 but the entries add up to 8999");
    }

    @Test
    void batchEntryAddendaCountMustMatch() throws IOException {
        assertFileHas(change(5, 5, "000002"), "Batch 1: entry/addenda count says 2 but the entries add up to 3");
    }

    @Test
    void batchControlMustMatchItsHeader() throws IOException {
        assertFileHas(change(5, 2, "200"), "Batch Control service class 200 doesn't match the Batch Header's 220");
        assertFileHas(change(5, 45, "1111111111"), "Batch Control company ID doesn't match the Batch Header");
        assertFileHas(change(5, 88, "0000009"), "Batch Control batch number 9 doesn't match");
    }

    @Test
    void batchNumbersMustAscend() throws IOException {
        List<String> lines = change(6, 88, "0000001");
        lines.set(8, lines.get(8).substring(0, 87) + "0000001");
        assertProblem(validate(lines), "Batch 1: batch numbers must ascend (previous was 1)");
    }

    @Test
    void fileControlMustMatchTheBatches() throws IOException {
        assertFileHas(change(9, 2, "000003"), "File Control batch count says 3 but the entries add up to 2");
        assertFileHas(change(9, 14, "00000005"), "File Control entry/addenda count says 5 but the entries add up to 4");
        assertFileHas(change(9, 22, "0076822490"), "File Control entry hash says 76822490 but the entries add up to 76822491");
        assertFileHas(change(9, 8, "000002"), "File Control block count says 2 but the entries add up to 1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"25", "42"})
    void unknownTransactionCodeIsReported(String code) throws IOException {
        assertFileHas(change(2, 2, code), "unknown transaction code " + code);
    }

    @Test
    void transactionCodeMustFitTheServiceClassAndSecCode() throws IOException {
        assertFileHas(change(2, 2, "27"), "debit code 27 in a credits-only (220) batch");
        assertFileHas(change(7, 2, "22"), "credit code 22 in a debits-only (225) batch");

        List<String> cor = change(6, 51, "COR");
        assertProblem(validate(cor), "transaction code 27 isn't allowed with SEC code COR");

        List<String> tel = change(1, 2, "200");
        tel.set(1, tel.get(1).substring(0, 50) + "TEL" + tel.get(1).substring(53));
        tel.set(5, tel.get(5).substring(0, 1) + "200" + tel.get(5).substring(4));
        assertProblem(validate(tel), "TEL entries must be debits, found 22");
    }

    @Test
    void returnCodesAreAllowedUnderTheOriginalSecCode() throws IOException {
        // A return of the CCD debit (27 -> 26) keeps SEC code CCD; only the totals are affected, not the code rule.
        List<String> problems = validate(change(7, 2, "26"));
        assertFalse(problems.stream().anyMatch(p -> p.contains("isn't allowed")), problems.toString());
    }

    // --- helpers ---

    private static List<String> sampleLines() throws IOException {
        return new ArrayList<>(AchFileWriterTest.sample().lines().toList());
    }

    /** Overwrites the sample's record at {@code line} starting at 1-based {@code position}. */
    private static List<String> change(int line, int position, String text) throws IOException {
        List<String> lines = sampleLines();
        String l = lines.get(line);
        lines.set(line, l.substring(0, position - 1) + text + l.substring(position - 1 + text.length()));
        return lines;
    }

    private static List<String> validate(List<String> lines) {
        return AchFileValidator.validate(AchFileParser.parse(String.join("\n", lines) + "\n"));
    }

    /** Validates the changed file lines and expects a problem containing {@code expected}. */
    private static void assertFileHas(List<String> lines, String expected) {
        assertProblem(validate(lines), expected);
    }

    private static void assertProblem(List<String> problems, String expected) {
        assertTrue(problems.stream().anyMatch(p -> p.contains(expected)), "expected '" + expected + "' in " + problems);
    }
}
