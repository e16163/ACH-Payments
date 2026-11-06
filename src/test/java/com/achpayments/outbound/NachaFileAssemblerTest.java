package com.achpayments.outbound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import com.achpayments.nacha.AchFile;
import com.achpayments.nacha.AchFile.BatchHeader;
import com.achpayments.nacha.AchFile.Entry;
import com.achpayments.nacha.AchFile.FileHeader;
import com.achpayments.nacha.AchFileParser;
import com.achpayments.nacha.AchFileValidator;
import com.achpayments.nacha.AchFileWriter;
import com.achpayments.outbound.NachaFileAssembler.BatchInput;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Steps 4-5. frontend/src/demo/nacha.test.ts checks the online demo against the same two files. */
class NachaFileAssemblerTest {

    private static String resource(String name) throws IOException {
        return new ClassPathResource("nacha/" + name).getContentAsString(StandardCharsets.US_ASCII);
    }

    private static AchFile fromFixture() throws IOException {
        JsonNode in = JsonMapper.builder().build().readTree(resource("eod-golden-input.json"));
        FileHeader header = new FileHeader("01", in.get("immediateDestination").asString(), in.get("immediateOrigin").asString(),
                in.get("fileCreationDate").asString(), in.get("fileCreationTime").asString(), in.get("fileIdModifier").asString(),
                "094", "10", "1", "FEDERAL RESERVE BANK", in.get("originName").asString(), "");
        List<BatchInput> batches = new ArrayList<>();
        for (JsonNode b : in.get("batches")) {
            List<Entry> entries = new ArrayList<>();
            for (JsonNode e : b.get("entries")) {
                String routing = e.get("routing").asString();
                entries.add(new Entry(e.get("tranCode").asString(), routing.substring(0, 8), routing.substring(8),
                        e.get("account").asString(), e.get("cents").asLong(), e.get("individualId").asString(),
                        e.get("name").asString(), e.get("discData").asString(), "0", e.get("trace").asString(), List.of()));
            }
            batches.add(new BatchInput(new BatchHeader("220", b.get("companyName").asString(), "", b.get("companyId").asString(),
                    b.get("secCode").asString(), b.get("entryDesc").asString(), "", b.get("effectiveDate").asString(), "", "1",
                    b.get("odfi").asString(), b.get("batchNumber").asLong()), entries));
        }
        return NachaFileAssembler.assemble(header, batches);
    }

    @Test
    void theFilePassesTheValidatorAndHasTheDesignRecordsAndPadding() throws IOException {
        String text = AchFileWriter.write(fromFixture());
        assertEquals(List.of(), AchFileValidator.validate(AchFileParser.parse(text)));
        String[] lines = text.split("\n");
        assertEquals(10, lines.length); // 1 + 2 batches of 5 + 3 records + 1 = 9, padded to a multiple of 10
        for (String line : lines) {
            assertEquals(94, line.length());
        }
        assertEquals("9".repeat(94), lines[9]);
        assertTrue(lines[0].startsWith("101 011000015 123456780"));
    }

    @Test
    void entryCountsTotalsAndHashAreRight() throws IOException {
        AchFile.FileControl c = fromFixture().control();
        assertEquals(2, c.batchCount());
        assertEquals(1, c.blockCount());
        assertEquals(3, c.entryAddendaCount());
        assertEquals(2100002L + 12100024L + 2600959L, c.entryHash());
        assertEquals(0, c.totalDebitCents());
        assertEquals(152551, c.totalCreditCents());
    }

    private static AchFile withEntries(int count) throws IOException {
        AchFile base = fromFixture();
        Entry e = base.batches().get(0).entries().get(0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            entries.add(new Entry(e.transactionCode(), e.receivingDfi(), e.checkDigit(), e.accountNumber(), e.amountCents(),
                    e.individualId(), e.individualName(), e.discretionaryData(), e.addendaIndicator(), "12345678" + "%07d".formatted(i), List.of()));
        }
        return NachaFileAssembler.assemble(base.header(), List.of(new BatchInput(base.batches().get(0).header(), entries)));
    }

    /** 1 + 1 + entries + 1 + 1 records: 6 entries fill a block exactly, the 7th starts another. */
    @Test
    void aFullBlockGetsNoPaddingAndOneMoreRecordStartsAnother() throws IOException {
        String[] full = AchFileWriter.write(withEntries(6)).split("\n");
        assertEquals(10, full.length);
        assertTrue(full[9].startsWith("9000001000001"));
        assertEquals(1, withEntries(6).control().blockCount());
        assertEquals(20, AchFileWriter.write(withEntries(7)).split("\n").length);
        assertEquals(2, withEntries(7).control().blockCount());
    }

    @Test
    void matchesTheGoldenFile() throws IOException {
        assertEquals(resource("eod-golden.ach"), AchFileWriter.write(fromFixture()));
    }
}
