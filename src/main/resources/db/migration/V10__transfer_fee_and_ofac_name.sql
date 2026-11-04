-- USE-CASES T5: the external transfer fee is a parameter (free for now), editable in the Parameter UI.
INSERT INTO AppParam (systemName, paramName, paramDesc, paramRawType, paramRawValue, lastModBy) VALUES
('ACH_ORIG', 'EXTERNAL_XFER_FEE', 'Fee for a member external ACH transfer (UC-ACH-ORG-01)', 'DECIMAL', '0.00', 'SYS_ADMIN');

-- A simulated OFAC name that fits a NACHA recipient name (22 characters), so the screening hit (T6) can be demoed.
INSERT INTO OfacSdnName (sdnName, program) VALUES ('BLOCKED TEST PERSON', 'DEMO');
