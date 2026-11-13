-- Ba (2026-11-12): the bank is now "Demo Community Bank". The NACHA fields are fixed width: the File Header's origin name
-- holds 23 characters and the batch header's company name 16, so the company name is shortened. A value an admin has
-- already changed is left alone.
UPDATE ${orig}.ACHOriginator SET name = 'DEMO COMMUNITY BANK', lastModBy = 'SYS_ADMIN'
    WHERE originatorID = '9000000001' AND name = 'DEMO CREDIT UNION';
UPDATE ABADirectory SET bankName = 'Demo Community Bank' WHERE routingNo = '123456780' AND bankName = 'Demo Credit Union';
UPDATE AppParam SET paramRawValue = 'DEMO COMM BANK', lastModBy = 'SYS_ADMIN'
    WHERE systemName = 'ACH_ORIG' AND paramName = 'ORIG_COMPANY_NAME' AND paramRawValue = 'DEMO CU';
