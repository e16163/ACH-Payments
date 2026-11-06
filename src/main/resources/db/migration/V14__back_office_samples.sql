-- Made-up sample rows, so the back-office screens have something to show until their use cases arrive.
-- None of this comes from the design. Parents are the design's sample entry (trace 051000031234567) on each side.

INSERT INTO ${orig}.Action (actionID, actionName) VALUES (601, 'RETURN_PROCESS');

-- A return and two NOCs we received on the entry we sent
INSERT INTO ${orig}.ACHOrigReturn (returnID, tranID, typeID, sourceID, recvTraceNo, returnTraceNo, amount, acctName, returnStatus,
    returnCode, companyName, companyID, discretionaryData, returnAcctNo, returnRtn, returnDt, settlementDt, lastModBy)
SELECT 70001, tranID, 'RETURN', NULL, traceNo, '051000030000101', amount, recvName, 'RECEIVED', 'R03', companyName, companyID,
    companyDiscData, recvAcctID, '051000033', '2026-11-04', '2026-11-05', 'SYS_USER_02'
FROM ${orig}.ACHOrigTransaction WHERE traceNo = '051000031234567';

INSERT INTO ${orig}.ACHOrigReturnAction (returnID, processID, actionID, actionTm, lastModBy) VALUES
(70001, 'PROC_RET_01', 601, '2026-11-04 08:10:00', 'SYS_USER_02');

INSERT INTO ${orig}.ACHOrigNOC (nocID, tranID, sourceID, nocCode, correctedData, nocTraceNo, origTraceNo, lastModBy, notifyByDt, applyByDt)
SELECT 50913, tranID, NULL, 'C05', '32', '051000030000002', traceNo, 'SYS_USER_02', '2026-11-06', '2026-11-13'
FROM ${orig}.ACHOrigTransaction WHERE traceNo = '051000031234567';

INSERT INTO ${orig}.ACHOrigNOCAction (nocID, processID, actionID, actionTm, lastModBy) VALUES
(50913, 'PROC_NOC_02', 301, '2026-11-04 08:25:00', 'SYS_USER_02');

INSERT INTO ${orig}.ACHOrigAcctSubs (nocAcctNo, nocRoutingNo, subsAcctNo, subsRoutingNo, acctType, acctOwner, preNocID) VALUES
('987654321012', '051000033', '987654321013', '051000033', 'CHECKING', 'JOHN DOE', 50912);

INSERT INTO ${orig}.ACHOrigTransCodeSubs (nocID, nocAcctNo, nocRtn, nocTranCode, subsTranCode, companyID, lastModBy) VALUES
(50913, '987654321012', '051000033', '22', '32', '1234567890', 'SYS_USER_02');

-- A return, a return request and two NOCs on the entry we received
INSERT INTO ${recv}.ACHRecvReturn (returnID, tranID, typeID, sourceID, recvTraceNo, returnTraceNo, amount, acctName, returnStatus,
    returnCode, companyName, companyID, discretionaryData, returnAcctNo, returnRtn, returnDt, settlementDt, lastModBy, dueDt)
SELECT 80001, tranID, 'RETURN', NULL, traceNo, '123456780000101', amount, recvName, 'SENT', 'R02', companyName, companyID,
    companyDiscData, recvAcctID, '051000033', '2026-11-04', '2026-11-05', 'SYS_USER_02', '2026-11-05'
FROM ${recv}.ACHRecvTransaction WHERE traceNo = '051000031234567';

INSERT INTO ${recv}.ACHRecvReturnRequest (tranID, requestingRtn, requestReason, receivedDt, dueDt, lastModBy)
SELECT tranID, '051000033', 'Originator sent the payment in error', '2026-11-04', '2026-11-18', 'SYS_USER_02'
FROM ${recv}.ACHRecvTransaction WHERE traceNo = '051000031234567';

INSERT INTO ${recv}.ACHRecvNOC (nocID, tranID, sourceID, nocCode, correctedData, nocTraceNo, origTraceNo, lastModBy, nocStatus, approvedBy, dueDt)
SELECT 90001, tranID, NULL, 'C01', '987654321013', '123456780000201', traceNo, 'SYS_USER_02', 'DRAFT', NULL, '2026-11-05'
FROM ${recv}.ACHRecvTransaction WHERE traceNo = '051000031234567';

INSERT INTO ${recv}.ACHRecvNOC (nocID, tranID, sourceID, nocCode, correctedData, nocTraceNo, origTraceNo, lastModBy, nocStatus, approvedBy, dueDt)
SELECT 90002, tranID, NULL, 'C05', '32', '123456780000202', traceNo, 'SYS_USER_02', 'SENT', 'SYS_USER_01', '2026-11-05'
FROM ${recv}.ACHRecvTransaction WHERE traceNo = '051000031234567';

INSERT INTO ${recv}.ACHRecvAcctSubs (nocAcctNo, nocRoutingNo, subsAcctNo, subsRoutingNo, acctType, acctOwner, preNocID) VALUES
('987654321012', '123456780', '987654321013', '123456780', 'CHECKING', 'JOHN DOE', 90001);

INSERT INTO ${recv}.ACHRecvTransCodeSubs (nocID, nocAcctNo, nocRtn, nocTranCode, subsTranCode, companyID, lastModBy) VALUES
(90002, '987654321012', '123456780', '22', '32', '1234567890', 'SYS_USER_02');

-- A reversal that came in from the Fed: Nacha marks it with REVERSAL in the Company Entry Description
INSERT INTO ${recv}.ACHRecvBatch (batchID, fileID, batchNo, companyName, companyID, companyDiscData, stdEntryCode, companyEntryDesc,
    descriptiveDt, effectiveDt, settlementDt, odfiID, totalBatchCount, totalDebitAmt, serviceClassCode) VALUES
('BATCH-002', 'ACHFILE-88392', 2, 'ACME CORP', '1234567890', 'DIRECT DEP', 'PPD', 'REVERSAL', '2026-11-04',
    '2026-11-05', '2026-11-05', '05100003', 1, 3450.50, '225');

INSERT INTO ${recv}.ACHRecvTransaction (transferVersion, fileID, batchID, creditFlag, tranCode, companyName, companyID, companyDiscData,
    stdEntryCode, companyEntryDesc, descriptiveDt, effectiveDt, settlementDt, originatorID, amount, recvAcctID, recvName,
    transactionDiscData, traceNo, fedStatus, sourceID) VALUES
(1, 'ACHFILE-88392', 'BATCH-002', 0, '27', 'ACME CORP', '1234567890', 'DIRECT DEP', 'PPD', 'REVERSAL', '2026-11-04', '2026-11-05',
    '2026-11-05', '9000123456', 3450.50, '987654321012', 'JOHN DOE', 'S', '051000031234568', 'SETTLED', 'PAYROLL_SYS');
