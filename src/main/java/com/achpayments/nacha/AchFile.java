package com.achpayments.nacha;

import java.util.List;

/**
 * A parsed NACHA file. Field names follow the Nacha record layouts.
 * Codes, identifiers and dates are kept as strings (trimmed); amounts, counts and hashes are longs.
 * Amounts are in cents.
 */
public record AchFile(FileHeader header, List<Batch> batches, FileControl control) {

    /** Record type 1. */
    public record FileHeader(
            String priorityCode,
            String immediateDestination,
            String immediateOrigin,
            String fileCreationDate,   // YYMMDD
            String fileCreationTime,   // HHMM
            String fileIdModifier,
            String recordSize,
            String blockingFactor,
            String formatCode,
            String immediateDestinationName,
            String immediateOriginName,
            String referenceCode) {
    }

    public record Batch(BatchHeader header, List<Entry> entries, BatchControl control) {
    }

    /** Record type 5. */
    public record BatchHeader(
            String serviceClassCode,
            String companyName,
            String companyDiscretionaryData,
            String companyId,
            String secCode,
            String companyEntryDescription,
            String companyDescriptiveDate,
            String effectiveEntryDate, // YYMMDD
            String settlementDate,     // Julian day, filled in by the ACH Operator
            String originatorStatusCode,
            String originatingDfi,
            long batchNumber) {
    }

    /** Record type 6, plus any type 7 addenda that follow it. */
    public record Entry(
            String transactionCode,
            String receivingDfi,
            String checkDigit,
            String accountNumber,
            long amountCents,
            String individualId,
            String individualName,
            String discretionaryData,
            String addendaIndicator,
            String traceNumber,
            List<Addenda> addenda) {
    }

    /**
     * Record type 7. The layout after the type code depends on it (05 payment info, 98 NOC, 99 return),
     * so positions 4-94 are kept as-is.
     */
    public record Addenda(String typeCode, String content) {
    }

    /** Record type 8. */
    public record BatchControl(
            String serviceClassCode,
            long entryAddendaCount,
            long entryHash,
            long totalDebitCents,
            long totalCreditCents,
            String companyId,
            String messageAuthenticationCode,
            String originatingDfi,
            long batchNumber) {
    }

    /** Record type 9. */
    public record FileControl(
            long batchCount,
            long blockCount,
            long entryAddendaCount,
            long entryHash,
            long totalDebitCents,
            long totalCreditCents) {
    }
}
