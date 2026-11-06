package com.achpayments.nacha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

class NachaDatesTest {

    private static final LocalDate EFFECTIVE = LocalDate.of(2026, 11, 6);

    @Test
    void aRealYymmddDateIsKept() {
        assertEquals(LocalDate.parse("2026-11-26"), NachaDates.descriptiveDate("261126"));
    }

    @Test
    void freeTextBlankOrImpossibleDatesBecomeNull() {
        assertNull(NachaDates.descriptiveDate("NOV 26"));
        assertNull(NachaDates.descriptiveDate("      "));
        assertNull(NachaDates.descriptiveDate(null));
        assertNull(NachaDates.descriptiveDate("261131")); // November 31st
        assertNull(NachaDates.descriptiveDate("2611"));
    }

    @Test
    void readsRequiredDatesAndTimes() {
        assertEquals(EFFECTIVE, NachaDates.date("261106"));
        assertEquals(LocalTime.of(8, 30), NachaDates.time("0830"));
        assertThrows(DateTimeException.class, () -> NachaDates.date("261306"));
        assertThrows(DateTimeException.class, () -> NachaDates.time("2500"));
    }

    @Test
    void aBlankSettlementDateIsTheEffectiveDate() {
        assertEquals(EFFECTIVE, NachaDates.settlementDate("", EFFECTIVE));
    }

    @Test
    void readsTheFedsDayOfTheYear() {
        assertEquals(LocalDate.of(2026, 11, 9), NachaDates.settlementDate("313", EFFECTIVE));
    }

    @Test
    void anEarlyDayNumberFallsInTheNextYear() {
        assertEquals(LocalDate.of(2027, 1, 4), NachaDates.settlementDate("004", LocalDate.of(2026, 12, 31)));
    }

    @Test
    void aDayThatDoesNotExistIsRefused() {
        assertThrows(DateTimeException.class, () -> NachaDates.settlementDate("400", EFFECTIVE));
    }
}
