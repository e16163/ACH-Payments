-- UC-ACH-EOD-01 (docs/USE-CASES.md).

-- E2: The design's new spec names the statuses a transfer needs before it goes in a file.
UPDATE ${orig}.ACHOriginatorTransfer SET ofacStatus = 'OFAC_PASSED' WHERE ofacStatus = 'CLEARED';
UPDATE ${orig}.ACHOriginatorTransfer SET processStatus = 'PENDING_BATCH'
WHERE processStatus = 'PENDING' AND ofacStatus = 'OFAC_PASSED';

-- FIX: transferReqNo is the position in a series and transferReqRemaining what's left (the sample has 1 and 0).
UPDATE ${orig}.ACHOriginatorTransfer
SET transferReqRemaining = transferReqNo - 1, transferReqNo = 1
WHERE transferSerNo <> 'TRF-20261101-9921';

-- E6: a Type 6 record needs the receiving bank's routing number, and the design's entry table has none.
ALTER TABLE ${orig}.ACHOrigTransaction ADD COLUMN recvRoutingNo CHAR(9) NULL;

-- E7, E11
INSERT INTO AppParam (systemName, paramName, paramDesc, paramRawType, paramRawValue, lastModBy) VALUES
('ACH_ORIG', 'FED_IMMEDIATE_DEST', 'Routing number of the Federal Reserve office that receives our files', 'STRING', '011000015', 'SYS_ADMIN'),
('ACH_ORIG', 'ORIG_COMPANY_NAME', 'Company name in NACHA batch headers (16 characters at most)', 'STRING', 'DEMO CU', 'SYS_ADMIN'),
('ACH_ORIG', 'FED_SIM_FAIL_ATTEMPTS', 'Fed simulator: how many send attempts fail, to demo EX-02 (0 = none)', 'INTEGER', '0', 'SYS_ADMIN');

-- E8: next to the design's 401 FED_SEND
INSERT INTO ${orig}.Action (actionID, actionName) VALUES
(402, 'FILE_QUEUED'),
(403, 'FED_SEND_FAILED'),
(404, 'FILE_ERROR');
