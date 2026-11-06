package com.achpayments.nacha;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;

/** Date fields in NACHA records, mapped to Ba's DATE columns. */
public final class NachaDates {

    private static final DateTimeFormatter YYMMDD = DateTimeFormatter.ofPattern("uuMMdd").withResolverStyle(ResolverStyle.STRICT);

    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HHmm").withResolverStyle(ResolverStyle.STRICT);

    private NachaDates() {
    }

    /** A required YYMMDD field. @throws DateTimeParseException if it isn't a real date */
    public static LocalDate date(String raw) {
        return LocalDate.parse(raw, YYMMDD);
    }

    /** The File Creation Time. @throws DateTimeParseException if it isn't a real time */
    public static LocalTime time(String raw) {
        return LocalTime.parse(raw, HHMM);
    }

    /**
     * Settlement Date (batch header positions 76-78): the Fed's day of the year, or blank before the Fed fills it in.
     * Blank means the effective date. A day number earlier in the year than the effective date falls in the next year.
     *
     * @throws DateTimeException if the day doesn't exist in that year
     */
    public static LocalDate settlementDate(String julian, LocalDate effective) {
        if (julian == null || julian.isBlank()) {
            return effective;
        }
        LocalDate date = effective.withDayOfYear(Integer.parseInt(julian));
        return date.isBefore(effective) ? date.plusYears(1) : date;
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
