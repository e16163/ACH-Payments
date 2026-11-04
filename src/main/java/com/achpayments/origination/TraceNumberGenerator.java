package com.achpayments.origination;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * NACHA trace numbers for entries we originate: the ODFI's 8-digit routing prefix + a 7-digit sequence.
 * Ba's index on {@code ACHOrigTransaction.traceNo} isn't unique (DB-MODEL F7 kept as he wrote it), so uniqueness
 * is guaranteed here: each prefix continues after the highest number already stored, and numbers are handed out
 * atomically. This assumes one running app instance (true for this demo).
 */
@Component
public class TraceNumberGenerator {

    static final long MAX_SEQUENCE = 9_999_999L;

    private final JdbcTemplate jdbc;
    private final String origDb;
    private final Map<String, AtomicLong> lastByPrefix = new ConcurrentHashMap<>();

    public TraceNumberGenerator(JdbcTemplate jdbc, @Value("${app.db.origination}") String origDb) {
        this.jdbc = jdbc;
        this.origDb = origDb;
    }

    /** @param odfiRoutingNo the 9-digit routing number of the bank sending the file (its first 8 digits are used) */
    public String next(String odfiRoutingNo) {
        if (odfiRoutingNo == null || !odfiRoutingNo.matches("\\d{9}")) {
            throw new IllegalArgumentException("ODFI routing number must be 9 digits");
        }
        String prefix = odfiRoutingNo.substring(0, 8);
        long seq = lastByPrefix.computeIfAbsent(prefix, p -> new AtomicLong(highestStored(p))).incrementAndGet();
        if (seq > MAX_SEQUENCE) {
            throw new IllegalStateException("Trace number sequence exhausted for ODFI " + prefix);
        }
        return prefix + String.format("%07d", seq);
    }

    private long highestStored(String prefix) {
        String max = jdbc.queryForObject("SELECT MAX(traceNo) FROM " + origDb
                + ".ACHOrigTransaction WHERE traceNo LIKE ? AND LENGTH(traceNo) = 15", String.class, prefix + "%");
        return max == null ? 0 : Long.parseLong(max.substring(8));
    }
}
