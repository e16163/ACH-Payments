-- Ba's blueprint, ACH Receiving Processing (docs/BLUEPRINT-PLAN.md section 3). Additions only; the design's columns are unchanged.

-- Step 2: the raw file text is kept for audit.
ALTER TABLE ${recv}.ACHRecvFile ADD COLUMN rawFileText LONGTEXT NULL;

-- Ba's transaction statuses: RECEIVED, AUTO_CORRECTED, REJECTED, MEMO_POSTED, POSTED, plus MANUAL_REVIEW from step 4.
-- Ba's ACHReceivingTransaction columns map onto the design's ACHRecvTransaction (Q2): corrected_account_number, return_code
-- and memo_posted_at are added; credit_debit_indicator is the existing creditFlag. coreTranCode and processReason hold step 4's
-- core transaction code and the reason for an auto-correction, review or rejection.
ALTER TABLE ${recv}.ACHRecvTransaction
    ALTER COLUMN processStatus SET DEFAULT 'RECEIVED',
    ADD COLUMN correctedAcctNo VARCHAR(17) NULL,
    ADD COLUMN coreTranCode VARCHAR(10) NULL,
    ADD COLUMN processReason VARCHAR(40) NULL,
    ADD COLUMN returnCode CHAR(3) NULL,
    ADD COLUMN memoPostedTm DATETIME NULL,
    ADD COLUMN postedTm DATETIME NULL;
UPDATE ${recv}.ACHRecvTransaction SET processStatus = 'RECEIVED' WHERE processStatus = 'UNPROCESSED';
UPDATE ${recv}.ACHRecvTransaction SET processStatus = 'POSTED' WHERE processStatus = 'PROCESSED';

-- Step 5 (Q7): stop payments (R08) and ACH debit blocks on an originator (R10 consumer, R29 corporate). Seed data only.
CREATE TABLE ${recv}.ACHRecvStopPayment (
    stopID BIGINT AUTO_INCREMENT NOT NULL,
    acctNo VARCHAR(34) NOT NULL,
    companyID VARCHAR(10) NULL COMMENT 'NULL: any originator',
    amount DECIMAL(13, 2) NULL COMMENT 'NULL: any amount',
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_ACHRecvStopPayment PRIMARY KEY (stopID),
    INDEX IX_ACHRecvStopPayment_Acct (acctNo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ${recv}.ACHRecvBlockedOriginator (
    blockID BIGINT AUTO_INCREMENT NOT NULL,
    acctNo VARCHAR(34) NOT NULL,
    companyID VARCHAR(10) NOT NULL,
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_ACHRecvBlockedOriginator PRIMARY KEY (blockID),
    CONSTRAINT UQ_ACHRecvBlockedOriginator UNIQUE (acctNo, companyID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Step 7: one core posting file per business date.
CREATE TABLE ${recv}.ACHRecvPostingJob (
    postingJobID VARCHAR(30) NOT NULL,
    businessDt DATE NOT NULL,
    fileName VARCHAR(100) NOT NULL,
    recordCount INT NOT NULL,
    totalDebitAmt DECIMAL(15, 2) NOT NULL,
    totalCreditAmt DECIMAL(15, 2) NOT NULL,
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_ACHRecvPostingJob PRIMARY KEY (postingJobID),
    CONSTRAINT UQ_ACHRecvPostingJob_Date UNIQUE (businessDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Step 1: five windows replace the single 6 AM pickup. Step 6 (Q6): trusted Company IDs. Step 5: the per-entry limit.
DELETE FROM AppParam WHERE systemName = 'ACH_RCV' AND paramName = 'FED_RECV_FILE_TIME';
INSERT INTO AppParam (systemName, paramName, paramDesc, paramRawType, paramRawValue, lastModBy) VALUES
('ACH_RCV', 'RECV_WINDOW_TIMES', 'FedACH distribution windows when inbound files are processed (Eastern, comma-separated)', 'STRING', '06:00,09:00,12:00,15:00,18:00', 'SYS_ADMIN'),
('ACH_RCV', 'CORE_POSTING_TIME', 'Time the end-of-day core posting file is generated (Eastern)', 'STRING', '19:00', 'SYS_ADMIN'),
('ACH_RCV', 'TRUSTED_COMPANY_IDS', 'Originator Company IDs whose credits are memo posted at once (comma-separated)', 'STRING', '1900483920', 'SYS_ADMIN'),
('ACH_RCV', 'MAX_ENTRY_AMOUNT', 'A received entry above this amount waits for manual review', 'DECIMAL', '25000.00', 'SYS_ADMIN');

-- Core accounts for the receivers in the sample files (fed-samples), so every step has something to show:
-- 987654330019 is missing (R03); 987654329019 is held as 0987654329019 (zero padding); 987654328019 is a known alias of
-- 555000328019 (ACHRecvAcctSubs); 987654327019 is FROZEN (R16); 987654326019 is CLOSED (R02); 987654410019 has $5.00 (R01 on
-- the $19.99 subscription debit); 987654323019 has a stop payment on GLOBAL WEB PAY (R08); 987654322019 blocks it (R10).
INSERT INTO SimCoreAccount (acctNo, memberNo, acctName, acctType, acctOwner, ledgerBal, availBal, acctStatus) VALUES
('987654321019', 'M3000001', 'ACH Checking', 'CHECKING', 'JOHN DOE', 2000.00, 2000.00, 'ACTIVE'),
('987654322019', 'M3000002', 'ACH Checking', 'CHECKING', 'JANE SMITH', 2000.00, 2000.00, 'ACTIVE'),
('987654323019', 'M3000003', 'ACH Checking', 'CHECKING', 'ROBERT JOHNSON', 2000.00, 2000.00, 'ACTIVE'),
('987654324019', 'M3000004', 'ACH Checking', 'CHECKING', 'EMILY DAVIS', 2000.00, 2000.00, 'ACTIVE'),
('987654325019', 'M3000005', 'ACH Checking', 'CHECKING', 'MICHAEL BROWN', 2000.00, 2000.00, 'ACTIVE'),
('987654326019', 'M3000006', 'ACH Checking', 'CHECKING', 'SARAH WILSON', 2000.00, 2000.00, 'CLOSED'),
('987654327019', 'M3000007', 'ACH Checking', 'CHECKING', 'DAVID TAYLOR', 2000.00, 2000.00, 'FROZEN'),
('0987654329019', 'M3000008', 'ACH Checking', 'CHECKING', 'JAMES THOMAS', 2000.00, 2000.00, 'ACTIVE'),
('987654401019', 'M3000009', 'ACH Checking', 'CHECKING', 'SUPPLIER ALPHA', 2000.00, 2000.00, 'ACTIVE'),
('987654402019', 'M3000010', 'ACH Checking', 'CHECKING', 'SUPPLIER BETA', 2000.00, 2000.00, 'ACTIVE'),
('987654403019', 'M3000011', 'ACH Checking', 'CHECKING', 'SUPPLIER GAMMA', 2000.00, 2000.00, 'ACTIVE'),
('987654404019', 'M3000012', 'ACH Checking', 'CHECKING', 'SUPPLIER DELTA', 2000.00, 2000.00, 'ACTIVE'),
('987654405019', 'M3000013', 'ACH Checking', 'CHECKING', 'SUPPLIER EPSILON', 2000.00, 2000.00, 'ACTIVE'),
('987654406019', 'M3000014', 'ACH Checking', 'CHECKING', 'SUPPLIER ZETA', 2000.00, 2000.00, 'ACTIVE'),
('987654407019', 'M3000015', 'ACH Checking', 'CHECKING', 'SUPPLIER ETA', 2000.00, 2000.00, 'ACTIVE'),
('987654408019', 'M3000016', 'ACH Checking', 'CHECKING', 'SUPPLIER THETA', 2000.00, 2000.00, 'ACTIVE'),
('987654409019', 'M3000017', 'ACH Checking', 'CHECKING', 'SUPPLIER IOTA', 2000.00, 2000.00, 'ACTIVE'),
('987654410019', 'M3000018', 'ACH Checking', 'CHECKING', 'SUPPLIER KAPPA', 5.00, 5.00, 'ACTIVE'),
('987654501019', 'M3000019', 'ACH Checking', 'CHECKING', 'JOHN DOE', 2000.00, 2000.00, 'ACTIVE'),
('987654502019', 'M3000020', 'ACH Checking', 'CHECKING', 'JANE SMITH', 2000.00, 2000.00, 'ACTIVE'),
('987654503019', 'M3000021', 'ACH Checking', 'CHECKING', 'ROBERT JOHNSON', 2000.00, 2000.00, 'ACTIVE'),
('987654504019', 'M3000022', 'ACH Checking', 'CHECKING', 'EMILY DAVIS', 2000.00, 2000.00, 'ACTIVE'),
('987654505019', 'M3000023', 'ACH Checking', 'CHECKING', 'MICHAEL BROWN', 2000.00, 2000.00, 'ACTIVE'),
('987654506019', 'M3000024', 'ACH Checking', 'CHECKING', 'SARAH WILSON', 2000.00, 2000.00, 'ACTIVE'),
('987654507019', 'M3000025', 'ACH Checking', 'CHECKING', 'DAVID TAYLOR', 2000.00, 2000.00, 'ACTIVE'),
('987654508019', 'M3000026', 'ACH Checking', 'CHECKING', 'LAURA ANDERSON', 2000.00, 2000.00, 'ACTIVE'),
('987654509019', 'M3000027', 'ACH Checking', 'CHECKING', 'JAMES THOMAS', 2000.00, 2000.00, 'ACTIVE'),
('987654510019', 'M3000028', 'ACH Checking', 'CHECKING', 'LINDA JACKSON', 2000.00, 2000.00, 'ACTIVE'),
('987654601019', 'M3000029', 'ACH Checking', 'CHECKING', 'JOHN DOE', 2000.00, 2000.00, 'ACTIVE'),
('987654602019', 'M3000030', 'ACH Checking', 'CHECKING', 'JANE SMITH', 2000.00, 2000.00, 'ACTIVE'),
('987654603019', 'M3000031', 'ACH Checking', 'CHECKING', 'ROBERT JOHNSON', 2000.00, 2000.00, 'ACTIVE'),
('987654604019', 'M3000032', 'ACH Checking', 'CHECKING', 'EMILY DAVIS', 2000.00, 2000.00, 'ACTIVE'),
('987654605019', 'M3000033', 'ACH Checking', 'CHECKING', 'MICHAEL BROWN', 2000.00, 2000.00, 'ACTIVE'),
('987654606019', 'M3000034', 'ACH Checking', 'CHECKING', 'SARAH WILSON', 2000.00, 2000.00, 'ACTIVE'),
('987654607019', 'M3000035', 'ACH Checking', 'CHECKING', 'DAVID TAYLOR', 2000.00, 2000.00, 'ACTIVE'),
('987654608019', 'M3000036', 'ACH Checking', 'CHECKING', 'LAURA ANDERSON', 2000.00, 2000.00, 'ACTIVE'),
('987654609019', 'M3000037', 'ACH Checking', 'CHECKING', 'JAMES THOMAS', 2000.00, 2000.00, 'ACTIVE'),
('987654610019', 'M3000038', 'ACH Checking', 'CHECKING', 'LINDA JACKSON', 2000.00, 2000.00, 'ACTIVE'),
('987654701019', 'M3000039', 'ACH Checking', 'CHECKING', 'INVESTOR ONE', 2000.00, 2000.00, 'ACTIVE'),
('987654702019', 'M3000040', 'ACH Checking', 'CHECKING', 'INVESTOR TWO', 2000.00, 2000.00, 'ACTIVE'),
('987654703019', 'M3000041', 'ACH Checking', 'CHECKING', 'INVESTOR THREE', 2000.00, 2000.00, 'ACTIVE'),
('987654704019', 'M3000042', 'ACH Checking', 'CHECKING', 'INVESTOR FOUR', 2000.00, 2000.00, 'ACTIVE'),
('987654705019', 'M3000043', 'ACH Checking', 'CHECKING', 'INVESTOR FIVE', 2000.00, 2000.00, 'ACTIVE'),
('987654706019', 'M3000044', 'ACH Checking', 'CHECKING', 'INVESTOR SIX', 2000.00, 2000.00, 'ACTIVE'),
('987654707019', 'M3000045', 'ACH Checking', 'CHECKING', 'INVESTOR SEVEN', 2000.00, 2000.00, 'ACTIVE'),
('987654708019', 'M3000046', 'ACH Checking', 'CHECKING', 'INVESTOR EIGHT', 2000.00, 2000.00, 'ACTIVE'),
('987654709019', 'M3000047', 'ACH Checking', 'CHECKING', 'INVESTOR NINE', 2000.00, 2000.00, 'ACTIVE'),
('987654710019', 'M3000048', 'ACH Checking', 'CHECKING', 'INVESTOR TEN', 2000.00, 2000.00, 'ACTIVE'),
('555000328019', 'M3000049', 'ACH Checking', 'CHECKING', 'LAURA ANDERSON', 2000.00, 2000.00, 'ACTIVE');

INSERT INTO ${recv}.ACHRecvAcctSubs (nocAcctNo, nocRoutingNo, subsAcctNo, subsRoutingNo, acctType, acctOwner, preNocID) VALUES
('987654328019', '123456780', '555000328019', '123456780', 'CHECKING', 'LAURA ANDERSON', NULL);
INSERT INTO ${recv}.ACHRecvStopPayment (acctNo, companyID, amount) VALUES ('987654323019', '2987654321', NULL);
INSERT INTO ${recv}.ACHRecvBlockedOriginator (acctNo, companyID) VALUES ('987654322019', '2987654321');
