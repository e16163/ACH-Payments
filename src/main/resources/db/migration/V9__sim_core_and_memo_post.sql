-- R6 (PLAN 5.4, DB-MODEL M4), in ach_payments.

-- The simulated core banking system's member accounts. Balances live in the core, not in the design's ACH tables.
CREATE TABLE SimCoreAccount (
    acctNo VARCHAR(34) NOT NULL,
    memberNo VARCHAR(20) NOT NULL,
    acctName VARCHAR(50) NOT NULL,
    acctType VARCHAR(15) NOT NULL COMMENT 'CHECKING or SAVINGS',
    acctOwner VARCHAR(100) NOT NULL,
    ledgerBal DECIMAL(13, 2) NOT NULL DEFAULT 0.00,
    availBal DECIMAL(13, 2) NOT NULL DEFAULT 0.00,
    acctStatus VARCHAR(20) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE, FROZEN, CLOSED or DECEASED',
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT PK_SimCoreAccount PRIMARY KEY (acctNo),
    INDEX IX_SimCoreAccount_MemberNo (memberNo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Memo Posting Eligibility Engine results: every attempt, whether it posted or not.
CREATE TABLE ACHMemoPost (
    memoPostID BIGINT AUTO_INCREMENT NOT NULL,
    tranSource VARCHAR(10) NOT NULL COMMENT 'ORIG (member transfer) or RECV (received entry)',
    tranRef VARCHAR(30) NOT NULL COMMENT 'transferSerNo or received tranID',
    acctNo VARCHAR(34) NOT NULL,
    creditFlag TINYINT(1) NOT NULL COMMENT '1=Credit, 0=Debit',
    amount DECIMAL(13, 2) NOT NULL,
    postType VARCHAR(10) NOT NULL COMMENT 'HOLD (funds held) or POST (available balance changed)',
    memoStatus VARCHAR(15) NOT NULL COMMENT 'POSTED, NSF, REVIEW, DEFERRED or INELIGIBLE',
    reason VARCHAR(255) NULL,
    createTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_ACHMemoPost PRIMARY KEY (memoPostID),
    CONSTRAINT FK_ACHMemoPost_SimCoreAccount FOREIGN KEY (acctNo) REFERENCES SimCoreAccount(acctNo),
    INDEX IX_ACHMemoPost_Tran (tranSource, tranRef)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Demo member: the sample member M10982347 (JOHN DOE). 100049281920 is the funding account in the sample transfer.
INSERT INTO SimCoreAccount (acctNo, memberNo, acctName, acctType, acctOwner, ledgerBal, availBal) VALUES
('100049281920', 'M10982347', 'Primary Checking', 'CHECKING', 'JOHN DOE', 8500.00, 8500.00),
('100049281946', 'M10982347', 'Savings', 'SAVINGS', 'JOHN DOE', 15000.00, 15000.00);
