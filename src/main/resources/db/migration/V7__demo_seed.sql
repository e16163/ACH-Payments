-- D6: the sample rows from the design doc, loaded as demo data.
-- The values are kept exactly, except where a constraint forces a change. Each change is marked "FIX".
-- Parent rows the design doesn't include (files, batches, actions, a NOC, originators, sources) are marked "PARENT":
-- they hold the minimum the rows need to satisfy the foreign keys.
-- FIX (USE-CASES T9): The design's routing number 051000031 fails the ABA check digit. 051000033 is the number with the
-- correct check digit, and it matches the ODFI prefix 05100003 of the trace number 051000031234567.

-- PARENT: channels used by the samples
INSERT INTO ${orig}.Source (sourceID, sourceName, sourceDesc) VALUES
('ONLINE_BANKING', 'Online Banking', 'Members using online or mobile banking'),
('PAYROLL_SYS', 'Payroll System', 'Corporate payroll files');

-- PARENT: the payroll company in the sample entry (originatorID 9000123456, company ACME CORP)
INSERT INTO ${orig}.ACHOriginator (originatorID, taxID, routingNo, name, lastModBy) VALUES
('9000123456', NULL, '051000033', 'ACME CORP', 'SYS_ADMIN');

-- PARENT: the file and batch the design's originated entry belongs to (values taken from the entry itself)
INSERT INTO ${orig}.ACHOrigFile (fileID, fileName, fileHeaderDt, fileHeaderTm, fedStatus) VALUES
('ACHFILE-88392', 'ACHFILE-88392.ach', '2026-11-01', '16:00:00', 'SETTLED');
INSERT INTO ${orig}.ACHOrigBatch (batchID, fileID, batchNo, companyName, companyID, companyDiscData, stdEntryCode,
    companyEntryDesc, descriptiveDt, effectiveDt, settlementDt, odfiID, totalBatchCount, totalCreditAmt, serviceClassCode) VALUES
('BATCH-001', 'ACHFILE-88392', 1, 'ACME CORP', '1234567890', 'DIRECT DEP', 'PPD', 'PAYROLL', '2026-11-01',
    '2026-11-03', '2026-11-03', '05100003', 1, 3450.50, '220');

-- Design: ACHOrigTransaction
-- FIX: transferSerNo 'TRF-20261101-0089' has no ACHOriginatorTransfer row (it's a corporate payroll entry, not a
-- member transfer), so it is NULL.
INSERT INTO ${orig}.ACHOrigTransaction (transferSerNo, transferVersion, fileID, batchID, creditFlag, tranCode,
    companyName, companyID, companyDiscData, stdEntryCode, companyEntryDesc, descriptiveDt, effectiveDt, settlementDt,
    originatorID, amount, recvAcctID, recvName, transactionDiscData, traceNo, fedStatus, sourceID) VALUES
(NULL, 1, 'ACHFILE-88392', 'BATCH-001', 1, '22', 'ACME CORP', '1234567890', 'DIRECT DEP', 'PPD', 'PAYROLL',
    '2026-11-01', '2026-11-03', '2026-11-03', '9000123456', 3450.50, '987654321012', 'JOHN DOE', 'S',
    '051000031234567', 'SETTLED', 'PAYROLL_SYS');

-- Design: ACHOrigRecvAccount (FIX: routingNo)
INSERT INTO ${orig}.ACHOrigRecvAccount (recvAcctSerNo, recvAcctVersion, memberNo, acctNo, routingNo, acctType,
    acctOwner, acctStatus, sourceID, thridPartyFlag, creditFlag, debitFlag, lastModBy) VALUES
('REC-SRV-20261102-01', 1, 'M10982347', '987654321012', '051000033', 'CHECKING', 'JOHN DOE', 'ACTIVE',
    'ONLINE_BANKING', 0, 1, 1, 'SYS_USER_01');

-- Design: ACHOrigLimitRule
INSERT INTO ${orig}.ACHOrigLimitRule (ruleName, acctType, dayNo, limitAmt, status, lastModBy) VALUES
('DAILY_CHECKING_DEBIT_CAP', 'CHECKING', 1, 25000.00, 'ACTIVE', 'SYS_ADMIN'),
('DAILY_SAVINGS_LIMIT', 'SAVINGS', 1, 10000.00, 'ACTIVE', 'SYS_ADMIN'),
('WEEKLY_RETAIL_LIMIT', 'ALL', 7, 50000.00, 'ACTIVE', 'SYS_ADMIN'),
('MONTHLY_CORPORATE_CAP', 'CHECKING', 30, 500000.00, 'ACTIVE', 'SYS_ADMIN');

-- Design: ACHOriginatorTransfer
-- FIX: recvAcctSerNo 'REC-883721' doesn't exist; it now points at the sample account for the same member.
-- FIX (USE-CASES T2): originatorID '1234567890' isn't an originator; retail transfers belong to the credit union.
INSERT INTO ${orig}.ACHOriginatorTransfer (transferSerNo, transferVersion, memberNo, pTransferSerNo, recvAcctSerNo,
    recvAcctVersion, origAcctNo, origAcctType, origAcctGrp, recurringFlag, intAcctSpecFlag, nextPmtDt, pmtFrequency,
    transferReqNo, transferReqRemaining, transferAmt, processStatus, ofacStatus, memoStatus, postingStatus,
    postingSystem, sourceID, originatorID, lastModBy) VALUES
('TRF-20261101-9921', 1, 'M10982347', NULL, 'REC-SRV-20261102-01', 1, '100049281920', 'CHECKING', 'RETAIL', 0, 0,
    '2026-11-03', 'ONETIME', 1, 0, 1250.00, 'PENDING', 'CLEARED', 'PENDING', 'PENDING', 'MAIN_CORE',
    'ONLINE_BANKING', '9000000001', 'SYS_USER_01');

-- PARENT: the actions and the files the design's action rows refer to
INSERT INTO ${orig}.Action (actionID, actionName) VALUES
(201, 'RESUBMIT'),
(301, 'NOC_PROCESS'),
(401, 'FED_SEND'),
(501, 'REVERSAL');
INSERT INTO ${orig}.ACHOrigFile (fileID, fileName, fileHeaderDt, fileHeaderTm) VALUES
('FILE_20261102_01', 'FILE_20261102_01.ach', '2026-11-02', '08:00:00'),
('FILE_20261102_02', 'FILE_20261102_02.ach', '2026-11-02', '08:00:00');

-- PARENT: NOC 50912 that the design's NOC action refers to, on the originated entry (C01 = incorrect account number)
INSERT INTO ${orig}.ACHOrigNOC (nocID, tranID, sourceID, nocCode, correctedData, nocTraceNo, origTraceNo, lastModBy)
SELECT 50912, tranID, NULL, 'C01', '987654321013', '051000030000001', traceNo, 'SYS_USER_02'
FROM ${orig}.ACHOrigTransaction WHERE traceNo = '051000031234567';

-- Design: action rows
INSERT INTO ${orig}.ACHOrigResubAction (fileID, processID, actionID, actionTm, lastModBy) VALUES
('FILE_20261102_01', 'PROC_RESUB_02', 201, '2026-11-02 08:20:00', 'SYS_USER_01');
INSERT INTO ${orig}.ACHOrigNOCAction (nocID, processID, actionID, actionTm, lastModBy) VALUES
(50912, 'PROC_NOC_01', 301, '2026-11-02 08:25:00', 'SYS_USER_02');
INSERT INTO ${orig}.ACHOrigFedAction (fileID, processID, actionID, actionTm, lastModBy) VALUES
('FILE_20261102_01', 'PROC_FED_01', 401, '2026-11-02 08:30:00', 'FED_BATCH_SYS');
INSERT INTO ${orig}.ACHOrigReversalAction (fileID, processID, actionID, actionTm, lastModBy) VALUES
('FILE_20261102_02', 'PROC_REV_01', 501, '2026-11-02 08:35:00', 'SYS_ADMIN');

-- PARENT: the inbound file and batch the design's received entry belongs to
INSERT INTO ${recv}.ACHRecvFile (fileID, fileName, fileHeaderDt, fileHeaderTm, fedStatus) VALUES
('ACHFILE-88392', 'ACHFILE-88392.ach', '2026-11-01', '16:00:00', 'PROCESSED');
INSERT INTO ${recv}.ACHRecvBatch (batchID, fileID, batchNo, companyName, companyID, companyDiscData, stdEntryCode,
    companyEntryDesc, descriptiveDt, effectiveDt, settlementDt, odfiID, totalBatchCount, totalCreditAmt, serviceClassCode) VALUES
('BATCH-001', 'ACHFILE-88392', 1, 'ACME CORP', '1234567890', 'DIRECT DEP', 'PPD', 'PAYROLL', '2026-11-01',
    '2026-11-03', '2026-11-03', '05100003', 1, 3450.50, '220');

-- Design: ACHRecvTransaction (no changes needed)
INSERT INTO ${recv}.ACHRecvTransaction (transferSerNo, transferVersion, fileID, batchID, creditFlag, tranCode,
    companyName, companyID, companyDiscData, stdEntryCode, companyEntryDesc, descriptiveDt, effectiveDt, settlementDt,
    originatorID, amount, recvAcctID, recvName, transactionDiscData, traceNo, fedStatus, sourceID) VALUES
('TRF-20261101-0089', 1, 'ACHFILE-88392', 'BATCH-001', 1, '22', 'ACME CORP', '1234567890', 'DIRECT DEP', 'PPD',
    'PAYROLL', '2026-11-01', '2026-11-03', '2026-11-03', '9000123456', 3450.50, '987654321012', 'JOHN DOE', 'S',
    '051000031234567', 'SETTLED', 'PAYROLL_SYS');

-- Design: ACHRecvAccount (FIX: routingNo)
INSERT INTO ${recv}.ACHRecvAccount (recvAcctSerNo, recvAcctVersion, memberNo, acctNo, routingNo, acctType,
    acctOwner, acctStatus, sourceID, thridPartyFlag, creditFlag, debitFlag, lastModBy) VALUES
('REC-SRV-20261102-01', 1, 'M10982347', '987654321012', '051000033', 'CHECKING', 'JOHN DOE', 'ACTIVE',
    'ONLINE_BANKING', 0, 1, 1, 'SYS_USER_01');

-- Design: AppLog (the AppParam samples are already in V1)
INSERT INTO AppLog (logLevel, logMesg, logExceptionData, logTm) VALUES
('ERROR', 'Failed to parse incoming ACH file HEADER record for file ID FILE_20261103_09',
    'java.lang.IllegalArgumentException: Invalid routing number length at line 1 char 23\n\tat com.ach.engine.FileParser.parseHeader(FileParser.java:42)',
    '2026-11-03 15:10:00');
