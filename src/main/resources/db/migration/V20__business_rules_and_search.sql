-- Ba (2026-11-12): ACH Business Rules Management. One row per rule, per app (systemName, as in AppParam).
CREATE TABLE ACHBusinessRule (
    ruleID BIGINT AUTO_INCREMENT NOT NULL,
    systemName VARCHAR(30) NOT NULL COMMENT 'the app, as in AppParam',
    ruleName VARCHAR(100) NOT NULL COMMENT 'ACH Business Rule Name',
    ruleDesc VARCHAR(500) NULL COMMENT 'Business Description',
    pseudoCode VARCHAR(2000) NOT NULL COMMENT 'Pseudo Code',
    ruleJson JSON NOT NULL COMMENT 'JSON Expression Rule: {"when":{"all"|"any":[{field,op,value}]},"then":[{set,value}]}',
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_ACHBusinessRule PRIMARY KEY (ruleID),
    CONSTRAINT UQ_ACHBusinessRule_Name UNIQUE (systemName, ruleName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Receiving step 6: a credit a rule marks earlyPosting is memo posted at once. Ba's sample, and the Company ID the
-- TRUSTED_COMPANY_IDS parameter held, which these rules replace.
INSERT INTO ACHBusinessRule (systemName, ruleName, ruleDesc, pseudoCode, ruleJson, lastModBy) VALUES
('ACH_RCV', 'Early posting: Company 23416789 credits', 'Credits from Company ID 23416789 are made available at once, before settlement.',
 'If companyID = "23416789" && creditIndicator = true\nset earlyPosting = true',
 '{"when": {"all": [{"field": "companyID", "op": "eq", "value": "23416789"}, {"field": "creditIndicator", "op": "eq", "value": true}]}, "then": [{"set": "earlyPosting", "value": true}]}',
 'SYS_ADMIN'),
('ACH_RCV', 'Early posting: payroll from Company 1900483920', 'Payroll credits from Company ID 1900483920 are made available at once, before settlement.',
 'If companyID = "1900483920" && creditIndicator = true\nset earlyPosting = true',
 '{"when": {"all": [{"field": "companyID", "op": "eq", "value": "1900483920"}, {"field": "creditIndicator", "op": "eq", "value": true}]}, "then": [{"set": "earlyPosting", "value": true}]}',
 'SYS_ADMIN');

DELETE FROM AppParam WHERE systemName = 'ACH_RCV' AND paramName = 'TRUSTED_COMPANY_IDS';

-- ACH Origination Transactions searches by effective date (ACHRecvTransaction already has this index).
CREATE INDEX IX_ACHOriginatorTransfer_EffectiveDt ON ${orig}.ACHOriginatorTransfer (effectiveDt);
