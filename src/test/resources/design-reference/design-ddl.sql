-- the original DDL, copied unchanged from docs/design/ACH-Payments-System-Design.docx (30 CREATE TABLEs).
-- Used by DesignConformanceTest to prove our schema keeps every the designer column, type, default and key.

CREATE TABLE ACHOrigTransaction (
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

CREATE TABLE ACHOriginator (
    originatorID VARCHAR(10) NOT NULL,
    taxID VARCHAR(9) NULL,
    routingNo CHAR(9) NOT NULL,
    name VARCHAR(50) NOT NULL,
    lastModTm DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHOriginator PRIMARY KEY (originatorID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigRecvAccount (
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

CREATE TABLE ACHOrigLimitRule (
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

CREATE TABLE ACHOriginatorTransfer (
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

CREATE TABLE ACHOperator (
    operatorID VARCHAR(10) NOT NULL,
    routingNo CHAR(9) NOT NULL,
    name VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHOperator PRIMARY KEY (operatorID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Contact (
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

CREATE TABLE ACHOrigFile (
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

CREATE TABLE ACHOrigBatch (
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
    CONSTRAINT FK_ACHOrigBatch_ACHOrigFile FOREIGN KEY (fileID) REFERENCES ACHOrigFile(fileID) ON DELETE CASCADE,
    INDEX IX_ACHOrigBatch_FileID (fileID),
    INDEX IX_ACHOrigBatch_EffectiveDt (effectiveDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Company (
    companyID VARCHAR(10) NOT NULL,
    companyDesc VARCHAR(100) NULL,
    originatorID VARCHAR(10) NOT NULL,
    CONSTRAINT PK_Company PRIMARY KEY (companyID),
    CONSTRAINT FK_Company_ACHOriginator FOREIGN KEY (originatorID) REFERENCES ACHOriginator(originatorID),
    INDEX IX_Company_OriginatorID (originatorID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Source (
    sourceID VARCHAR(30) NOT NULL,
    sourceName VARCHAR(50) NOT NULL,
    sourceDesc VARCHAR(255) NULL,
    CONSTRAINT PK_Source PRIMARY KEY (sourceID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE Action (
    actionID INT AUTO_INCREMENT NOT NULL,
    actionName VARCHAR(50) NOT NULL,
    CONSTRAINT PK_Action PRIMARY KEY (actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigReturn (
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
    CONSTRAINT PK_ACHOrigReturn PRIMARY KEY (returnID),
    INDEX IX_ACHOrigReturn_TranID (tranID),
    INDEX IX_ACHOrigReturn_ReturnCode (returnCode),
    INDEX IX_ACHOrigReturn_ReturnDt (returnDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigNOC (
    nocID BIGINT AUTO_INCREMENT NOT NULL,
    tranID BIGINT NULL,
    sourceID VARCHAR(30) NULL,
    nocCode CHAR(3) NOT NULL,
    correctedData VARCHAR(100) NOT NULL,
    nocTraceNo VARCHAR(15) NOT NULL,
    origTraceNo VARCHAR(15) NOT NULL,
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHOrigNOC PRIMARY KEY (nocID),
    INDEX IX_ACHOrigNOC_TranID (tranID),
    INDEX IX_ACHOrigNOC_NocCode (nocCode)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigAcctSubs (
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
    CONSTRAINT PK_ACHOrigAcctSubs PRIMARY KEY (acctSubsID),
    INDEX IX_ACHOrigAcctSubs_NocAcct (nocAcctNo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigTransCodeSubs (
    nocID BIGINT NOT NULL,
    nocAcctNo VARCHAR(34) NOT NULL,
    nocRtn CHAR(9) NOT NULL,
    nocTranCode CHAR(2) NOT NULL,
    subsTranCode CHAR(2) NOT NULL,
    companyID VARCHAR(10) NOT NULL,
    lastModTm DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHOrigTransCodeSubs PRIMARY KEY (nocID, nocAcctNo, nocRtn),
    INDEX IX_ACHOrigTransCodeSubs_CompanyID (companyID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigResubAction (
    fileID VARCHAR(30) NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key Constraint
    CONSTRAINT PK_ACHOrigResubAction PRIMARY KEY (fileID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigNOCAction (
    nocID BIGINT NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key Constraint
    CONSTRAINT PK_ACHOrigNOCAction PRIMARY KEY (nocID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigFedAction (
    fileID VARCHAR(30) NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key Constraint
    CONSTRAINT PK_ACHOrigFedAction PRIMARY KEY (fileID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHOrigReversalAction (
    fileID VARCHAR(30) NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key Constraint
    CONSTRAINT PK_ACHOrigReversalAction PRIMARY KEY (fileID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHRecvTransaction (
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

CREATE TABLE ACHRecvAccount (
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

CREATE TABLE ACHRecvFile (
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

CREATE TABLE ACHRecvBatch (
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
    CONSTRAINT FK_ACHRecvBatch_ACHRecvFile FOREIGN KEY (fileID) REFERENCES ACHRecvFile(fileID) ON DELETE CASCADE,
    INDEX IX_ACHRecvBatch_FileID (fileID),
    INDEX IX_ACHRecvBatch_EffectiveDt (effectiveDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE ACHRecvReturn (
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

CREATE TABLE ACHRecvNOC (
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

CREATE TABLE ACHRecvAcctSubs (
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

CREATE TABLE ACHRecvTransCodeSubs (
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

CREATE TABLE AppParam (
    systemName VARCHAR(30) NOT NULL,
    paramName VARCHAR(50) NOT NULL,
    paramDesc VARCHAR(255) NULL,
    paramRawType VARCHAR(20) NOT NULL DEFAULT 'STRING',
    paramRawValue VARCHAR(500) NOT NULL,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key (Allows same paramName across different systems)
    CONSTRAINT PK_AppParam PRIMARY KEY (systemName, paramName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE AppLog (
    logAppId BIGINT AUTO_INCREMENT NOT NULL,
    logLevel VARCHAR(10) NOT NULL,
    logMesg VARCHAR(1000) NOT NULL,
    logExceptionData TEXT NULL,
    logTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    
    -- Primary Key Constraint
    CONSTRAINT PK_AppLog PRIMARY KEY (logAppId),
    
    -- Indexes for efficient querying by Log Level and Time Period
    INDEX IX_AppLog_LogLevel (logLevel),
    INDEX IX_AppLog_LogTm (logTm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
