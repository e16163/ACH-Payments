package com.achpayments.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ParamValidatorTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INTEGER | 3",
            "INTEGER | -12",
            "DECIMAL | 1000000.00",
            "DECIMAL | 5",
            "BOOLEAN | TRUE",
            "BOOLEAN | FALSE",
            "JSON    | {\"windows\":[\"10:30\",\"14:45\"]}",
            "STRING  | 17:00:00",
    })
    void acceptsValidValues(String type, String value) {
        assertNull(ParamValidator.validate("ACH_ORIG", "P", null, type, value));
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "INTEGER | five      | Value must be a valid integer.",
            "INTEGER | 1.5       | Value must be a valid integer.",
            "DECIMAL | 1,000.00  | Value must be a valid decimal number.",
            "DECIMAL | .5        | Value must be a valid decimal number.",
            "BOOLEAN | true      | Value must be TRUE or FALSE.",
            "BOOLEAN | YES       | Value must be TRUE or FALSE.",
            "JSON    | {windows: | Value must be valid JSON.",
            "JSON    | {} extra  | Value must be valid JSON.",
    })
    void rejectsValuesThatDontMatchType(String type, String value, String expected) {
        assertEquals(expected, ParamValidator.validate("ACH_ORIG", "P", null, type, value));
    }

    @Test
    void rejectsUnknownTypeAndMissingOrOversizedFields() {
        assertEquals("Type must be one of BOOLEAN, DECIMAL, INTEGER, JSON, STRING.",
                ParamValidator.validate("ACH_ORIG", "P", null, "DATE", "x"));
        assertEquals("Value is required.", ParamValidator.validate("ACH_ORIG", "P", null, "STRING", ""));
        assertEquals("Value must be at most 500 characters.",
                ParamValidator.validate("ACH_ORIG", "P", null, "STRING", "x".repeat(501)));
        assertNull(ParamValidator.validate("ACH_ORIG", "P", null, "STRING", "x".repeat(500)));
        assertEquals("Unknown app: ACH_FOO.", ParamValidator.validate("ACH_FOO", "P", null, "STRING", "x"));
        assertEquals("Unknown app: null.", ParamValidator.validate(null, "P", null, "STRING", "x"));
        assertEquals("Parameter name is required (max 50 characters).",
                ParamValidator.validate("ACH_ORIG", " ", null, "STRING", "x"));
        assertEquals("Description must be at most 255 characters.",
                ParamValidator.validate("ACH_ORIG", "P", "x".repeat(256), "STRING", "x"));
    }

    @Test
    void acceptsEveryAppFromTheDesignList() {
        for (String app : ParamApps.APPS.keySet()) {
            assertNull(ParamValidator.validate(app, "credit_limit", null, "DECIMAL", "5000.00"), app);
        }
    }
}
