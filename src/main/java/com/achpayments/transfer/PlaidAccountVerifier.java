package com.achpayments.transfer;

import org.springframework.stereotype.Component;

/** Placeholder until we have Plaid access (the designer, 2026-11-03): every account counts as verified. */
@Component
public class PlaidAccountVerifier implements AccountVerifier {

    @Override
    public boolean isEligibleForAch(String routingNo, String acctNo, String acctType) {
        // TODO: call the Plaid API (account exists + ACH-eligible) once we have access.
        return true;
    }
}
