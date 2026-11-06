-- UC-ACH-RCV-01 and UC-ACH-RCV-02: inbound FedACH files. Additions only; Ba's columns are unchanged.

-- UC-ACH-RCV-01 uses ACHRecvFile.processStatus, and REJECTED_SIG_FAIL doesn't fit in fedStatus VARCHAR(15).
-- INGESTING -> VALIDATED, or REJECTED_SIG_FAIL / OUT_OF_BALANCE / DUPLICATE / REJECTED
ALTER TABLE ${recv}.ACHRecvFile
    ADD COLUMN processStatus VARCHAR(20) NOT NULL DEFAULT 'INGESTING',
    ADD INDEX IX_ACHRecvFile_Duplicate (fileHeaderDt, fileHeaderTm, fileIDModifier);
UPDATE ${recv}.ACHRecvFile SET processStatus = fedStatus;

-- UC-ACH-RCV-01 step 4 marks entries UNPROCESSED; UC-ACH-RCV-02 step 2 sets fedStatus to SETTLED. Keeping both needs a column.
ALTER TABLE ${recv}.ACHRecvTransaction
    ADD COLUMN processStatus VARCHAR(15) NOT NULL DEFAULT 'UNPROCESSED';
UPDATE ${recv}.ACHRecvTransaction SET processStatus = 'PROCESSED';

INSERT INTO ${orig}.Source (sourceID, sourceName, sourceDesc) VALUES
('FED_ACH_INBOUND', 'FedACH Inbound', 'Files received from the Federal Reserve');

INSERT INTO AppParam (systemName, paramName, paramDesc, paramRawType, paramRawValue, lastModBy) VALUES
('ACH_RCV', 'FED_RECV_FILE_TIME', 'Time the daily file from the Fed is picked up (Eastern)', 'STRING', '06:00:00', 'SYS_ADMIN'),
('ACH_RCV', 'RDFI_ROUTING_NO', 'Our routing number; an inbound file must be addressed to it', 'STRING', '123456780', 'SYS_ADMIN');
