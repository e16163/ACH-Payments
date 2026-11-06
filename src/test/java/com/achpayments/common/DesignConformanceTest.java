package com.achpayments.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.context.ActiveProfiles;

/**
 * "Follow the design" as a test (docs/DB-MODEL.md section 0): runs the original DDL in a scratch database,
 * then checks that every design table we've built keeps all the columns (type, nullability, default, extra),
 * primary key, indexes and foreign keys exactly. Our additions are allowed; changes are not.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = "TEST_JDBC_URL", matches = ".+")
@ActiveProfiles("dbtest")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DesignConformanceTest {

    private static final String REFERENCE_DB = "design_reference_test";

    /** All 30 of the design's tables with SQL (D1-D3). */
    private static final Set<String> REQUIRED = Set.of(
            "AppParam", "AppLog",
            "ACHRecvTransaction", "ACHRecvAccount", "ACHRecvFile", "ACHRecvBatch", "ACHRecvReturn", "ACHRecvNOC",
            "ACHRecvAcctSubs", "ACHRecvTransCodeSubs",
            "ACHOrigTransaction", "ACHOriginator", "ACHOrigRecvAccount", "ACHOrigLimitRule", "ACHOriginatorTransfer",
            "ACHOperator", "Contact", "ACHOrigFile", "ACHOrigBatch", "Company", "Source",
            "Action", "ACHOrigReturn", "ACHOrigNOC", "ACHOrigAcctSubs", "ACHOrigTransCodeSubs",
            "ACHOrigResubAction", "ACHOrigNOCAction", "ACHOrigFedAction", "ACHOrigReversalAction");

    @Autowired
    JdbcTemplate jdbc;

    @Value("${app.db.common}")
    String commonDb;

    @Value("${app.db.receiving}")
    String recvDb;

    @Value("${app.db.origination}")
    String origDb;

    @Value("${spring.datasource.username}")
    String user;

    @Value("${spring.datasource.password}")
    String password;

    @BeforeAll
    void loadTheDesignDdlIntoTheReferenceDatabase() throws IOException {
        String url = System.getenv("TEST_JDBC_URL").replaceFirst("/[^/?]+(\\?|$)", "/" + REFERENCE_DB + "$1");
        // One connection, so SET FOREIGN_KEY_CHECKS applies to the DROPs that follow.
        JdbcTemplate ref = new JdbcTemplate(new SingleConnectionDataSource(url, user, password, true));
        ref.execute("SET FOREIGN_KEY_CHECKS = 0");
        for (String t : ref.queryForList("SELECT table_name FROM information_schema.tables WHERE table_schema = ?",
                String.class, REFERENCE_DB)) {
            ref.execute("DROP TABLE `" + t + "`");
        }
        ref.execute("SET FOREIGN_KEY_CHECKS = 1");
        for (String statement : designDdl().split(";\\s*\\n")) {
            if (statement.contains("CREATE TABLE")) {
                ref.execute(statement);
            }
        }
    }

    @Test
    void referenceDatabaseHasAllDesignTables() {
        assertEquals(30, tablesIn(REFERENCE_DB).size());
    }

    @Test
    void requiredDesignTablesExist() {
        List<String> missing = REQUIRED.stream().filter(t -> !tablesIn(schemaFor(t)).contains(t)).sorted().toList();
        assertEquals(List.of(), missing, "the design's tables not built yet");
    }

    @Test
    void everyDesignTableWeBuiltKeepsItsDefinitionExactly() {
        List<String> differences = new ArrayList<>();
        int checked = 0;
        for (String table : tablesIn(REFERENCE_DB)) {
            String ours = schemaFor(table);
            if (!tablesIn(ours).contains(table)) {
                continue;
            }
            checked++;
            for (Map<String, Object> col : columns(REFERENCE_DB, table)) {
                Map<String, Object> mine = columns(ours, table).stream()
                        .filter(c -> c.get("name").equals(col.get("name"))).findFirst().orElse(null);
                if (!col.equals(mine)) {
                    differences.add(table + "." + col.get("name") + ": The designer " + col + " vs ours " + mine);
                }
            }
            compare(differences, table, "primary key", keys(REFERENCE_DB, table, "PRIMARY KEY"), keys(ours, table, "PRIMARY KEY"));
            Set<String> ourIndexes = indexes(ours, table);
            for (String index : indexes(REFERENCE_DB, table)) {
                if (!ourIndexes.contains(index)) differences.add(table + ": missing the design's index " + index);
            }
            Set<String> ourFks = foreignKeys(ours, table);
            for (String fk : foreignKeys(REFERENCE_DB, table)) {
                if (!ourFks.contains(fk)) differences.add(table + ": missing the design's foreign key " + fk);
            }
        }
        assertTrue(checked >= REQUIRED.size(), "checked only " + checked + " tables");
        assertEquals(List.of(), differences);
    }

    // --- helpers ---

    private String schemaFor(String table) {
        if (table.startsWith("ACHRecv")) return recvDb;
        if (table.equals("AppParam") || table.equals("AppLog")) return commonDb;
        return origDb;
    }

    private Set<String> tablesIn(String schema) {
        return new TreeSet<>(jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables WHERE table_schema = ?", String.class, schema));
    }

    private List<Map<String, Object>> columns(String schema, String table) {
        return jdbc.queryForList("""
                SELECT column_name AS name, column_type AS type, is_nullable AS nullable,
                       column_default AS def, extra
                FROM information_schema.columns WHERE table_schema = ? AND table_name = ?""", schema, table);
    }

    private List<String> keys(String schema, String table, String type) {
        return jdbc.queryForList("""
                SELECT k.column_name FROM information_schema.table_constraints c
                JOIN information_schema.key_column_usage k
                  ON k.constraint_schema = c.constraint_schema AND k.table_name = c.table_name
                 AND k.constraint_name = c.constraint_name
                WHERE c.table_schema = ? AND c.table_name = ? AND c.constraint_type = ?
                ORDER BY k.ordinal_position""", String.class, schema, table, type);
    }

    /** "IX_name(col1,col2) unique=false" for each non-primary index. */
    private Set<String> indexes(String schema, String table) {
        return new TreeSet<>(jdbc.queryForList("""
                SELECT CONCAT(index_name, '(', GROUP_CONCAT(column_name ORDER BY seq_in_index), ') unique=', non_unique = 0)
                FROM information_schema.statistics
                WHERE table_schema = ? AND table_name = ? AND index_name <> 'PRIMARY'
                GROUP BY index_name, non_unique""", String.class, schema, table));
    }

    /** "FK_name(cols) -> Table(cols) on delete RULE"; the referenced database is ignored (ours differs by design). */
    private Set<String> foreignKeys(String schema, String table) {
        return new TreeSet<>(jdbc.queryForList("""
                SELECT CONCAT(k.constraint_name, '(', GROUP_CONCAT(k.column_name ORDER BY k.ordinal_position), ') -> ',
                              k.referenced_table_name, '(', GROUP_CONCAT(k.referenced_column_name ORDER BY k.ordinal_position),
                              ') on delete ', r.delete_rule)
                FROM information_schema.key_column_usage k
                JOIN information_schema.referential_constraints r
                  ON r.constraint_schema = k.constraint_schema AND r.constraint_name = k.constraint_name
                WHERE k.table_schema = ? AND k.table_name = ? AND k.referenced_table_name IS NOT NULL
                GROUP BY k.constraint_name, k.referenced_table_name, r.delete_rule""", String.class, schema, table));
    }

    private static void compare(List<String> differences, String table, String what, Object ba, Object ours) {
        if (!ba.equals(ours)) differences.add(table + " " + what + ": The designer " + ba + " vs ours " + ours);
    }

    private static String designDdl() throws IOException {
        try (InputStream in = DesignConformanceTest.class.getResourceAsStream("/design-reference/design-ddl.sql")) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
