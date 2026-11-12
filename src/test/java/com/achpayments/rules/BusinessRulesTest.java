package com.achpayments.rules;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BusinessRulesTest {

    /** Ba's sample: If companyID = "23416789" && creditIndicator = true set earlyPosting = true. */
    private static final String BA = "{\"when\": {\"all\": [{\"field\": \"companyID\", \"op\": \"eq\", \"value\": \"23416789\"}, "
            + "{\"field\": \"creditIndicator\", \"op\": \"eq\", \"value\": true}]}, \"then\": [{\"set\": \"earlyPosting\", \"value\": true}]}";

    private static boolean matches(String json, Map<String, Object> facts) {
        return BusinessRules.matches(BusinessRules.parse(json, "ACH_RCV").get("when"), facts);
    }

    private static String rule(String quantifier, String conditions) {
        return "{\"when\": {\"" + quantifier + "\": [" + conditions + "]}, \"then\": [{\"set\": \"x\", \"value\": 1}]}";
    }

    @Test
    void basSampleMatchesACreditFromTheCompanyOnly() {
        assertTrue(matches(BA, Map.of("companyID", "23416789", "creditIndicator", true)));
        assertFalse(matches(BA, Map.of("companyID", "23416789", "creditIndicator", false))); // a debit
        assertFalse(matches(BA, Map.of("companyID", "1900483920", "creditIndicator", true)));
        assertFalse(matches(BA, Map.of("creditIndicator", true))); // no companyID
    }

    @Test
    void eachOp() {
        Map<String, Object> facts = Map.of("amount", new BigDecimal("10000.00"), "stdEntryCode", "PPD");
        assertTrue(matches(rule("all", "{\"field\": \"amount\", \"op\": \"gte\", \"value\": 10000}"), facts));
        assertFalse(matches(rule("all", "{\"field\": \"amount\", \"op\": \"gt\", \"value\": 10000}"), facts));
        assertTrue(matches(rule("all", "{\"field\": \"amount\", \"op\": \"lte\", \"value\": 10000}"), facts));
        assertFalse(matches(rule("all", "{\"field\": \"amount\", \"op\": \"lt\", \"value\": 10000}"), facts));
        assertTrue(matches(rule("all", "{\"field\": \"amount\", \"op\": \"eq\", \"value\": 10000}"), facts)); // 10000.00 = 10000
        assertTrue(matches(rule("all", "{\"field\": \"stdEntryCode\", \"op\": \"in\", \"value\": [\"PPD\", \"CCD\"]}"), facts));
        assertTrue(matches(rule("all", "{\"field\": \"stdEntryCode\", \"op\": \"ne\", \"value\": \"WEB\"}"), facts));
        assertFalse(matches(rule("all", "{\"field\": \"stdEntryCode\", \"op\": \"gt\", \"value\": 1}"), facts)); // not a number
        assertTrue(matches(rule("any", "{\"field\": \"stdEntryCode\", \"op\": \"eq\", \"value\": \"WEB\"}, "
                + "{\"field\": \"amount\", \"op\": \"gt\", \"value\": 5}"), facts));
        assertFalse(matches(rule("any", "{\"field\": \"stdEntryCode\", \"op\": \"eq\", \"value\": \"WEB\"}"), facts));
    }

    @Test
    void aBadRuleIsRefusedWithWhatIsWrong() {
        assertEquals("The JSON rule isn't valid JSON.", bad("{not json", "ACH_RCV"));
        assertTrue(bad("{\"then\": []}", "ACH_RCV").startsWith("\"when\" needs"));
        assertTrue(bad(rule("all", "{\"field\": \"colour\", \"op\": \"eq\", \"value\": 1}"), "ACH_RCV").startsWith("Unknown field \"colour\""));
        assertTrue(bad(rule("all", "{\"field\": \"amount\", \"op\": \"~\", \"value\": 1}"), "ACH_RCV").startsWith("Unknown op \"~\""));
        assertTrue(bad(rule("all", "{\"field\": \"amount\", \"op\": \"in\", \"value\": 1}"), "ACH_RCV").startsWith("\"in\" takes a list"));
        assertTrue(bad("{\"when\": {\"all\": [{\"field\": \"amount\", \"op\": \"eq\", \"value\": 1}]}}", "ACH_RCV").startsWith("\"then\" needs"));
        // an app whose rules don't run yet can test any field
        BusinessRules.parse(rule("all", "{\"field\": \"colour\", \"op\": \"eq\", \"value\": 1}"), "ACH_ORIG");
    }

    private static String bad(String json, String systemName) {
        return assertThrows(IllegalArgumentException.class, () -> BusinessRules.parse(json, systemName)).getMessage();
    }
}
