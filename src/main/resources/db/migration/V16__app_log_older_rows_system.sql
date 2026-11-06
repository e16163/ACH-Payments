-- UC-SYS-LOG-01: rows written before V15 have no systemID. These match the messages that existed then.
UPDATE AppLog SET systemID = CASE
    WHEN logMesg LIKE 'MFA%' OR logMesg LIKE 'EOD%' OR logMesg LIKE 'Fed send%' OR logMesg LIKE 'Compliance alert%'
        OR logMesg LIKE 'Operations alert: FILE\_%' THEN 'ACH_ORIG'
    WHEN logMesg LIKE 'ACHFILE-%' OR logMesg LIKE 'Operations alert: ACHFILE-%' OR logMesg LIKE 'Inbound%'
        OR logMesg LIKE 'ACH\_FILE\_INGESTED\_EVENT%' OR logMesg LIKE 'Failed to parse incoming ACH file%' THEN 'ACH_RCV'
    ELSE 'ACH_SYS'
END
WHERE systemID IS NULL;
