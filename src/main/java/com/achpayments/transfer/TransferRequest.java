package com.achpayments.transfer;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * What the member enters on the External ACH Transfer screen (UC-ACH-ORG-01 steps 2-4). There is deliberately no
 * memberNo or lastModBy: the server takes both from the login. The recipient is one of the member's registered external
 * bank accounts, picked by its externalAccountId as in Ba's outbound transfer API (Ba, 2026-11-07).
 */
public record TransferRequest(
        String origAcctNo,
        String externalAccountId,
        BigDecimal amount,
        LocalDate effectiveDt,
        String frequency,
        Integer transferCount,
        String memo) {
}
