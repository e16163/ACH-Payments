package com.achpayments.nacha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.achpayments.nacha.AchFile.Batch;
import com.achpayments.nacha.AchFile.Entry;

class AchFileParserTest {

    @Test
    void parsesSampleFile() throws IOException {
        AchFile file = AchFileParser.parse(sample());

        assertEquals("256074974", file.header().immediateDestination());
        assertEquals("021000021", file.header().immediateOrigin());
        assertEquals("261103", file.header().fileCreationDate());
        assertEquals("NAVY FEDERAL CU", file.header().immediateDestinationName());
        assertEquals(2, file.batches().size());

        Batch payroll = file.batches().get(0);
        assertEquals("220", payroll.header().serviceClassCode());
        assertEquals("ACME PAYROLL INC", payroll.header().companyName());
        assertEquals("PPD", payroll.header().secCode());
        assertEquals("PAYROLL", payroll.header().companyEntryDescription());
        assertEquals("261104", payroll.header().effectiveEntryDate());
        assertEquals(1, payroll.header().batchNumber());
        assertEquals(2, payroll.entries().size());

        Entry jane = payroll.entries().get(0);
        assertEquals("22", jane.transactionCode());
        assertEquals("25607497", jane.receivingDfi());
        assertEquals("4", jane.checkDigit());
        assertEquals("1234567890", jane.accountNumber());
        assertEquals(250000, jane.amountCents());
        assertEquals("JANE DOE", jane.individualName());
        assertEquals("021000020000001", jane.traceNumber());
        assertTrue(jane.addenda().isEmpty());

        Entry john = payroll.entries().get(1);
        assertEquals("1", john.addendaIndicator());
        assertEquals(1, john.addenda().size());
        assertEquals("05", john.addenda().get(0).typeCode());
        assertTrue(john.addenda().get(0).content().startsWith("BONUS NOV 2026"));

        assertEquals(3, payroll.control().entryAddendaCount());
        assertEquals(51214994, payroll.control().entryHash());
        assertEquals(425050, payroll.control().totalCreditCents());

        Batch utility = file.batches().get(1);
        assertEquals("CCD", utility.header().secCode());
        assertEquals("27", utility.entries().get(0).transactionCode());
        assertEquals(8999, utility.control().totalDebitCents());

        assertEquals(2, file.control().batchCount());
        assertEquals(1, file.control().blockCount());
        assertEquals(4, file.control().entryAddendaCount());
        assertEquals(76822491, file.control().entryHash());
        assertEquals(8999, file.control().totalDebitCents());
        assertEquals(425050, file.control().totalCreditCents());
    }

    @Test
    void acceptsWindowsLineEndings() throws IOException {
        AchFile file = AchFileParser.parse(sample().replace("\n", "\r\n"));

        assertEquals(2, file.batches().size());
    }

    @Test
    void acceptsNineFillerAfterFileControl() throws IOException {
        AchFile file = AchFileParser.parse(sample() + "9".repeat(94) + "\n");

        assertEquals(2, file.batches().size());
    }

    @Test
    void rejectsRecordWithWrongLength() throws IOException {
        List<String> lines = sampleLines();
        lines.set(2, lines.get(2).substring(0, 93));

        AchParseException e = assertThrows(AchParseException.class, () -> parse(lines));

        assertEquals(3, e.lineNumber());
        assertTrue(e.getMessage().contains("93 characters"), e.getMessage());
    }

    @Test
    void rejectsNonNumericAmount() throws IOException {
        List<String> lines = sampleLines();
        String entry = lines.get(2);
        lines.set(2, entry.substring(0, 29) + "00002500X0" + entry.substring(39));

        AchParseException e = assertThrows(AchParseException.class, () -> parse(lines));

        assertEquals(3, e.lineNumber());
        assertTrue(e.getMessage().contains("positions 30-39 must be numeric"), e.getMessage());
    }

    @Test
    void rejectsEntryBeforeBatchHeader() throws IOException {
        List<String> lines = sampleLines();
        lines.remove(1); // drop the first Batch Header

        AchParseException e = assertThrows(AchParseException.class, () -> parse(lines));

        assertEquals(2, e.lineNumber());
        assertTrue(e.getMessage().contains("expected record type 5 (Batch Header) or record type 9 (File Control), found type 6"),
                e.getMessage());
    }

    @Test
    void rejectsAddendaWithoutEntry() throws IOException {
        List<String> lines = sampleLines();
        lines.remove(3); // drop both entries of the first batch,
        lines.remove(2); // so John's addenda directly follows the Batch Header

        AchParseException e = assertThrows(AchParseException.class, () -> parse(lines));

        assertEquals(3, e.lineNumber());
        assertTrue(e.getMessage().contains("expected record type 6 (Entry Detail) or record type 8 (Batch Control), found type 7"),
                e.getMessage());
    }

    @Test
    void rejectsMissingFileControl() throws IOException {
        List<String> lines = sampleLines();
        lines.remove(lines.size() - 1);

        AchParseException e = assertThrows(AchParseException.class, () -> parse(lines));

        assertTrue(e.getMessage().contains("File Control) but the file ended"), e.getMessage());
    }

    @Test
    void rejectsRecordsAfterFileControl() throws IOException {
        List<String> lines = sampleLines();
        lines.add(lines.get(2));

        AchParseException e = assertThrows(AchParseException.class, () -> parse(lines));

        assertEquals(11, e.lineNumber());
    }

    private static AchFile parse(List<String> lines) {
        return AchFileParser.parse(String.join("\n", lines) + "\n");
    }

    private static List<String> sampleLines() throws IOException {
        return new ArrayList<>(sample().lines().toList());
    }

    private static String sample() throws IOException {
        try (InputStream in = AchFileParserTest.class.getResourceAsStream("/nacha/sample-ppd-ccd.ach")) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
