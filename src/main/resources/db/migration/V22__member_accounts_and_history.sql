-- Ba (2026-11-13): member banking. The account summary needs a nickname and a group beside the type, status, available
-- and posted balances, and each account lists its credits and debits. An internal transfer moves money between the
-- member's two accounts. Additions only.

ALTER TABLE SimCoreAccount
    ADD COLUMN acctNickname VARCHAR(50) NULL COMMENT 'the member''s own name for the account' AFTER acctName,
    ADD COLUMN acctGroup VARCHAR(15) NOT NULL DEFAULT 'DEPOSIT' COMMENT 'product group: DEPOSIT (checking, savings), LOAN later' AFTER acctType;

-- Every change to an account's balances. AVAILABLE: a hold or memo post (available only); POSTED: the posting system
-- (posted, and available when it wasn't memo posted first). The balances after it are copied from SimCoreAccount.
CREATE TABLE SimCoreAcctTran (
    acctTranID BIGINT AUTO_INCREMENT NOT NULL,
    acctNo VARCHAR(34) NOT NULL,
    tranTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tranDesc VARCHAR(100) NOT NULL,
    creditFlag TINYINT(1) NOT NULL COMMENT '1=Credit, 0=Debit',
    amount DECIMAL(13, 2) NOT NULL,
    balanceType VARCHAR(10) NOT NULL COMMENT 'AVAILABLE or POSTED',
    tranSource VARCHAR(10) NOT NULL COMMENT 'ACH_ORIG, ACH_RCV, INTERNAL or OPENING',
    tranRef VARCHAR(30) NOT NULL COMMENT 'transferSerNo, received tranID or internal transfer ID',
    availBalAfter DECIMAL(13, 2) NOT NULL,
    ledgerBalAfter DECIMAL(13, 2) NOT NULL,
    CONSTRAINT PK_SimCoreAcctTran PRIMARY KEY (acctTranID),
    CONSTRAINT FK_SimCoreAcctTran_Account FOREIGN KEY (acctNo) REFERENCES SimCoreAccount (acctNo) ON DELETE CASCADE,
    INDEX IX_SimCoreAcctTran_Acct (acctNo, tranTm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- The demo member's accounts get nicknames.
UPDATE SimCoreAccount SET acctNickname = 'Everyday Checking' WHERE acctNo = '100049281920';
UPDATE SimCoreAccount SET acctNickname = 'Rainy Day Fund' WHERE acctNo = '100049281946';

-- An opening row per existing account, so each list starts from the balance it has today.
INSERT INTO SimCoreAcctTran (acctNo, tranDesc, creditFlag, amount, balanceType, tranSource, tranRef, availBalAfter, ledgerBalAfter)
SELECT acctNo, 'Opening balance', 1, ledgerBal, 'POSTED', 'OPENING', 'OPENING', availBal, ledgerBal FROM SimCoreAccount;
