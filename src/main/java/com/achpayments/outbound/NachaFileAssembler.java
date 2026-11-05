package com.achpayments.outbound;

import java.util.ArrayList;
import java.util.List;

import com.achpayments.nacha.AchFile;
import com.achpayments.nacha.AchFile.Batch;
import com.achpayments.nacha.AchFile.BatchControl;
import com.achpayments.nacha.AchFile.BatchHeader;
import com.achpayments.nacha.AchFile.Entry;
import com.achpayments.nacha.AchFile.FileControl;
import com.achpayments.nacha.AchFile.FileHeader;
import com.achpayments.nacha.AchFileValidator;

/** UC-ACH-EOD-01 steps 4-5: adds the Batch Control and File Control records (counts, totals, entry hash). */
public final class NachaFileAssembler {

    private static final long HASH_MODULUS = 10_000_000_000L;

    public record BatchInput(BatchHeader header, List<Entry> entries) {
    }

    private NachaFileAssembler() {
    }

    public static AchFile assemble(FileHeader header, List<BatchInput> inputs) {
        List<Batch> batches = new ArrayList<>();
        long fileHash = 0, fileDebits = 0, fileCredits = 0, fileEntryAddenda = 0, records = 2;
        for (BatchInput in : inputs) {
            long hash = 0, debits = 0, credits = 0, entryAddenda = 0;
            for (Entry e : in.entries()) {
                hash += Long.parseLong(e.receivingDfi());
                if (AchFileValidator.isDebit(e.transactionCode())) {
                    debits += e.amountCents();
                } else {
                    credits += e.amountCents();
                }
                entryAddenda += 1 + e.addenda().size();
            }
            hash %= HASH_MODULUS;
            BatchHeader h = in.header();
            batches.add(new Batch(h, in.entries(), new BatchControl(h.serviceClassCode(), entryAddenda, hash, debits, credits,
                    h.companyId(), "", h.originatingDfi(), h.batchNumber())));
            fileHash += hash;
            fileDebits += debits;
            fileCredits += credits;
            fileEntryAddenda += entryAddenda;
            records += 2 + entryAddenda;
        }
        return new AchFile(header, batches, new FileControl(batches.size(), (records + 9) / 10, fileEntryAddenda,
                fileHash % HASH_MODULUS, fileDebits, fileCredits));
    }
}
