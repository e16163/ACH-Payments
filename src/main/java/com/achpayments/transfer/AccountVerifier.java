package com.achpayments.transfer;

/**
 * UC-ACH-ORG-01 EX-03 (the designer, 2026-11-03, USE-CASES T3): does the external account exist, and can it take ACH
 * transfers? The real implementation will call the Plaid API.
 */
public interface AccountVerifier {

    boolean isEligibleForAch(String routingNo, String acctNo, String acctType);
}
