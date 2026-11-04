package com.achpayments.nacha;

import com.achpayments.nacha.AchFile.Addenda;
import com.achpayments.nacha.AchFile.Batch;
import com.achpayments.nacha.AchFile.BatchControl;
import com.achpayments.nacha.AchFile.BatchHeader;
import com.achpayments.nacha.AchFile.Entry;
import com.achpayments.nacha.AchFile.FileControl;
import com.achpayments.nacha.AchFile.FileHeader;

/**
 * Writes an {@link AchFile} as NACHA text: 94-character records, "\n" line endings, padded with
 * all-9 records to a multiple of 10 (the blocking factor). The mirror image of {@link AchFileParser}.
 * Alphanumeric fields are left-justified and space-filled; numeric fields are right-justified and zero-filled.
 */
public final class AchFileWriter {

    private static final int BLOCKING_FACTOR = 10;

    private final StringBuilder out = new StringBuilder();
    private int records;

    private AchFileWriter() {
    }

    public static String write(AchFile file) {
        AchFileWriter w = new AchFileWriter();
        w.fileHeader(file.header());
        for (Batch b : file.batches()) {
            w.batchHeader(b.header());
            for (Entry e : b.entries()) {
                w.entry(e);
            }
            w.batchControl(b.control());
        }
        w.fileControl(file.control());
        while (w.records % BLOCKING_FACTOR != 0) {
            w.record("9".repeat(94));
        }
        return w.out.toString();
    }

    private void fileHeader(FileHeader h) {
        record("1" + a(h.priorityCode(), 2) + right(h.immediateDestination(), 10) + right(h.immediateOrigin(), 10)
                + a(h.fileCreationDate(), 6) + a(h.fileCreationTime(), 4) + a(h.fileIdModifier(), 1)
                + a(h.recordSize(), 3) + a(h.blockingFactor(), 2) + a(h.formatCode(), 1)
                + a(h.immediateDestinationName(), 23) + a(h.immediateOriginName(), 23) + a(h.referenceCode(), 8));
    }

    private void batchHeader(BatchHeader h) {
        record("5" + a(h.serviceClassCode(), 3) + a(h.companyName(), 16) + a(h.companyDiscretionaryData(), 20)
                + a(h.companyId(), 10) + a(h.secCode(), 3) + a(h.companyEntryDescription(), 10)
                + a(h.companyDescriptiveDate(), 6) + a(h.effectiveEntryDate(), 6) + a(h.settlementDate(), 3)
                + a(h.originatorStatusCode(), 1) + a(h.originatingDfi(), 8) + n(h.batchNumber(), 7));
    }

    private void entry(Entry e) {
        record("6" + a(e.transactionCode(), 2) + a(e.receivingDfi(), 8) + a(e.checkDigit(), 1)
                + a(e.accountNumber(), 17) + n(e.amountCents(), 10) + a(e.individualId(), 15)
                + a(e.individualName(), 22) + a(e.discretionaryData(), 2) + a(e.addendaIndicator(), 1)
                + a(e.traceNumber(), 15));
        for (Addenda ad : e.addenda()) {
            record("7" + a(ad.typeCode(), 2) + a(ad.content(), 91));
        }
    }

    private void batchControl(BatchControl c) {
        record("8" + a(c.serviceClassCode(), 3) + n(c.entryAddendaCount(), 6) + n(c.entryHash(), 10)
                + n(c.totalDebitCents(), 12) + n(c.totalCreditCents(), 12) + a(c.companyId(), 10)
                + a(c.messageAuthenticationCode(), 19) + " ".repeat(6) + a(c.originatingDfi(), 8)
                + n(c.batchNumber(), 7));
    }

    private void fileControl(FileControl c) {
        record("9" + n(c.batchCount(), 6) + n(c.blockCount(), 6) + n(c.entryAddendaCount(), 8)
                + n(c.entryHash(), 10) + n(c.totalDebitCents(), 12) + n(c.totalCreditCents(), 12) + " ".repeat(39));
    }

    private void record(String text) {
        if (text.length() != 94) {
            throw new IllegalStateException("record is " + text.length() + " characters: " + text);
        }
        out.append(text).append('\n');
        records++;
    }

    /** Alphanumeric: left-justified, space-filled. Rejects values that don't fit rather than cutting them off. */
    private static String a(String value, int width) {
        String v = value == null ? "" : value;
        if (v.length() > width) {
            throw new IllegalArgumentException("'" + v + "' is longer than " + width + " characters");
        }
        return v + " ".repeat(width - v.length());
    }

    /** Right-justified, space-filled: the Immediate Destination/Origin (" 256074974"). */
    private static String right(String value, int width) {
        String v = a(value, width).strip();
        return " ".repeat(width - v.length()) + v;
    }

    /** Numeric: right-justified, zero-filled. */
    private static String n(long value, int width) {
        String v = Long.toString(value);
        if (value < 0 || v.length() > width) {
            throw new IllegalArgumentException(value + " doesn't fit in " + width + " digits");
        }
        return "0".repeat(width - v.length()) + v;
    }
}
