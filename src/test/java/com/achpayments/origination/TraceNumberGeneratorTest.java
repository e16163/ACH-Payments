package com.achpayments.origination;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class TraceNumberGeneratorTest {

    private TraceNumberGenerator withHighestStored(String max) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(String.class), any(Object[].class))).thenReturn(max);
        return new TraceNumberGenerator(jdbc, "orig");
    }

    @Test
    void startsAtOneUsingTheFirst8DigitsOfTheOdfiRouting() {
        TraceNumberGenerator gen = withHighestStored(null);
        assertEquals("123456780000001", gen.next("123456780"));
        assertEquals("123456780000002", gen.next("123456780"));
    }

    @Test
    void rejectsABadRoutingNumber() {
        TraceNumberGenerator gen = withHighestStored(null);
        assertThrows(IllegalArgumentException.class, () -> gen.next("12345678"));
        assertThrows(IllegalArgumentException.class, () -> gen.next("12345678X"));
    }

    @Test
    void stopsInsteadOfWrappingWhenTheSequenceRunsOut() {
        TraceNumberGenerator gen = withHighestStored("123456789999999");
        assertThrows(IllegalStateException.class, () -> gen.next("123456780"));
    }
}
