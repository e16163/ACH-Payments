-- NACHA: every entry in a file sits inside a batch. Enforcing that here also works around a MySQL 26.10 bug found
-- in D5: a foreign key on fileID isn't checked when batchID is NULL, because both columns lead the design's
-- IX_*Transaction_File_Batch (fileID, batchID) index. With batchID required whenever fileID is set, the fileID and
-- batchID foreign keys always run. Additions only; the design's columns, keys and indexes are unchanged.
ALTER TABLE ${recv}.ACHRecvTransaction
    ADD CONSTRAINT CK_ACHRecvTransaction_FileNeedsBatch CHECK (fileID IS NULL OR batchID IS NOT NULL);

ALTER TABLE ${orig}.ACHOrigTransaction
    ADD CONSTRAINT CK_ACHOrigTransaction_FileNeedsBatch CHECK (fileID IS NULL OR batchID IS NOT NULL);
