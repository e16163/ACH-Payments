package com.achpayments.transfer;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaidAccountVerifierTest {

    @Test
    void placeholderTreatsEveryAccountAsEligible() {
        assertTrue(new PlaidAccountVerifier().isEligibleForAch("021000021", "123456789", "CHECKING"));
    }
}
