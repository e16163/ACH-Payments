package com.achpayments.transfer;

import java.util.Locale;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** USE-CASES T6: a name against the (simulated) OFAC SDN list, ignoring case and punctuation. */
@Component
public class OfacScreening {

    private final JdbcTemplate jdbc;

    public OfacScreening(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isHit(String name) {
        String n = normalize(name);
        return jdbc.queryForList("SELECT sdnName FROM OfacSdnName", String.class).stream().anyMatch(s -> normalize(s).equals(n));
    }

    private static String normalize(String name) {
        return name.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", " ").trim();
    }
}
