package com.achpayments.param;

import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Checks a parameter before it is saved (UC-SYS-PARAM-01 step 4 and EX-01).
 * The React form runs the same rules (frontend/src/params/validation.ts) so the
 * admin sees errors before Save; this class makes sure a direct API call can't skip them.
 */
public final class ParamValidator {

    public static final Set<String> TYPES = Set.of("STRING", "INTEGER", "DECIMAL", "BOOLEAN", "JSON");

    private static final Pattern INTEGER = Pattern.compile("-?\\d+");
    private static final Pattern DECIMAL = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ParamValidator() {
    }

    /** Returns the first problem found, or null if the parameter is valid. Lengths match the AppParam columns. */
    public static String validate(String systemName, String paramName, String paramDesc, String type, String value) {
        if (systemName == null || !ParamApps.APPS.containsKey(systemName)) return "Unknown app: " + systemName + ".";
        if (isBlank(paramName) || paramName.length() > 50) return "Parameter name is required (max 50 characters).";
        if (paramDesc != null && paramDesc.length() > 255) return "Description must be at most 255 characters.";
        if (type == null || !TYPES.contains(type)) return "Type must be one of " + String.join(", ", TYPES.stream().sorted().toList()) + ".";
        if (value == null || value.isEmpty()) return "Value is required.";
        if (value.length() > 500) return "Value must be at most 500 characters.";
        return validateValue(type, value);
    }

    static String validateValue(String type, String value) {
        return switch (type) {
            case "INTEGER" -> INTEGER.matcher(value).matches() ? null : "Value must be a valid integer.";
            case "DECIMAL" -> DECIMAL.matcher(value).matches() ? null : "Value must be a valid decimal number.";
            case "BOOLEAN" -> value.equals("TRUE") || value.equals("FALSE") ? null : "Value must be TRUE or FALSE.";
            case "JSON" -> isJson(value) ? null : "Value must be valid JSON.";
            default -> null; // STRING: anything goes
        };
    }

    private static boolean isJson(String value) {
        try {
            JSON.readTree(value);
            return true;
        } catch (JacksonException e) {
            return false;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
