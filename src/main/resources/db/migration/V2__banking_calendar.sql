-- BankingCalendar (docs/DB-MODEL.md M6): Federal Reserve holidays. Weekends are computed, not stored.
-- Fed rules: a Sunday holiday is observed Monday; for a Saturday holiday the Fed is open the Friday before,
-- so 2026-07-04, 2027-06-19 and 2027-12-25 are not listed.

CREATE TABLE BankingCalendar (
    holidayDt DATE NOT NULL,
    holidayName VARCHAR(50) NOT NULL,
    CONSTRAINT PK_BankingCalendar PRIMARY KEY (holidayDt)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO BankingCalendar (holidayDt, holidayName) VALUES
('2026-01-01', 'New Year''s Day'),
('2026-01-19', 'Martin Luther King Jr. Day'),
('2026-02-16', 'Presidents Day'),
('2026-05-25', 'Memorial Day'),
('2026-06-19', 'Juneteenth'),
('2026-09-07', 'Labor Day'),
('2026-10-12', 'Columbus Day'),
('2026-11-11', 'Veterans Day'),
('2026-11-26', 'Thanksgiving Day'),
('2026-12-25', 'Christmas Day'),
('2027-01-01', 'New Year''s Day'),
('2027-01-18', 'Martin Luther King Jr. Day'),
('2027-02-15', 'Presidents Day'),
('2027-05-31', 'Memorial Day'),
('2027-07-05', 'Independence Day (observed)'),
('2027-09-06', 'Labor Day'),
('2027-10-11', 'Columbus Day'),
('2027-11-11', 'Veterans Day'),
('2027-11-25', 'Thanksgiving Day');
