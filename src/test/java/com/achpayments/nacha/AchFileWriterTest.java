package com.achpayments.nacha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.achpayments.nacha.AchFile.Batch;

class AchFileWriterTest {

    @Test
    void roundTripIsByteIdentical() throws IOException {
        String original = sample();

        assertEquals(original, AchFileWriter.write(AchFileParser.parse(original)));
    }

    @Test
    void padsWithNineRecordsToAMultipleOfTen() throws IOException {
        AchFile file = AchFileParser.parse(sample());
        AchFile oneBatch = new AchFile(file.header(), List.of(file.batches().get(1)), file.control());

        List<String> lines = AchFileWriter.write(oneBatch).lines().toList();

        assertEquals(10, lines.size()); // 1 header + 4 batch records + 1 control = 6, padded to 10
        assertEquals("9".repeat(94), lines.get(9));
        assertTrue(lines.stream().allMatch(l -> l.length() == 94));
    }

    @Test
    void rejectsAFieldThatDoesNotFitInsteadOfCuttingItOff() throws IOException {
        AchFile file = AchFileParser.parse(sample());
        Batch b = file.batches().get(0);
        AchFile.BatchHeader h = b.header();
        AchFile.BatchHeader tooLong = new AchFile.BatchHeader(h.serviceClassCode(), "A COMPANY NAME LONGER THAN 16",
                h.companyDiscretionaryData(), h.companyId(), h.secCode(), h.companyEntryDescription(),
                h.companyDescriptiveDate(), h.effectiveEntryDate(), h.settlementDate(), h.originatorStatusCode(),
                h.originatingDfi(), h.batchNumber());
        AchFile bad = new AchFile(file.header(), List.of(new Batch(tooLong, b.entries(), b.control())), file.control());

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> AchFileWriter.write(bad));
        assertTrue(e.getMessage().contains("longer than 16"), e.getMessage());
    }

    static String sample() throws IOException {
        try (InputStream in = AchFileWriterTest.class.getResourceAsStream("/nacha/sample-ppd-ccd.ach")) {
            return new String(in.readAllBytes(), StandardCharsets.US_ASCII);
        }
    }
}
