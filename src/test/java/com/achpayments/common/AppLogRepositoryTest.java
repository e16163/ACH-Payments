package com.achpayments.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class AppLogRepositoryTest {

    private static final String INSERT = "INSERT INTO AppLog (systemID, logLevel, logMesg, logExceptionData) VALUES (?, ?, ?, ?)";

    @TempDir
    Path dir;

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    private AppLogRepository repo() {
        return new AppLogRepository(jdbc, dir.resolve("logs/app-error.log").toString());
    }

    @Test
    void savesAllTheFields() {
        repo().log("ACH_RCV", "INFO", "hello");
        verify(jdbc).update(INSERT, "ACH_RCV", "INFO", "hello", null);
    }

    @Test
    void aLineBreakCantFakeAnotherLogEntry() {
        repo().log("ACH_SYS", "WARN", "Failed sign-in for user 'x'\r\n2026-11-06 FATAL forged\tentry");
        verify(jdbc).update(INSERT, "ACH_SYS", "WARN", "Failed sign-in for user 'x' 2026-11-06 FATAL forged entry", null);
    }

    @Test
    void cutsTheMessageTo1000Characters() {
        repo().log("ACH_SYS", "INFO", "x".repeat(1001));
        verify(jdbc).update(INSERT, "ACH_SYS", "INFO", "x".repeat(1000), null);
    }

    @Test
    void savesTheFullStackTrace() {
        repo().log("ACH_ORIG", "FATAL", "failed", new IllegalStateException("boom", new RuntimeException("root")));
        verify(jdbc).update(eq(INSERT), eq("ACH_ORIG"), eq("FATAL"), eq("failed"),
                contains("Caused by: java.lang.RuntimeException: root"));
    }

    @Test
    void cutsAStackTraceThatWouldNotFitInText() {
        repo().log("ACH_ORIG", "FATAL", "failed", new IllegalStateException("x".repeat(70_000)));
        verify(jdbc).update(eq(INSERT), eq("ACH_ORIG"), eq("FATAL"), eq("failed"),
                org.mockito.ArgumentMatchers.<String>argThat(s -> s.length() == 60_000));
    }

    @Test
    void ex01WritesToTheFileWhenTheDatabaseIsDownAndKeepsEveryEntry() throws IOException {
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        AppLogRepository repo = repo();
        repo.log("ACH_RCV", "ERROR", "first", new IllegalStateException("boom"));
        repo.log("ACH_ORIG", "INFO", "second");

        String file = Files.readString(dir.resolve("logs/app-error.log"));
        assertTrue(file.contains("ERROR ACH_RCV first"));
        assertTrue(file.contains("java.lang.IllegalStateException: boom"));
        assertTrue(file.contains("INFO ACH_ORIG second"));
        verify(jdbc, times(2)).update(startsWith("INSERT INTO AppLog"), any(), any(), any(), any()); // no retry, so no recursion
    }

    /** Runs the log call in a transaction that commits or rolls back. */
    private void logInTransaction(boolean commit) {
        when(jdbc.update(anyString(), any(), any(), any(), any())).thenThrow(new DataAccessResourceFailureException("down"));
        new TransactionTemplate(new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        }).executeWithoutResult(status -> {
            repo().log("ACH_ORIG", "INFO", "Member submitted transfer");
            assertFalse(Files.exists(dir.resolve("logs/app-error.log")), "the file must wait for the outcome");
            if (!commit) {
                status.setRollbackOnly();
            }
        });
    }

    @Test
    void ex01MarksAnEntryWhoseTransactionFailed() throws IOException {
        logInTransaction(false);
        assertTrue(Files.readString(dir.resolve("logs/app-error.log")).contains("Member submitted transfer [NOT COMMITTED]"));
    }

    @Test
    void ex01LeavesAnEntryWhoseTransactionCommittedUnmarked() throws IOException {
        logInTransaction(true);
        String file = Files.readString(dir.resolve("logs/app-error.log"));
        assertTrue(file.contains("Member submitted transfer"));
        assertFalse(file.contains("NOT COMMITTED"));
    }

    @Test
    void ex01AlsoCoversARealFailedConnection() throws IOException {
        JdbcTemplate down = new JdbcTemplate(new DriverManagerDataSource("jdbc:mysql://127.0.0.1:1/none"));
        new AppLogRepository(down, dir.resolve("logs/app-error.log").toString()).log("ACH_SYS", "FATAL", "no database");
        assertTrue(Files.readString(dir.resolve("logs/app-error.log")).contains("FATAL ACH_SYS no database"));
    }

    @Test
    void writesNoFileWhenTheDatabaseIsUp() {
        repo().log("ACH_RCV", "INFO", "hello");
        assertFalse(Files.exists(dir.resolve("logs/app-error.log")));
        assertEquals(0, org.mockito.Mockito.mockingDetails(jdbc).getStubbings().size());
        verify(jdbc).update(anyString(), eq("ACH_RCV"), eq("INFO"), eq("hello"), isNull());
    }
}
