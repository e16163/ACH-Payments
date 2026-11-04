-- Origination database (Ba's "ACH Origination Engine and UI" tables), in ${orig} (ach_origination).
-- Part 1: Ba's CREATE TABLEs, copied unchanged from his design doc (only the database prefix added).
-- Part 2: additions approved in docs/DB-MODEL.md section 0 and USE-CASES.md T2/T8. Nothing Ba defined is changed.


-- Ba: ACHOrigTransaction
CREATE TABLE ${orig}.ACHOrigTransaction (
    tranID BIGINT AUTO_INCREMENT NOT NULL,
    transferSerNo VARCHAR(30) NULL,
    transferVersion INT DEFAULT 1 NOT NULL,
    fileID VARCHAR(30) NULL,
    batchID VARCHAR(30) NULL,
    creditFlag TINYINT(1) NOT NULL COMMENT '1=Credit, 0=Debit',
    tranCode CHAR(2) NOT NULL,
    companyName VARCHAR(16) NOT NULL,
    companyID VARCHAR(10) NOT NULL,
    companyDiscData VARCHAR(20) NULL,
    stdEntryCode CHAR(3) NOT NULL,
    companyEntryDesc VARCHAR(10) NULL,
    descriptiveDt DATE NULL,
    effectiveDt DATE NOT NULL,
    settlementDt DATE NULL,
    originatorID VARCHAR(10) NOT NULL,
    amount DECIMAL(13, 2) NOT NULL,
    recvAcctID VARCHAR(17) NOT NULL,
    recvName VARCHAR(22) NOT NULL,
    transactionDiscData VARCHAR(2) NULL,
    traceNo VARCHAR(15) NOT NULL,
    fedStatus VARCHAR(15) DEFAULT 'PENDING' NOT NULL,
    sourceID VARCHAR(30) NULL,
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    
    -- Primary Key Constraint
    CONSTRAINT PK_ACHOrigTransaction PRIMARY KEY (tranID),
    
    -- Recommended Indexes for ACH Lookup Performance
    INDEX IX_ACHOrigTransaction_TraceNo (traceNo),
    INDEX IX_ACHOrigTransaction_File_Batch (fileID, batchID),
    INDEX IX_ACHOrigTransaction_EffectiveDt (effectiveDt),
    INDEX IX_ACHOrigTransaction_FedStatus (fedStatus)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOriginator
CREATE TABLE ${orig}.ACHOriginator (
    originatorID VARCHAR(10) NOT NULL,
    taxID VARCHAR(9) NULL,
    routingNo CHAR(9) NOT NULL,
    name VARCHAR(50) NOT NULL,
    lastModTm DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHOriginator PRIMARY KEY (originatorID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOrigRecvAccount
CREATE TABLE ${orig}.ACHOrigRecvAccount (
    recvAcctSerNo VARCHAR(30) NOT NULL,
    recvAcctVersion INT NOT NULL DEFAULT 1,
    memberNo VARCHAR(20) NOT NULL,
    acctNo VARCHAR(34) NOT NULL,
    routingNo CHAR(9) NOT NULL,
    acctType VARCHAR(15) NOT NULL,
    acctOwner VARCHAR(100) NOT NULL,
    acctStatus VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    sourceID VARCHAR(30) NULL,
    thridPartyFlag TINYINT(1) DEFAULT 0 NOT NULL,
    creditFlag TINYINT(1) DEFAULT 1 NOT NULL,
    debitFlag TINYINT(1) DEFAULT 1 NOT NULL,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key
    CONSTRAINT PK_ACHOrigRecvAccount PRIMARY KEY (recvAcctSerNo, recvAcctVersion),
    
    -- Recommended Indexes for Search & Performance
    INDEX IX_ACHOrigRecvAccount_MemberNo (memberNo),
    INDEX IX_ACHOrigRecvAccount_AcctNo (acctNo),
    INDEX IX_ACHOrigRecvAccount_Status (acctStatus)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOrigLimitRule
CREATE TABLE ${orig}.ACHOrigLimitRule (
    ruleID INT AUTO_INCREMENT NOT NULL,
    ruleName VARCHAR(50) NOT NULL,
    acctType VARCHAR(15) NOT NULL DEFAULT 'ALL',
    dayNo INT NOT NULL DEFAULT 1 COMMENT 'Limit duration in days (e.g., 1=Daily, 30=Monthly)',
    limitAmt DECIMAL(13, 2) NOT NULL,
    status VARCHAR(15) NOT NULL DEFAULT 'ACTIVE',
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Primary Key Constraint
    CONSTRAINT PK_ACHOrigLimitRule PRIMARY KEY (ruleID),
    
    -- Recommended Indexes for Performance
    INDEX IX_ACHOrigLimitRule_Status (status),
    INDEX IX_ACHOrigLimitRule_AcctType (acctType)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOriginatorTransfer
CREATE TABLE ${orig}.ACHOriginatorTransfer (
    transferSerNo VARCHAR(30) NOT NULL,
    transferVersion INT NOT NULL DEFAULT 1,
    memberNo VARCHAR(20) NOT NULL,
    pTransferSerNo VARCHAR(30) NULL,
    recvAcctSerNo VARCHAR(30) NOT NULL,
    recvAcctVersion INT NOT NULL DEFAULT 1,
    origAcctNo VARCHAR(34) NOT NULL,
    origAcctType VARCHAR(15) NOT NULL,
    origAcctGrp VARCHAR(20) NULL,
    recurringFlag TINYINT(1) DEFAULT 0 NOT NULL,
    intAcctSpecFlag TINYINT(1) DEFAULT 0 NOT NULL,
    nextPmtDt DATE NULL,
    pmtFrequency VARCHAR(15) NULL,
    twMnthFrequency1 INT NULL,
    twMnthFrequency2 INT NULL,
    transferReqNo INT NULL,
    transferReqRemaining INT NULL,
    transferAmt DECIMAL(13, 2) NOT NULL,
    processStatus VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    processTm DATETIME NULL,
    ofacStatus VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    ofacTm DATETIME NULL,
    memoStatus VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    memoTm DATETIME NULL,
    postingStatus VARCHAR(20) DEFAULT 'PENDING' NOT NULL,
    postingTm DATETIME NULL,
    postingSystem VARCHAR(30) NULL,
    sourceID VARCHAR(30) NULL,
    originatorID VARCHAR(10) NOT NULL,
    lastModTm DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key
    CONSTRAINT PK_ACHOriginatorTransfer PRIMARY KEY (transferSerNo, transferVersion),
    
    -- Recommended Indexes for Performance & Lookups
    INDEX IX_ACHOriginatorTransfer_MemberNo (memberNo),
    INDEX IX_ACHOriginatorTransfer_NextPmtDt (nextPmtDt),
    INDEX IX_ACHOriginatorTransfer_Status (processStatus, ofacStatus, postingStatus)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOperator
CREATE TABLE ${orig}.ACHOperator (
    operatorID VARCHAR(10) NOT NULL,
    routingNo CHAR(9) NOT NULL,
    name VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHOperator PRIMARY KEY (operatorID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: Contact
CREATE TABLE ${orig}.Contact (
    contactID BIGINT AUTO_INCREMENT NOT NULL,
    contactPerson VARCHAR(100) NOT NULL,
    street1 VARCHAR(100) NOT NULL,
    street2 VARCHAR(100) NULL,
    city VARCHAR(50) NOT NULL,
    zip VARCHAR(10) NOT NULL,
    state CHAR(2) NOT NULL,
    phone1 VARCHAR(20) NOT NULL,
    phone2 VARCHAR(20) NULL,
    fax VARCHAR(20) NULL,
    emailAddr VARCHAR(255) NULL,
    Url VARCHAR(255) NULL,
    CONSTRAINT PK_Contact PRIMARY KEY (contactID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOrigFile
CREATE TABLE ${orig}.ACHOrigFile (
    fileID VARCHAR(30) NOT NULL,
    processID VARCHAR(30) NULL,
    fileIDModifier CHAR(1) DEFAULT 'A' NOT NULL,
    fileName VARCHAR(255) NOT NULL,
    fileHeaderDt DATE NOT NULL,
    fileHeaderTm TIME NOT NULL,
    fileDesc VARCHAR(100) NULL,
    fileVolSer VARCHAR(6) NULL,
    totalDebitCount INT DEFAULT 0 NOT NULL,
    totalCreditCount INT DEFAULT 0 NOT NULL,
    totalDebitAmt DECIMAL(13, 2) DEFAULT 0.00 NOT NULL,
    totalCreditAmt DECIMAL(13, 2) DEFAULT 0.00 NOT NULL,
    fedStatus VARCHAR(15) DEFAULT 'PENDING' NOT NULL,
    sendTm DATETIME NULL,
    acknowledgedTm DATETIME NULL,
    CONSTRAINT PK_ACHOrigFile PRIMARY KEY (fileID),
    INDEX IX_ACHOrigFile_HeaderDt (fileHeaderDt),
    INDEX IX_ACHOrigFile_FedStatus (fedStatus)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOrigBatch
CREATE TABLE ${orig}.ACHOrigBatch (
    batchID VARCHAR(30) NOT NULL,
    fileID VARCHAR(30) NOT NULL,
    batchNo INT NOT NULL,
    companyName VARCHAR(16) NOT NULL,
    companyID VARCHAR(10) NOT NULL,
    companyDiscData VARCHAR(20) NULL,
    stdEntryCode CHAR(3) NOT NULL,
    companyEntryDesc VARCHAR(10) NULL,
    descriptiveDt DATE NULL,
    effectiveDt DATE NOT NULL,
    settlementDt DATE NULL,
    odfiID VARCHAR(10) NOT NULL,
    totalBatchCount INT DEFAULT 0 NOT NULL,
    totalDebitAmt DECIMAL(13, 2) DEFAULT 0.00 NOT NULL,
    totalCreditAmt DECIMAL(13, 2) DEFAULT 0.00 NOT NULL,
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    Process VARCHAR(50) NULL,
    processID VARCHAR(30) NULL,
    processName VARCHAR(100) NULL,
    CONSTRAINT PK_ACHOrigBatch PRIMARY KEY (batchID),
    CONSTRAINT FK_ACHOrigBatch_ACHOrigFile FOREIGN KEY (fileID) REFERENCES ${orig}.ACHOrigFile(fileID) ON DELETE CASCADE,
    INDEX IX_ACHOrigBatch_FileID (fileID),
    INDEX IX_ACHOrigBatch_EffectiveDt (effectiveDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: Company
CREATE TABLE ${orig}.Company (
    companyID VARCHAR(10) NOT NULL,
    companyDesc VARCHAR(100) NULL,
    originatorID VARCHAR(10) NOT NULL,
    CONSTRAINT PK_Company PRIMARY KEY (companyID),
    CONSTRAINT FK_Company_ACHOriginator FOREIGN KEY (originatorID) REFERENCES ${orig}.ACHOriginator(originatorID),
    INDEX IX_Company_OriginatorID (originatorID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: Source
CREATE TABLE ${orig}.Source (
    sourceID VARCHAR(30) NOT NULL,
    sourceName VARCHAR(50) NOT NULL,
    sourceDesc VARCHAR(255) NULL,
    CONSTRAINT PK_Source PRIMARY KEY (sourceID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ===== Part 2: additions =====

-- F9: who the file is from and to
ALTER TABLE ${orig}.ACHOrigFile
    ADD COLUMN immedDest CHAR(10) NULL,
    ADD COLUMN immedOrigin CHAR(10) NULL;

-- F4: Service Class Code (200/220/225)
ALTER TABLE ${orig}.ACHOrigBatch
    ADD COLUMN serviceClassCode CHAR(3) NULL;

-- S6: link originators to Ba's Contact table
ALTER TABLE ${orig}.ACHOriginator
    ADD COLUMN contactID BIGINT NULL,
    ADD CONSTRAINT FK_ACHOriginator_Contact FOREIGN KEY (contactID) REFERENCES ${orig}.Contact(contactID);

-- F5: channel lookups
ALTER TABLE ${orig}.ACHOrigRecvAccount
    ADD CONSTRAINT FK_ACHOrigRecvAccount_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);

-- T8: the inputs UC-ACH-ORG-01 collects (names as Ba's spec maps them), the fee shown, and creation time; F5 FKs
ALTER TABLE ${orig}.ACHOriginatorTransfer
    ADD COLUMN effectiveDt DATE NULL,
    ADD COLUMN companyEntryDesc VARCHAR(10) NULL,
    ADD COLUMN feeAmt DECIMAL(13, 2) NOT NULL DEFAULT 0.00,
    ADD COLUMN createTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD CONSTRAINT FK_ACHOriginatorTransfer_ACHOrigRecvAccount FOREIGN KEY (recvAcctSerNo, recvAcctVersion)
        REFERENCES ${orig}.ACHOrigRecvAccount(recvAcctSerNo, recvAcctVersion),
    ADD CONSTRAINT FK_ACHOriginatorTransfer_ACHOriginator FOREIGN KEY (originatorID) REFERENCES ${orig}.ACHOriginator(originatorID),
    ADD CONSTRAINT FK_ACHOriginatorTransfer_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);

-- F2, F3: receiving bank routing (with check digit), Individual ID, addenda indicator;
-- F12/Q4: a reversal or retry is a new row pointing at the original; F5 FKs
ALTER TABLE ${orig}.ACHOrigTransaction
    ADD COLUMN recvRtn CHAR(9) NULL,
    ADD COLUMN individualID VARCHAR(15) NULL,
    ADD COLUMN addendaInd CHAR(1) NULL,
    ADD COLUMN parentTranID BIGINT NULL,
    ADD CONSTRAINT FK_ACHOrigTransaction_ACHOrigFile FOREIGN KEY (fileID) REFERENCES ${orig}.ACHOrigFile(fileID),
    ADD CONSTRAINT FK_ACHOrigTransaction_ACHOrigBatch FOREIGN KEY (batchID) REFERENCES ${orig}.ACHOrigBatch(batchID),
    ADD CONSTRAINT FK_ACHOrigTransaction_ACHOriginator FOREIGN KEY (originatorID) REFERENCES ${orig}.ACHOriginator(originatorID),
    ADD CONSTRAINT FK_ACHOrigTransaction_Transfer FOREIGN KEY (transferSerNo, transferVersion)
        REFERENCES ${orig}.ACHOriginatorTransfer(transferSerNo, transferVersion),
    ADD CONSTRAINT FK_ACHOrigTransaction_Parent FOREIGN KEY (parentTranID) REFERENCES ${orig}.ACHOrigTransaction(tranID),
    ADD CONSTRAINT FK_ACHOrigTransaction_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);

-- F5: receiving-side sourceID columns point at Ba's Source table too (same MySQL server, so cross-database FKs work)
ALTER TABLE ${recv}.ACHRecvTransaction ADD CONSTRAINT FK_ACHRecvTransaction_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);
ALTER TABLE ${recv}.ACHRecvAccount ADD CONSTRAINT FK_ACHRecvAccount_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);
ALTER TABLE ${recv}.ACHRecvReturn ADD CONSTRAINT FK_ACHRecvReturn_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);
ALTER TABLE ${recv}.ACHRecvNOC ADD CONSTRAINT FK_ACHRecvNOC_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);
ALTER TABLE ${recv}.ACHRecvAcctSubs ADD CONSTRAINT FK_ACHRecvAcctSubs_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);

-- M3: addenda records for originated entries (e.g. 05 payment info)
CREATE TABLE ${orig}.ACHOrigAddenda (
    addendaID BIGINT AUTO_INCREMENT NOT NULL,
    tranID BIGINT NOT NULL,
    seqNo INT NOT NULL,
    typeCode CHAR(2) NOT NULL,
    content VARCHAR(91) NOT NULL,
    CONSTRAINT PK_ACHOrigAddenda PRIMARY KEY (addendaID),
    CONSTRAINT FK_ACHOrigAddenda_ACHOrigTransaction FOREIGN KEY (tranID) REFERENCES ${orig}.ACHOrigTransaction(tranID),
    INDEX IX_ACHOrigAddenda_TranID (tranID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- T2: the credit union is the originator for retail members' external transfers (fictional routing number that passes the 3-7-1 check)
INSERT INTO ${orig}.ACHOriginator (originatorID, taxID, routingNo, name, lastModBy) VALUES
('9000000001', NULL, '123456780', 'DEMO CREDIT UNION', 'SYS_ADMIN');
