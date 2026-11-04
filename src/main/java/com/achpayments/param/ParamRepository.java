package com.achpayments.param;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ParamRepository {

    private static final RowMapper<AppParam> ROW = (rs, i) -> new AppParam(
            rs.getString("systemName"),
            rs.getString("paramName"),
            rs.getString("paramDesc"),
            rs.getString("paramRawType"),
            rs.getString("paramRawValue"),
            rs.getObject("lastModTm", java.time.LocalDateTime.class),
            rs.getString("lastModBy"));

    private final JdbcTemplate jdbc;

    public ParamRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AppParam> findBySystem(String systemName) {
        return jdbc.query("SELECT * FROM AppParam WHERE systemName = ? ORDER BY paramName", ROW, systemName);
    }

    public Optional<AppParam> find(String systemName, String paramName) {
        return jdbc.query("SELECT * FROM AppParam WHERE systemName = ? AND paramName = ?", ROW, systemName, paramName)
                .stream().findFirst();
    }

    /**
     * Insert, or update on the composite key (systemName, paramName). lastModTm is set by the database;
     * it is set explicitly on update because ON UPDATE CURRENT_TIMESTAMP doesn't fire when no value changes.
     */
    public void upsert(String systemName, String paramName, String paramDesc, String type, String value, String user) {
        jdbc.update("""
                INSERT INTO AppParam (systemName, paramName, paramDesc, paramRawType, paramRawValue, lastModBy)
                VALUES (?, ?, ?, ?, ?, ?) AS new
                ON DUPLICATE KEY UPDATE
                    paramDesc = new.paramDesc,
                    paramRawType = new.paramRawType,
                    paramRawValue = new.paramRawValue,
                    lastModBy = new.lastModBy,
                    lastModTm = CURRENT_TIMESTAMP
                """, systemName, paramName, paramDesc, type, value, user);
    }
}
