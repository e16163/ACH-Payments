-- Back office (Ba's returns, NOCs, substitutions and action logs on the origination side), in ${orig} (ach_origination).
-- Part 1: Ba's CREATE TABLEs, copied unchanged from his design doc (only the database prefix added).
-- Part 2: additions approved in docs/DB-MODEL.md section 0 (F5, F13, S4). Nothing Ba defined is changed.

-- Ba: Action
CREATE TABLE ${orig}.Action (
    actionID INT AUTO_INCREMENT NOT NULL,
    actionName VARCHAR(50) NOT NULL,
    CONSTRAINT PK_Action PRIMARY KEY (actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOrigReturn
CREATE TABLE ${orig}.ACHOrigReturn (
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

-- Ba: ACHOrigNOC
CREATE TABLE ${orig}.ACHOrigNOC (
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

-- Ba: ACHOrigAcctSubs
CREATE TABLE ${orig}.ACHOrigAcctSubs (
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

-- Ba: ACHOrigTransCodeSubs
CREATE TABLE ${orig}.ACHOrigTransCodeSubs (
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

-- Ba: ACHOrigResubAction
CREATE TABLE ${orig}.ACHOrigResubAction (
    fileID VARCHAR(30) NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key Constraint
    CONSTRAINT PK_ACHOrigResubAction PRIMARY KEY (fileID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOrigNOCAction
CREATE TABLE ${orig}.ACHOrigNOCAction (
    nocID BIGINT NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key Constraint
    CONSTRAINT PK_ACHOrigNOCAction PRIMARY KEY (nocID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOrigFedAction
CREATE TABLE ${orig}.ACHOrigFedAction (
    fileID VARCHAR(30) NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key Constraint
    CONSTRAINT PK_ACHOrigFedAction PRIMARY KEY (fileID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba: ACHOrigReversalAction
CREATE TABLE ${orig}.ACHOrigReversalAction (
    fileID VARCHAR(30) NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    
    -- Composite Primary Key Constraint
    CONSTRAINT PK_ACHOrigReversalAction PRIMARY KEY (fileID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===== Part 2: additions =====

-- F13: Ba lists ACHOrigReturnAction's fields but gives no SQL; same shape as his other action tables, keyed by returnID
CREATE TABLE ${orig}.ACHOrigReturnAction (
    returnID BIGINT NOT NULL,
    processID VARCHAR(30) NOT NULL,
    actionID INT NOT NULL,
    actionTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHOrigReturnAction PRIMARY KEY (returnID, processID, actionID)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- F5 FKs
ALTER TABLE ${orig}.ACHOrigReturn
    ADD CONSTRAINT FK_ACHOrigReturn_ACHOrigTransaction FOREIGN KEY (tranID) REFERENCES ${orig}.ACHOrigTransaction(tranID),
    ADD CONSTRAINT FK_ACHOrigReturn_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);

-- S4: Nacha NOC deadlines (tell the originator within 2 banking days, apply within 6), when applied, refused-NOC code (C61-C69); F5 FKs
ALTER TABLE ${orig}.ACHOrigNOC
    ADD COLUMN notifyByDt DATE NULL,
    ADD COLUMN applyByDt DATE NULL,
    ADD COLUMN appliedTm DATETIME NULL,
    ADD COLUMN refusedCode CHAR(3) NULL,
    ADD CONSTRAINT FK_ACHOrigNOC_ACHOrigTransaction FOREIGN KEY (tranID) REFERENCES ${orig}.ACHOrigTransaction(tranID),
    ADD CONSTRAINT FK_ACHOrigNOC_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);

ALTER TABLE ${orig}.ACHOrigAcctSubs
    ADD CONSTRAINT FK_ACHOrigAcctSubs_ACHOrigNOC FOREIGN KEY (preNocID) REFERENCES ${orig}.ACHOrigNOC(nocID),
    ADD CONSTRAINT FK_ACHOrigAcctSubs_Source FOREIGN KEY (sourceID) REFERENCES ${orig}.Source(sourceID);
ALTER TABLE ${orig}.ACHOrigTransCodeSubs
    ADD CONSTRAINT FK_ACHOrigTransCodeSubs_ACHOrigNOC FOREIGN KEY (nocID) REFERENCES ${orig}.ACHOrigNOC(nocID);

ALTER TABLE ${orig}.ACHOrigResubAction
    ADD CONSTRAINT FK_ACHOrigResubAction_ACHOrigFile FOREIGN KEY (fileID) REFERENCES ${orig}.ACHOrigFile(fileID),
    ADD CONSTRAINT FK_ACHOrigResubAction_Action FOREIGN KEY (actionID) REFERENCES ${orig}.Action(actionID);
ALTER TABLE ${orig}.ACHOrigFedAction
    ADD CONSTRAINT FK_ACHOrigFedAction_ACHOrigFile FOREIGN KEY (fileID) REFERENCES ${orig}.ACHOrigFile(fileID),
    ADD CONSTRAINT FK_ACHOrigFedAction_Action FOREIGN KEY (actionID) REFERENCES ${orig}.Action(actionID);
ALTER TABLE ${orig}.ACHOrigReversalAction
    ADD CONSTRAINT FK_ACHOrigReversalAction_ACHOrigFile FOREIGN KEY (fileID) REFERENCES ${orig}.ACHOrigFile(fileID),
    ADD CONSTRAINT FK_ACHOrigReversalAction_Action FOREIGN KEY (actionID) REFERENCES ${orig}.Action(actionID);
ALTER TABLE ${orig}.ACHOrigNOCAction
    ADD CONSTRAINT FK_ACHOrigNOCAction_ACHOrigNOC FOREIGN KEY (nocID) REFERENCES ${orig}.ACHOrigNOC(nocID),
    ADD CONSTRAINT FK_ACHOrigNOCAction_Action FOREIGN KEY (actionID) REFERENCES ${orig}.Action(actionID);
ALTER TABLE ${orig}.ACHOrigReturnAction
    ADD CONSTRAINT FK_ACHOrigReturnAction_ACHOrigReturn FOREIGN KEY (returnID) REFERENCES ${orig}.ACHOrigReturn(returnID),
    ADD CONSTRAINT FK_ACHOrigReturnAction_Action FOREIGN KEY (actionID) REFERENCES ${orig}.Action(actionID);

-- F5: the receiving side's action log (M1) uses the same Action list (same MySQL server, so cross-database FKs work)
ALTER TABLE ${recv}.ACHRecvAction
    ADD CONSTRAINT FK_ACHRecvAction_Action FOREIGN KEY (actionID) REFERENCES ${orig}.Action(actionID);
