# How ACH Works: Notes from Building This System

Personal study notes, kept up to date as the demo is built. The aim is to be able to explain ACH Origination and ACH Receiving end to end, and point at the code that does each step, in a payments software engineer interview.

---

## 1. The players

| Who | Role |
| --- | --- |
| **Originator** | Starts the payment: a company paying payroll, or a member sending money out. |
| **ODFI** (Originating Depository Financial Institution) | The originator's bank. It builds the NACHA file and sends it to the operator. **In our Origination side, we are the ODFI.** |
| **ACH Operator** | The clearing house: **FedACH** (Federal Reserve) or **EPN** (The Clearing House). It sorts entries and sends each RDFI its file. |
| **RDFI** (Receiving DFI) | The receiver's bank. It posts the entries to its customers' accounts. **In our Receiving side, we are the RDFI.** |
| **Receiver** | The person or company whose account is credited or debited. |

- **Nacha** writes the operating rules. Federal government ACH payments are governed by 31 CFR Part 210, and consumer protections come from Regulation E.
- **Credit vs. debit:** a *credit* pushes money to the receiver (payroll, our member's outgoing transfer). A *debit* pulls money from the receiver (bill pay, loan payment). The direction is set by the **transaction code**, not by who sends the file.

## 2. The NACHA file (what actually moves)

A fixed-width text file: **94 characters per line**, in blocks of 10 lines, padded with lines of all `9`s.

| Record | What it holds |
| --- | --- |
| `1` File Header | Immediate destination and origin (routing numbers), creation date and time, File ID Modifier (A, B, C…) |
| `5` Batch Header | Service class (200 mixed, 220 credits, 225 debits), company name and ID, **SEC code**, entry description, effective date |
| `6` Entry Detail | **Transaction code**, receiving routing number + check digit, account number, amount (cents), name, **trace number** |
| `7` Addenda | `05` payment info, `98` NOC, `99` return |
| `8` Batch Control | Entry count, **entry hash**, total debits, total credits |
| `9` File Control | Batch count, block count, entry count, entry hash, totals |

Things to be able to explain:
- **Routing check digit:** weights 3-7-1 repeated over the 9 digits; the sum must be a multiple of 10.
- **Entry hash:** sum of the first 8 digits of every receiving routing number, keep the last 10 digits. It's a cheap tamper and corruption check.
- **Transaction codes:** `22`/`27` checking credit/debit, `32`/`37` savings credit/debit, `23`/`28`/`33`/`38` prenotes (zero-dollar test entries).
- **SEC codes** say how the payment was authorized: `PPD` (consumer, signed), `CCD` (corporate), `WEB` (internet), `TEL` (phone), `COR` (NOC), `IAT` (international; never Same Day).
- **Trace number:** the ODFI's 8-digit routing prefix + a 7-digit sequence. It's how every later return or NOC finds the original entry.

Code: `nacha/AchFileParser`, `AchFileWriter`, `AchFileValidator`. A file must parse and write back byte-for-byte identical (round-trip test).

## 3. The business day (timeline)

- **Same Day ACH:** FedACH submission deadlines 10:30 AM, 2:45 PM and 4:45 PM ET. Limit $1M per payment. IAT can't go Same Day.
- **Next-day ACH:** files sent after the last Same Day window settle on the next banking day.
- **Banking days** skip weekends and Federal Reserve holidays, so every date calculation goes through a calendar (`common/BankingCalendar`).
- In this demo: **Receiving** picks up the Fed's inbound file at 06:00 ET; **Origination** builds one EOD file after the `FED_FILE_CUTOFF_TIME` parameter (5 PM). Same Day windows aren't built yet.

## 4. ACH Origination, as built (we are the ODFI)

1. **Register the payee.** The member adds an external bank account (`ACHOrigRecvAccount`, status `PENDING_MICRO_DEP`). Real banks verify it with micro-deposits; Nacha requires account validation before first use for WEB debits.
2. **Request the transfer** (`TransferService.preview`, then `submit`). Checks: the member owns the source account, available balance, daily and monthly limits (`ACHOrigLimitRule`), **OFAC** name screening, account verification (simulated Plaid), and a banking-day effective date.
3. **Memo hold.** In the same DB transaction as the insert, the amount + fee is held against available balance (`ACHMemoPost`, `memoStatus = 'POSTED'`). That stops the member spending the money twice before the file goes out.
4. **Wait for cutoff.** The transfer sits as `processStatus = 'PENDING_BATCH'`, `ofacStatus = 'OFAC_PASSED'`.
5. **EOD file** (`outbound/EodFileJob`): picks up eligible transfers, re-screens OFAC, assigns trace numbers, groups them into batches (SEC code, company, effective date, entry description), writes the file, then **re-parses and validates its own output**. A bad file never leaves the bank.
6. **Persist, then send.** One DB transaction writes `ACHOrigFile` (`QUEUED`), batches and entries; the Fed simulator then takes the file (`SENT`). 3 attempts with backoff; all failing gives `ERROR`.
7. **Audit.** Every state change adds an `ACHOrigFedAction` row; DB triggers refuse UPDATE and DELETE on it.

Not built yet: returns coming back to us, NOCs from RDFIs, reversals, Same Day windows, final posting to the core.

## 5. ACH Receiving, as built (we are the RDFI)

1. **Ingest** (`inbound/ReceivingScheduler`, `ReceivingEngine`): the Fed's file arrives in `fed/inbound`; `ACHRecvFile` starts as `INGESTING`.
2. **Trust it first:** check the signature (simulated HMAC), then the File Header (is it for our routing number? valid date, time, modifier?).
3. **Duplicate check:** same date, time and File ID Modifier as a file already seen gives `DUPLICATE`.
4. **Load** one batch at a time (streaming, so a big file doesn't fill memory): entries `SETTLED` / `UNPROCESSED`, addenda to `ACHRecvAddenda`.
5. **Balance check:** batch and file control totals must match the entries; otherwise `OUT_OF_BALANCE`, roll everything back and write a rejection notice.
6. **Hand off:** `VALIDATED`, archive the raw file (kept for audit), publish `ACH_FILE_INGESTED_EVENT`.
7. **No OFAC on received entries:** Ba's design screens once, at origination. (A real RDFI still answers for its own members, especially on IAT.)

Not built yet: matching entries to member accounts, memo posting received entries, returns and NOCs we send back, final posting.

## 6. Returns and NOCs (to learn before building)

- **Return:** the RDFI sends an entry back with an `R` code in a `99` addenda. Common ones: `R01` insufficient funds, `R02` account closed, `R03` no account, `R04` invalid account number, `R10` not authorized (consumer). Most must be sent within **2 banking days**; consumer unauthorized returns (`R05`, `R07`, `R10`, `R11`) get **60 calendar days**.
- **NOC (Notification of Change):** the RDFI posts the entry but tells the originator to fix the details next time (`C01` wrong account number, `C02` wrong routing number…), in a `COR` entry with a `98` addenda. The originator must make the change within 6 banking days or before the next entry, whichever is later.
- **Request for Return ("recall"):** the ODFI asks the RDFI to send an entry back. The RDFI may refuse, but must answer within 10 banking days; agreeing uses `R06`.

## 7. Design points worth talking about

- **Idempotency.** Rerunning the EOD job resends a `QUEUED` file rebuilt from saved rows (same bytes) and never re-includes processed transfers. Receiving rejects duplicate files.
- **Validate your own output.** The file builder re-reads its file with the same validator the receiving side uses.
- **Money in cents** (whole numbers in the file), `BigDecimal` in Java, never floating point.
- **One DB transaction per state change** (insert + memo hold; file + batches + entries), so a crash can't leave half a payment.
- **Immutable audit trail** enforced by the database, not just the code.
- **Parameters, not hardcoded values,** for cutoffs, fees and limits (`AppParam`), since Nacha changes limits on known dates.
- **Simulated outside systems** (Fed, core banking, Plaid, OFAC list) behind small interfaces, so real ones can be swapped in.

## 8. Decisions log

- **2026-11-07: Routing number box.** Ba: only error when the routing number isn't 9 digits; any 9 digits pass until real routing validation is written. (The transfer step still requires the 3-7-1 check digit, because the EOD file validator rejects a file containing a bad one.)
- **Coming:** Ba's blueprint of the business-day life cycle for Origination and Receiving processing and posting. It will drive the redesign of both file processing and posting flows; this file gets updated from it.
