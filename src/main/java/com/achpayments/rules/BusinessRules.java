package com.achpayments.rules;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.achpayments.common.AppLogRepository;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ba's ACH Business Rules (2026-11-12), stored in ACHBusinessRule. A rule's JSON is
 * {"when": {"all"|"any": [{"field", "op", "value"}]}, "then": [{"set", "value"}]}: when its conditions hold for an entry's facts,
 * each "then" sets a result, e.g. earlyPosting = true.
 */
@Service
public class BusinessRules {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> OPS = Set.of("eq", "ne", "in", "gt", "gte", "lt", "lte");

    /** The facts each app's rules can test. Only ACH_RCV runs its rules today (receiving step 6); other apps aren't checked. */
    static final Map<String, Set<String>> FIELDS = Map.of("ACH_RCV",
            Set.of("companyID", "companyName", "creditIndicator", "amount", "stdEntryCode", "tranCode"));

    private final JdbcTemplate jdbc;
    private final AppLogRepository appLog;

    public BusinessRules(JdbcTemplate jdbc, AppLogRepository appLog) {
        this.jdbc = jdbc;
        this.appLog = appLog;
    }

    /** The app's rules, by name. */
    public List<Map<String, Object>> rules(String systemName) {
        return jdbc.queryForList("SELECT ruleID, systemName, ruleName, ruleDesc, pseudoCode, ruleJson, lastModTm, lastModBy "
                + "FROM ACHBusinessRule WHERE systemName = ? ORDER BY ruleName", systemName);
    }

    /** One rule, by ID. */
    public Map<String, Object> rule(long ruleID) {
        return jdbc.queryForList("SELECT ruleID, systemName, ruleName, ruleDesc, pseudoCode, ruleJson, lastModTm, lastModBy "
                + "FROM ACHBusinessRule WHERE ruleID = ?", ruleID).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No such business rule " + ruleID + "."));
    }

    /** Adds a rule (ruleID null) or changes one, after checking it. user comes from the login session. */
    public Map<String, Object> save(Long ruleID, String systemName, String ruleName, String ruleDesc, String pseudoCode, String ruleJson,
            String user) {
        String app = ruleID == null ? systemName : (String) rule(ruleID).get("systemName"); // a rule stays in its app
        if (app == null || !com.achpayments.param.ParamApps.APPS.containsKey(app)) {
            throw new IllegalArgumentException("Choose an app.");
        }
        if (ruleName == null || ruleName.isBlank() || ruleName.length() > 100) {
            throw new IllegalArgumentException("The rule name is required, up to 100 characters.");
        }
        if (pseudoCode == null || pseudoCode.isBlank() || pseudoCode.length() > 2000) {
            throw new IllegalArgumentException("The pseudo code is required, up to 2000 characters.");
        }
        if (ruleDesc != null && ruleDesc.length() > 500) {
            throw new IllegalArgumentException("The business description can be up to 500 characters.");
        }
        parse(ruleJson == null ? "" : ruleJson, app);
        String desc = ruleDesc == null || ruleDesc.isBlank() ? null : ruleDesc.trim();
        try {
            if (ruleID == null) {
                var key = new org.springframework.jdbc.support.GeneratedKeyHolder();
                jdbc.update(c -> {
                    var ps = c.prepareStatement("INSERT INTO ACHBusinessRule (systemName, ruleName, ruleDesc, pseudoCode, ruleJson, lastModBy) "
                            + "VALUES (?, ?, ?, ?, ?, ?)", java.sql.Statement.RETURN_GENERATED_KEYS);
                    Object[] v = {app, ruleName.trim(), desc, pseudoCode, ruleJson, user};
                    for (int i = 0; i < v.length; i++) {
                        ps.setObject(i + 1, v[i]);
                    }
                    return ps;
                }, key);
                ruleID = key.getKey().longValue();
            } else {
                jdbc.update("UPDATE ACHBusinessRule SET ruleName = ?, ruleDesc = ?, pseudoCode = ?, ruleJson = ?, lastModBy = ?, "
                        + "lastModTm = CURRENT_TIMESTAMP WHERE ruleID = ?", ruleName.trim(), desc, pseudoCode, ruleJson, user, ruleID);
            }
        } catch (org.springframework.dao.DuplicateKeyException e) {
            throw new IllegalArgumentException(app + " already has a rule named \"" + ruleName.trim() + "\".");
        }
        appLog.log(app, "INFO", "User '%s' saved business rule %s/%s".formatted(user, app, ruleName.trim()));
        return rule(ruleID);
    }

    /** The app's rules, ready to run. One that fails the check (edited outside the screen) is logged and skipped. */
    public List<JsonNode> load(String systemName) {
        List<JsonNode> out = new java.util.ArrayList<>();
        for (Map<String, Object> r : rules(systemName)) {
            try {
                out.add(parse(String.valueOf(r.get("ruleJson")), systemName));
            } catch (IllegalArgumentException e) {
                appLog.log(systemName, "FATAL", "Business rule '%s' skipped: %s".formatted(r.get("ruleName"), e.getMessage()));
            }
        }
        return out;
    }

    /** What the rules set for these facts; a later rule (by name) wins over an earlier one for the same result. */
    public static Map<String, Object> results(List<JsonNode> rules, Map<String, Object> facts) {
        Map<String, Object> out = new HashMap<>();
        for (JsonNode rule : rules) {
            if (matches(rule.get("when"), facts)) {
                rule.get("then").forEach(t -> out.put(t.get("set").asString(), plain(t.get("value"))));
            }
        }
        return out;
    }

    /** Checks a rule before it's saved (and when it runs). The message names what's wrong. */
    public static JsonNode parse(String json, String systemName) {
        JsonNode rule;
        try {
            rule = JSON.readTree(json);
        } catch (JacksonException e) {
            throw new IllegalArgumentException("The JSON rule isn't valid JSON.");
        }
        JsonNode when = rule.path("when");
        JsonNode conditions = when.has("all") ? when.get("all") : when.path("any");
        if (!conditions.isArray() || conditions.isEmpty() || when.size() != 1) {
            throw new IllegalArgumentException("\"when\" needs one of \"all\" or \"any\" with a list of conditions.");
        }
        Set<String> fields = FIELDS.get(systemName);
        for (JsonNode c : conditions) {
            String field = c.path("field").asString(""), op = c.path("op").asString("");
            if (field.isEmpty() || !c.has("value")) {
                throw new IllegalArgumentException("Each condition needs \"field\", \"op\" and \"value\".");
            }
            if (fields != null && !fields.contains(field)) {
                throw new IllegalArgumentException("Unknown field \"" + field + "\"; use one of " + fields.stream().sorted().toList() + ".");
            }
            if (!OPS.contains(op)) {
                throw new IllegalArgumentException("Unknown op \"" + op + "\"; use one of " + OPS.stream().sorted().toList() + ".");
            }
            if (op.equals("in") != c.get("value").isArray()) {
                throw new IllegalArgumentException("\"in\" takes a list of values, and only \"in\" does.");
            }
        }
        JsonNode then = rule.path("then");
        if (!then.isArray() || then.isEmpty()) {
            throw new IllegalArgumentException("\"then\" needs a list of {\"set\", \"value\"}.");
        }
        for (JsonNode t : then) {
            if (t.path("set").asString("").isEmpty() || !t.has("value")) {
                throw new IllegalArgumentException("Each \"then\" needs \"set\" and \"value\".");
            }
        }
        return rule;
    }

    static boolean matches(JsonNode when, Map<String, Object> facts) {
        boolean all = when.has("all");
        for (JsonNode c : all ? when.get("all") : when.get("any")) {
            if (holds(c, facts.get(c.get("field").asString())) != all) {
                return !all;
            }
        }
        return all;
    }

    private static boolean holds(JsonNode c, Object fact) {
        JsonNode value = c.get("value");
        return switch (c.get("op").asString()) {
            case "eq" -> same(fact, value);
            case "ne" -> !same(fact, value);
            case "in" -> {
                for (JsonNode v : value) {
                    if (same(fact, v)) {
                        yield true;
                    }
                }
                yield false;
            }
            default -> fact instanceof BigDecimal n && value.isNumber() && switch (c.get("op").asString()) {
                case "gt" -> n.compareTo(value.decimalValue()) > 0;
                case "gte" -> n.compareTo(value.decimalValue()) >= 0;
                case "lt" -> n.compareTo(value.decimalValue()) < 0;
                default -> n.compareTo(value.decimalValue()) <= 0;
            };
        };
    }

    private static boolean same(Object fact, JsonNode value) {
        if (fact instanceof BigDecimal n) {
            return value.isNumber() && n.compareTo(value.decimalValue()) == 0;
        }
        if (fact instanceof Boolean b) {
            return value.isBoolean() && b == value.booleanValue();
        }
        return fact != null && value.isString() && fact.toString().equals(value.asString());
    }

    private static Object plain(JsonNode v) {
        return v.isBoolean() ? v.booleanValue() : v.isNumber() ? v.decimalValue() : v.asString();
    }
}
