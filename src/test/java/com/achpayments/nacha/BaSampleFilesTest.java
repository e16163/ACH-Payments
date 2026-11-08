package com.achpayments.nacha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.achpayments.nacha.AchFile.Batch;
import com.achpayments.nacha.AchFile.Entry;

/**
 * Ba's two sample files from "NACHA ACH File Format Specification 01 - Overview", rebuilt as real 94-character files
 * (Word had collapsed the spacing). Every value is Ba's; the fixes are listed in docs/BLUEPRINT-PLAN.md section 2.
 * Receiving reads a file batch by batch with stream(); Origination writes one with AchFileWriter.
 */
class BaSampleFilesTest {

    private static final String PAYROLL = "/nacha/ba-sample-1-payroll-b2b.ach";
    private static final String RETAIL = "/nacha/ba-sample-2-retail-web-ccd.ach";

    @Test
    void bothSamplesPassEveryValidationRule() throws IOException {
        assertEquals(List.of(), AchFileValidator.validate(AchFileParser.parse(read(PAYROLL))));
        assertEquals(List.of(), AchFileValidator.validate(AchFileParser.parse(read(RETAIL))));
    }

    @Test
    void payrollSampleBatchByBatch() throws IOException {
        AchFile file = AchFileParser.parse(read(PAYROLL));

        assertEquals("021000021", file.header().immediateDestination());
        assertEquals("1987654321", file.header().immediateOrigin());
        assertEquals("FEDACH CLEARING CTR", file.header().immediateDestinationName());
        assertEquals("ACME CORP ODFI", file.header().immediateOriginName());
        assertEquals("A", file.header().fileIdModifier());

        // company name, SEC code, entry description, entries, total credits in cents (all credits, code 22)
        assertBatch(file.batches().get(0), "ACME PAYROLL", "PPD", "PAIDROLL", 10, 0, 1_675_000);
        assertBatch(file.batches().get(1), "ACME VENDORS", "CCD", "APAYABLES", 10, 0, 6_750_000);
        assertBatch(file.batches().get(2), "ACME BONUSES", "PPD", "BONUSES", 10, 0, 2_670_000);
        assertBatch(file.batches().get(3), "ACME REIMBURSE", "PPD", "REIMBURS", 10, 0, 675_000);
        assertBatch(file.batches().get(4), "ACME DIVIDENDS", "CCD", "SHAREHOLD", 10, 0, 1_175_000);

        Entry first = file.batches().get(0).entries().get(0);
        assertEquals("22", first.transactionCode());
        assertEquals("02100002", first.receivingDfi());
        assertEquals("1", first.checkDigit());
        assertEquals("987654321019", first.accountNumber());
        assertEquals(150_000, first.amountCents());
        assertEquals("EMP000000001", first.individualId());
        assertEquals("JOHN DOE", first.individualName());
        assertEquals("198765430000001", first.traceNumber());

        Entry last = file.batches().get(4).entries().get(9);
        assertEquals("INVESTOR TEN", last.individualName());
        assertEquals(135_000, last.amountCents());
        assertEquals("198765430000050", last.traceNumber());

        assertEquals(5, file.control().batchCount());
        assertEquals(50, file.control().entryAddendaCount());
        assertEquals(7, file.control().blockCount());
        assertEquals(12_945_000, file.control().totalCreditCents());
    }

    @Test
    void retailSampleBatchByBatch() throws IOException {
        AchFile file = AchFileParser.parse(read(RETAIL));

        assertEquals("B", file.header().fileIdModifier());
        assertBatch(file.batches().get(0), "GLOBAL WEB PAY", "WEB", "PAYMENTS", 10, 94_500, 0);
        assertBatch(file.batches().get(1), "GLOBAL SUBSCRIP", "WEB", "SUBJECT", 10, 19_990, 0);
        assertBatch(file.batches().get(2), "GLOBAL B2B AR", "CCD", "AR", 10, 0, 6_750_000);
        assertBatch(file.batches().get(3), "GLOBAL REFUND", "CCD", "PAYOUT", 10, 0, 167_000);
        assertBatch(file.batches().get(4), "GLOBAL DIVIDEND", "CCD", "DIVIDEND", 10, 0, 267_000);

        Entry alice = file.batches().get(0).entries().get(0);
        assertEquals("27", alice.transactionCode()); // checking debit
        assertEquals("031000037", alice.receivingDfi() + alice.checkDigit());
        assertEquals("ALICE JOHNSON", alice.individualName());
        assertEquals(5_000, alice.amountCents());
        assertEquals("298765430000001", alice.traceNumber());

        assertEquals(114_490, file.control().totalDebitCents());
        assertEquals(7_184_000, file.control().totalCreditCents());
    }

    /** Receiving: the file is read one batch at a time and each batch handed over with its transactions. */
    @Test
    void streamingHandsOverTheSameBatchesAsAFullParse() throws IOException {
        for (String sample : List.of(PAYROLL, RETAIL)) {
            List<Batch> streamed = new ArrayList<>();
            AchFileParser.Summary summary = AchFileParser.stream(
                    new BufferedReader(new StringReader(read(sample))), h -> { }, streamed::add);

            AchFile parsed = AchFileParser.parse(read(sample));
            assertEquals(parsed.batches(), streamed, sample);
            assertEquals(parsed.control(), summary.control(), sample);
            assertEquals(70, summary.recordCount(), sample);
        }
    }

    /** Origination: writing the parsed file gives back the same bytes, so the writer builds Ba's layout exactly. */
    @Test
    void writingTheParsedFileGivesBackTheSameBytes() throws IOException {
        for (String sample : List.of(PAYROLL, RETAIL)) {
            assertEquals(read(sample), AchFileWriter.write(AchFileParser.parse(read(sample))), sample);
        }
    }

    /** The positions in Ba's "02 - Details" tables for record types 6, 8 and 9 (they match NACHA). */
    @Test
    void readsEachFieldFromTheDetailsSpecPositions() throws IOException {
        List<String> lines = read(PAYROLL).lines().toList();
        String entry = lines.get(2), batchControl = lines.get(12), fileControl = lines.get(61);
        Entry e = AchFileParser.parse(read(PAYROLL)).batches().get(0).entries().get(0);

        assertEquals(field(entry, 2, 3), e.transactionCode());
        assertEquals(field(entry, 4, 11), e.receivingDfi());
        assertEquals(field(entry, 12, 12), e.checkDigit());
        assertEquals(field(entry, 13, 29).trim(), e.accountNumber());
        assertEquals(Long.parseLong(field(entry, 30, 39)), e.amountCents());
        assertEquals(field(entry, 40, 54).trim(), e.individualId());
        assertEquals(field(entry, 55, 76).trim(), e.individualName());
        assertEquals(field(entry, 79, 79), e.addendaIndicator());
        assertEquals(field(entry, 80, 94), e.traceNumber());

        AchFile.BatchControl bc = AchFileParser.parse(read(PAYROLL)).batches().get(0).control();
        assertTrue(batchControl.startsWith("8"));
        assertEquals(field(batchControl, 2, 4), bc.serviceClassCode());
        assertEquals(Long.parseLong(field(batchControl, 5, 10)), bc.entryAddendaCount());
        assertEquals(Long.parseLong(field(batchControl, 11, 20)), bc.entryHash());
        assertEquals(Long.parseLong(field(batchControl, 21, 32)), bc.totalDebitCents());
        assertEquals(Long.parseLong(field(batchControl, 33, 44)), bc.totalCreditCents());
        assertEquals(field(batchControl, 45, 54), bc.companyId());
        assertEquals(field(batchControl, 80, 87), bc.originatingDfi());
        assertEquals(Long.parseLong(field(batchControl, 88, 94)), bc.batchNumber());

        AchFile.FileControl fc = AchFileParser.parse(read(PAYROLL)).control();
        assertTrue(fileControl.startsWith("9"));
        assertEquals(Long.parseLong(field(fileControl, 2, 7)), fc.batchCount());
        assertEquals(Long.parseLong(field(fileControl, 8, 13)), fc.blockCount());
        assertEquals(Long.parseLong(field(fileControl, 14, 21)), fc.entryAddendaCount());
        assertEquals(Long.parseLong(field(fileControl, 22, 31)), fc.entryHash());
        assertEquals(Long.parseLong(field(fileControl, 32, 43)), fc.totalDebitCents());
        assertEquals(Long.parseLong(field(fileControl, 44, 55)), fc.totalCreditCents());
    }

    private static void assertBatch(Batch b, String company, String sec, String description, int entries,
                                    long debitCents, long creditCents) {
        assertEquals(company, b.header().companyName());
        assertEquals(sec, b.header().secCode());
        assertEquals(description, b.header().companyEntryDescription());
        assertEquals(entries, b.entries().size());
        assertEquals(debitCents, b.control().totalDebitCents());
        assertEquals(creditCents, b.control().totalCreditCents());
    }

    /** 1-based, inclusive positions, as in the spec tables. */
    private static String field(String record, int from, int to) {
        return record.substring(from - 1, to);
    }

    private static String read(String resource) throws IOException {
        try (InputStream in = BaSampleFilesTest.class.getResourceAsStream(resource)) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
