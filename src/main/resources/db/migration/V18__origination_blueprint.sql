-- Ba's blueprint, ACH Origination Processing (docs/BLUEPRINT-PLAN.md section 4). Additions only; the design's columns are unchanged.
-- ACHOriginatorTransfer.processStatus: PENDING_BATCH (submitted, waiting for a window) -> READY_TO_POST (window steps 1-7 passed)
-- -> PROCESSED (in the EOD file), or PENDING (OFAC FLAGGED, waiting for compliance) or REJECTED.
-- ofacStatus adds CLEARED (a compliance officer cleared a false positive) and BLOCKED. memoStatus adds RELEASED (hold given back).

-- Step 5: every compliance decision on a flagged transfer, kept for audit.
CREATE TABLE ${orig}.ACHOrigOfacReview (
    reviewID BIGINT AUTO_INCREMENT NOT NULL,
    transferSerNo VARCHAR(30) NOT NULL,
    previousStatus VARCHAR(20) NOT NULL,
    resolution VARCHAR(30) NOT NULL COMMENT 'CLEARED_FALSE_POSITIVE or BLOCKED_CONFIRMED_MATCH',
    currentStatus VARCHAR(20) NOT NULL COMMENT 'CLEARED or BLOCKED',
    complianceOfficerID VARCHAR(50) NOT NULL,
    notes VARCHAR(500) NULL,
    createTm DATETIME DEFAULT CURRENT_TIMESTAMP NOT NULL,
    CONSTRAINT PK_ACHOrigOfacReview PRIMARY KEY (reviewID),
    INDEX IX_ACHOrigOfacReview_Transfer (transferSerNo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Step 1: the intraday windows. The file still goes at FED_FILE_CUTOFF_TIME (step 8).
INSERT INTO AppParam (systemName, paramName, paramDesc, paramRawType, paramRawValue, lastModBy) VALUES
('ACH_ORIG', 'ORIG_WINDOW_TIMES', 'Intraday windows when scheduled transfers are checked and made ready to post (Eastern, comma-separated)',
    'STRING', '06:00,09:00,12:00,15:00', 'SYS_ADMIN');
