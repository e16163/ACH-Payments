package com.achpayments.nacha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDate;

import org.junit.jupiter.api.Test;

class NachaDatesTest {

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
}
