-- Ba's critique (2026-11-12): the ACH Origination File and ACH Receiving File screens become ACH Origination Processing
-- and ACH Receiving Processing, built on the two blueprint pipelines, with every screen value read from the database:
-- the processing schedule, each window run, and (already in the design) the files and batches sent to the Fed.
-- Additions only; the design's tables are unchanged.

-- ACH Origination Processing: the intraday windows (Ba: 6 AM, 9 AM, 12 PM, 3 PM Eastern). Replaces ORIG_WINDOW_TIMES.
CREATE TABLE ${orig}.ACHOrigSchedule (
    scheduleID INT AUTO_INCREMENT NOT NULL,
    windowTime CHAR(5) NOT NULL COMMENT 'HH:mm, Eastern',
    windowName VARCHAR(50) NOT NULL,
    status VARCHAR(10) DEFAULT 'ACTIVE' NOT NULL COMMENT 'ACTIVE or INACTIVE',
    lastModBy VARCHAR(50) NOT NULL,
    lastModTm DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_ACHOrigSchedule PRIMARY KEY (scheduleID),
    CONSTRAINT UQ_ACHOrigSchedule_Time UNIQUE (windowTime)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ${orig}.ACHOrigSchedule (windowTime, windowName, lastModBy) VALUES
('06:00', '6:00 AM window', 'SYS_ADMIN'),
('09:00', '9:00 AM window', 'SYS_ADMIN'),
('12:00', '12:00 PM window', 'SYS_ADMIN'),
('15:00', '3:00 PM window', 'SYS_ADMIN');

-- Ba (2026-11-12): each ACH origination file records the schedule it was sent from (NULL: the 5 PM file).
ALTER TABLE ${orig}.ACHOrigFile
    ADD COLUMN scheduleID INT NULL COMMENT 'the window it was sent from; NULL for the 5 PM file',
    ADD CONSTRAINT FK_ACHOrigFile_Schedule FOREIGN KEY (scheduleID) REFERENCES ${orig}.ACHOrigSchedule (scheduleID);

-- The window that made a transfer READY_TO_POST, so the screen lists and sends a schedule's transactions only.
ALTER TABLE ${orig}.ACHOriginatorTransfer
    ADD COLUMN scheduleID INT NULL COMMENT 'the window that made it READY_TO_POST; NULL for the 5 PM sweep',
    ADD CONSTRAINT FK_ACHOriginatorTransfer_Schedule FOREIGN KEY (scheduleID) REFERENCES ${orig}.ACHOrigSchedule (scheduleID);

-- Steps 1-7, one row per window run: a scheduled or operator-processed window, or the 5 PM sweep (no schedule row).
CREATE TABLE ${orig}.ACHOrigWindowRun (
    runID BIGINT AUTO_INCREMENT NOT NULL,
    windowID VARCHAR(20) NOT NULL COMMENT 'WIN-yyyyMMdd-HHmm, as the trigger-window response',
    scheduleID INT NULL COMMENT 'NULL for the 5 PM sweep',
    businessDt DATE NOT NULL,
    windowTime VARCHAR(5) NOT NULL COMMENT 'HH:mm, or EOD for the 5 PM sweep',
    extractedCnt INT NOT NULL COMMENT 'step 1',
    acctRejectCnt INT NOT NULL COMMENT 'step 2: account not active, or not enough funds',
    limitRejectCnt INT NOT NULL COMMENT 'step 3',
    flaggedCnt INT NOT NULL COMMENT 'step 4: OFAC hits sent to compliance (step 5)',
    holdRejectCnt INT NOT NULL COMMENT 'step 6: the memo hold could not be placed',
    readyCnt INT NOT NULL COMMENT 'step 7: READY_TO_POST',
    runBy VARCHAR(50) NOT NULL,
    startTm DATETIME NOT NULL,
    endTm DATETIME NOT NULL,
    CONSTRAINT PK_ACHOrigWindowRun PRIMARY KEY (runID),
    CONSTRAINT FK_ACHOrigWindowRun_Schedule FOREIGN KEY (scheduleID) REFERENCES ${orig}.ACHOrigSchedule (scheduleID),
    INDEX IX_ACHOrigWindowRun_Date (businessDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ACH Receiving Processing: the FedACH distribution windows (Ba: 6 AM, 9 AM, 12 PM, 3 PM, 6 PM). Replaces RECV_WINDOW_TIMES.
CREATE TABLE ${recv}.ACHRecvSchedule (
    scheduleID INT AUTO_INCREMENT NOT NULL,
    windowTime CHAR(5) NOT NULL COMMENT 'HH:mm, Eastern',
    windowName VARCHAR(50) NOT NULL,
    status VARCHAR(10) DEFAULT 'ACTIVE' NOT NULL COMMENT 'ACTIVE or INACTIVE',
    lastModBy VARCHAR(50) NOT NULL,
    lastModTm DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_ACHRecvSchedule PRIMARY KEY (scheduleID),
    CONSTRAINT UQ_ACHRecvSchedule_Time UNIQUE (windowTime)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ${recv}.ACHRecvSchedule (windowTime, windowName, lastModBy) VALUES
('06:00', '6:00 AM window', 'SYS_ADMIN'),
('09:00', '9:00 AM window', 'SYS_ADMIN'),
('12:00', '12:00 PM window', 'SYS_ADMIN'),
('15:00', '3:00 PM window', 'SYS_ADMIN'),
('18:00', '6:00 PM window', 'SYS_ADMIN');

-- Steps 2-6, one row per window run.
CREATE TABLE ${recv}.ACHRecvWindowRun (
    runID BIGINT AUTO_INCREMENT NOT NULL,
    executionID VARCHAR(30) NOT NULL COMMENT 'EXEC-REC-yyyyMMdd-HHmm, as the trigger response',
    scheduleID INT NULL,
    businessDt DATE NOT NULL,
    windowTime VARCHAR(5) NOT NULL,
    fileCnt INT NOT NULL COMMENT 'files picked up from fed/inbound',
    validatedCnt INT NOT NULL COMMENT 'steps 2-3: validated and extracted; the rest were quarantined',
    entryCnt INT NOT NULL COMMENT 'step 3: entries extracted',
    matchedCnt INT NOT NULL COMMENT 'step 4: account found as sent',
    correctedCnt INT NOT NULL COMMENT 'step 4: alias or zero padding',
    reviewCnt INT NOT NULL COMMENT 'step 4: manual review',
    rejectedCnt INT NOT NULL COMMENT 'step 5: returned with an R code',
    memoPostedCnt INT NOT NULL COMMENT 'step 6',
    runBy VARCHAR(50) NOT NULL,
    startTm DATETIME NOT NULL,
    endTm DATETIME NOT NULL,
    CONSTRAINT PK_ACHRecvWindowRun PRIMARY KEY (runID),
    CONSTRAINT FK_ACHRecvWindowRun_Schedule FOREIGN KEY (scheduleID) REFERENCES ${recv}.ACHRecvSchedule (scheduleID),
    INDEX IX_ACHRecvWindowRun_Date (businessDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The schedule tables replace the two parameters.
DELETE FROM AppParam WHERE (systemName = 'ACH_ORIG' AND paramName = 'ORIG_WINDOW_TIMES')
    OR (systemName = 'ACH_RCV' AND paramName = 'RECV_WINDOW_TIMES');
