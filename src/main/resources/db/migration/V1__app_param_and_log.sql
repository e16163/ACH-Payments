-- AppParam and AppLog, as defined in Ba's ACH Payments System Design ("Database Model for Application Configuration and Logging").

CREATE TABLE AppParam (
    systemName VARCHAR(30) NOT NULL,
    paramName VARCHAR(50) NOT NULL,
    paramDesc VARCHAR(255) NULL,
    paramRawType VARCHAR(20) NOT NULL DEFAULT 'STRING',
    paramRawValue VARCHAR(500) NOT NULL,
    lastModTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    lastModBy VARCHAR(50) NOT NULL,
    CONSTRAINT PK_AppParam PRIMARY KEY (systemName, paramName)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE AppLog (
    logAppId BIGINT AUTO_INCREMENT NOT NULL,
    logLevel VARCHAR(10) NOT NULL,
    logMesg VARCHAR(1000) NOT NULL,
    logExceptionData TEXT NULL,
    logTm DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT PK_AppLog PRIMARY KEY (logAppId),
    INDEX IX_AppLog_LogLevel (logLevel),
    INDEX IX_AppLog_LogTm (logTm)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Ba's sample parameters
INSERT INTO AppParam (systemName, paramName, paramDesc, paramRawType, paramRawValue, lastModBy) VALUES
('ACH_RCV', 'MEMO_POST_IMMEDIATE', 'Enable immediate memo posting upon transaction ingestion', 'BOOLEAN', 'TRUE', 'SYS_ADMIN'),
('ACH_ORIG', 'FED_FILE_CUTOFF_TIME', 'Cutoff time for building daily Fed file (EST)', 'STRING', '17:00:00', 'SYS_ADMIN');
