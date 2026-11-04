-- X1 (USE-CASES UC-ACH-ORG-01 "Tables it needs"), in ach_payments (shared reference data).

-- Routing number -> bank name, for the Transfer UI lookup (step 3). A small stand-in for the Fed's
-- E-Payments Routing Directory. Every number passes the ABA 3-7-1 check digit (tested).
CREATE TABLE ABADirectory (
    routingNo CHAR(9) NOT NULL,
    bankName VARCHAR(100) NOT NULL,
    CONSTRAINT PK_ABADirectory PRIMARY KEY (routingNo)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO ABADirectory (routingNo, bankName) VALUES
('123456780', 'Demo Credit Union'),
('051000033', 'Sample Bank (from Ba''s design samples)'),
('021000021', 'JPMorgan Chase Bank, N.A.'),
('026009593', 'Bank of America, N.A.'),
('121000248', 'Wells Fargo Bank, N.A.'),
('011000015', 'Federal Reserve Bank of Boston');

-- A simulated OFAC SDN list for the screening check (step 5, T6). Names are fictional test values.
CREATE TABLE OfacSdnName (
    sdnID INT AUTO_INCREMENT NOT NULL,
    sdnName VARCHAR(200) NOT NULL,
    program VARCHAR(50) NOT NULL,
    CONSTRAINT PK_OfacSdnName PRIMARY KEY (sdnID),
    INDEX IX_OfacSdnName_Name (sdnName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO OfacSdnName (sdnName, program) VALUES
('OFAC TEST BLOCKED PERSON', 'DEMO'),
('SANCTIONED TEST TRADING LLC', 'DEMO'),
('BLOCKED DEMO ENTITY', 'DEMO');
