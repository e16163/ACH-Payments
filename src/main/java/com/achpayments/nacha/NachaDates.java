package com.achpayments.nacha;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** Date fields in NACHA records, mapped to Ba's DATE columns. */
public final class NachaDates {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("uuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private NachaDates() {
    }

    /**
     * Company Descriptive Date (batch header positions 64-69) is free text in NACHA, e.g. "NOV 26" or blank.
     * Ba's {@code descriptiveDt} column is a DATE (DB-MODEL F1 kept as he wrote it), so only a real YYMMDD date
     * is stored; anything else becomes NULL, which his column allows. The raw text stays in the file itself.
     */
    public static LocalDate descriptiveDate(String raw) {
        if (raw == null || !raw.matches("\\d{6}")) {
            return null;
        }
        try {
            return LocalDate.parse(raw, YYMMDD);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
