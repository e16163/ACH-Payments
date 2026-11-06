-- UC-SYS-LOG-01. Adds: systemID, which application wrote the row.
-- FIX: logTm gets milliseconds, because the use case shows YYYY-MM-DD HH:mm:ss.SSS. The design has whole seconds.
ALTER TABLE AppLog
    ADD COLUMN systemID VARCHAR(30) NULL,
    MODIFY COLUMN logTm DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3);
