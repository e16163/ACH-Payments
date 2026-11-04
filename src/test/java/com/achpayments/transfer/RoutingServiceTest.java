package com.achpayments.transfer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class RoutingServiceTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final RoutingService service = new RoutingService(jdbc);

    {
        when(jdbc.queryForList(anyString(), eq(String.class), eq("021000021"))).thenReturn(List.of("JPMorgan Chase Bank, N.A."));
        when(jdbc.queryForList(anyString(), eq(String.class), eq("011000028"))).thenReturn(List.of());
    }

    @Test
    void aValidNumberInTheDirectoryReturnsTheBankName() {
        assertEquals(Optional.of("JPMorgan Chase Bank, N.A."), service.bankName("021000021"));
    }

    @Test
    void badCheckDigitWrongLengthNonDigitsOrUnknownAreAllInvalid() {
        assertTrue(service.bankName("021000022").isEmpty());   // check digit
        assertTrue(service.bankName("02100002").isEmpty());    // 8 digits
        assertTrue(service.bankName("0210000211").isEmpty());  // 10 digits
        assertTrue(service.bankName("02100002A").isEmpty());   // non-digit
        assertTrue(service.bankName(" 21000021").isEmpty());
        assertTrue(service.bankName(null).isEmpty());
        assertTrue(service.bankName("011000028").isEmpty());   // passes the check digit, not in the directory
    }
}
