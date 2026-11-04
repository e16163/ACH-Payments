-- Receiving database (Ba's "ACH Receiving Processing Engine" tables), in ${recv} (ach_receiving).
-- Part 1: Ba's CREATE TABLEs, copied unchanged from his design doc (only the database prefix added).
-- Part 2: additions approved in docs/DB-MODEL.md section 0. Nothing Ba defined is changed.


-- Ba: ACHRecvAccount
CREATE TABLE ${recv}.ACHRecvAccount (
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
    CONSTRAINT PK_ACHRecvAccount PRIMARY KEY (recvAcctSerNo, recvAcctVersion),
    
    -- Recommended Indexes for Search & Performance
    INDEX IX_ACHRecvAccount_MemberNo (memberNo),
    INDEX IX_ACHRecvAccount_AcctNo (acctNo),
    INDEX IX_ACHRecvAccount_Status (acctStatus)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHRecvFile
CREATE TABLE ${recv}.ACHRecvFile (
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
    CONSTRAINT PK_ACHRecvFile PRIMARY KEY (fileID),
    INDEX IX_ACHRecvFile_HeaderDt (fileHeaderDt),
    INDEX IX_ACHRecvFile_FedStatus (fedStatus)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHRecvBatch
CREATE TABLE ${recv}.ACHRecvBatch (
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
    CONSTRAINT PK_ACHRecvBatch PRIMARY KEY (batchID),
    CONSTRAINT FK_ACHRecvBatch_ACHRecvFile FOREIGN KEY (fileID) REFERENCES ${recv}.ACHRecvFile(fileID) ON DELETE CASCADE,
    INDEX IX_ACHRecvBatch_FileID (fileID),
    INDEX IX_ACHRecvBatch_EffectiveDt (effectiveDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHRecvTransaction
CREATE TABLE ${recv}.ACHRecvTransaction (
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
    CONSTRAINT PK_ACHRecvTransaction PRIMARY KEY (tranID),
    
    -- Recommended Indexes for ACH Lookup Performance
    INDEX IX_ACHRecvTransaction_TraceNo (traceNo),
    INDEX IX_ACHRecvTransaction_File_Batch (fileID, batchID),
    INDEX IX_ACHRecvTransaction_EffectiveDt (effectiveDt),
    INDEX IX_ACHRecvTransaction_FedStatus (fedStatus)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHRecvReturn
CREATE TABLE ${recv}.ACHRecvReturn (
    returnID BIGINT AUTO_INCREMENT NOT NULL,
    tranID BIGINT NULL,
    typeID VARCHAR(30) NULL,
    sourceID VARCHAR(30) NULL,
    recvTraceNo VARCHAR(15) NULL,
    returnTraceNo VARCHAR(15) NOT NULL,
    amount DECIMAL(13, 2) NOT NULL,
    acctName VARCHAR(100) NULL,
    returnStatus VARCHAR(20) DEFAULT 'RECEIVED' NOT NULL,
    returnCode CHAR(3) NOT NULL,
    companyName VARCHAR(16) NOT NULL,
    companyID VARCHAR(10) NOT NULL,
    discretionaryData VARCHAR(20) NULL,
    returnAcctNo VARCHAR(34) NOT NULL,
    returnRtn CHAR(9) NOT NULL,
    returnDt DATE NOT NULL,
    settlementDt DATE NULL,
    lastModTm DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHRecvReturn PRIMARY KEY (returnID),
    INDEX IX_ACHRecvReturn_TranID (tranID),
    INDEX IX_ACHRecvReturn_ReturnCode (returnCode),
    INDEX IX_ACHRecvReturn_ReturnDt (returnDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHRecvNOC
CREATE TABLE ${recv}.ACHRecvNOC (
    nocID BIGINT AUTO_INCREMENT NOT NULL,
    tranID BIGINT NULL,
    sourceID VARCHAR(30) NULL,
    nocCode CHAR(3) NOT NULL,
    correctedData VARCHAR(100) NOT NULL,
    nocTraceNo VARCHAR(15) NOT NULL,
    origTraceNo VARCHAR(15) NOT NULL,
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHRecvNOC PRIMARY KEY (nocID),
    INDEX IX_ACHRecvNOC_TranID (tranID),
    INDEX IX_ACHRecvNOC_NocCode (nocCode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHRecvAcctSubs
CREATE TABLE ${recv}.ACHRecvAcctSubs (
    acctSubsID BIGINT AUTO_INCREMENT NOT NULL,
    nocAcctNo VARCHAR(34) NOT NULL,
    nocRoutingNo CHAR(9) NOT NULL,
    subsAcctNo VARCHAR(34) NOT NULL,
    subsRoutingNo CHAR(9) NOT NULL,
    acctType VARCHAR(15) NOT NULL,
    acctOwner VARCHAR(100) NOT NULL,
    acctStatus VARCHAR(20) DEFAULT 'ACTIVE' NOT NULL,
    sourceID VARCHAR(30) NULL,
    thridPartyFlag TINYINT(1) DEFAULT 0 NOT NULL,
    creditFlag TINYINT(1) DEFAULT 1 NOT NULL,
    debitFlag TINYINT(1) DEFAULT 1 NOT NULL,
    preNocID BIGINT NULL,
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_ACHRecvAcctSubs PRIMARY KEY (acctSubsID),
    INDEX IX_ACHRecvAcctSubs_NocAcct (nocAcctNo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHRecvTransCodeSubs
CREATE TABLE ${recv}.ACHRecvTransCodeSubs (
    nocID BIGINT NOT NULL,
    nocAcctNo VARCHAR(34) NOT NULL,
    nocRtn CHAR(9) NOT NULL,
    nocTranCode CHAR(2) NOT NULL,
    subsTranCode CHAR(2) NOT NULL,
    companyID VARCHAR(10) NOT NULL,
    lastModTm DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHRecvTransCodeSubs PRIMARY KEY (nocID, nocAcctNo, nocRtn),
    INDEX IX_ACHRecvTransCodeSubs_CompanyID (companyID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;


-- ===== Part 2: additions (DB-MODEL.md section 0) =====

-- F9: who the file is from and to
ALTER TABLE ${recv}.ACHRecvFile
    ADD COLUMN immedDest CHAR(10) NULL,
    ADD COLUMN immedOrigin CHAR(10) NULL;

-- F4: Service Class Code (200/220/225)
ALTER TABLE ${recv}.ACHRecvBatch
    ADD COLUMN serviceClassCode CHAR(3) NULL;

-- F2, F3: RDFI routing + check digit, Individual ID, addenda indicator; link to the member account; F5 FKs
ALTER TABLE ${recv}.ACHRecvTransaction
    ADD COLUMN recvRtn CHAR(9) NULL,
    ADD COLUMN individualID VARCHAR(15) NULL,
    ADD COLUMN addendaInd CHAR(1) NULL,
    ADD COLUMN recvAcctSerNo VARCHAR(30) NULL,
    ADD COLUMN recvAcctVersion INT NULL,
    ADD CONSTRAINT FK_ACHRecvTransaction_ACHRecvFile FOREIGN KEY (fileID) REFERENCES ${recv}.ACHRecvFile(fileID),
    ADD CONSTRAINT FK_ACHRecvTransaction_ACHRecvBatch FOREIGN KEY (batchID) REFERENCES ${recv}.ACHRecvBatch(batchID),
    ADD CONSTRAINT FK_ACHRecvTransaction_ACHRecvAccount FOREIGN KEY (recvAcctSerNo, recvAcctVersion)
        REFERENCES ${recv}.ACHRecvAccount(recvAcctSerNo, recvAcctVersion);

-- S5: return deadline (2 banking days, or 60 days for unauthorized consumer debits) and WSUD; F5 FK
ALTER TABLE ${recv}.ACHRecvReturn
    ADD COLUMN dueDt DATE NULL,
    ADD COLUMN wsudFlag TINYINT(1) NOT NULL DEFAULT 0,
    ADD CONSTRAINT FK_ACHRecvReturn_ACHRecvTransaction FOREIGN KEY (tranID) REFERENCES ${recv}.ACHRecvTransaction(tranID);

-- S3: NOC workflow (DRAFT -> APPROVED -> SENT), approver, 2-banking-day deadline; F5 FK
ALTER TABLE ${recv}.ACHRecvNOC
    ADD COLUMN nocStatus VARCHAR(15) NOT NULL DEFAULT 'DRAFT',
    ADD COLUMN approvedBy VARCHAR(50) NULL,
    ADD COLUMN dueDt DATE NULL,
    ADD CONSTRAINT FK_ACHRecvNOC_ACHRecvTransaction FOREIGN KEY (tranID) REFERENCES ${recv}.ACHRecvTransaction(tranID);

-- F5 FKs
ALTER TABLE ${recv}.ACHRecvAcctSubs
    ADD CONSTRAINT FK_ACHRecvAcctSubs_ACHRecvNOC FOREIGN KEY (preNocID) REFERENCES ${recv}.ACHRecvNOC(nocID);
ALTER TABLE ${recv}.ACHRecvTransCodeSubs
    ADD CONSTRAINT FK_ACHRecvTransCodeSubs_ACHRecvNOC FOREIGN KEY (nocID) REFERENCES ${recv}.ACHRecvNOC(nocID);

-- M1: Receiving UI actions (accept, correct, intercept, recall), same shape as Ba's action tables
CREATE TABLE ${recv}.ACHRecvAction (
    tranID BIGINT NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHRecvAction PRIMARY KEY (tranID, processID, actionID),
    CONSTRAINT FK_ACHRecvAction_ACHRecvTransaction FOREIGN KEY (tranID) REFERENCES ${recv}.ACHRecvTransaction(tranID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- M2: Request for Return ("recall") from the sending bank; answer within 10 banking days (R06/R31 or declined)
CREATE TABLE ${recv}.ACHRecvReturnRequest (
    requestID BIGINT AUTO_INCREMENT NOT NULL,
    tranID BIGINT NOT NULL,
    requestingRtn CHAR(9) NOT NULL,
    requestReason VARCHAR(100) NULL,
    receivedDt DATE NOT NULL,
    dueDt DATE NOT NULL,
    decision VARCHAR(15) NULL COMMENT 'R06, R31 or DECLINED',
    decisionTm DATETIME NULL,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHRecvReturnRequest PRIMARY KEY (requestID),
    CONSTRAINT FK_ACHRecvReturnRequest_ACHRecvTransaction FOREIGN KEY (tranID) REFERENCES ${recv}.ACHRecvTransaction(tranID),
    INDEX IX_ACHRecvReturnRequest_DueDt (dueDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- M3: addenda records (05 payment info, 98 NOC, 99 return), positions 4-94 kept as received
CREATE TABLE ${recv}.ACHRecvAddenda (
    addendaID BIGINT AUTO_INCREMENT NOT NULL,
    tranID BIGINT NOT NULL,
    seqNo INT NOT NULL,
    typeCode CHAR(2) NOT NULL,
    content VARCHAR(91) NOT NULL,
    CONSTRAINT PK_ACHRecvAddenda PRIMARY KEY (addendaID),
    CONSTRAINT FK_ACHRecvAddenda_ACHRecvTransaction FOREIGN KEY (tranID) REFERENCES ${recv}.ACHRecvTransaction(tranID),
    INDEX IX_ACHRecvAddenda_TranID (tranID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
