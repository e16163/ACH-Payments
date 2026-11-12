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
- In this system (Ba's blueprint), times Eastern:

| Time | Receiving (RDFI) | Origination (ODFI) |
|---|---|---|
| 06:00 | window: validate, extract, match accounts, returns, memo post | window: checks on transfers due today, `READY_TO_POST` |
| 09:00, 12:00, 15:00 | same | same; ops can **Send to the Fed** a schedule's ready transfers in its own file |
| next banking day 06:00 | | anything ready but not sent moves to the first window and goes in its file (no 5 PM file since 2026-11-12) |
| 18:00 | last window | |
| 19:00 | core posting file: final posting of everything memo posted or waiting | |

- The windows mirror the Fed's distribution times: a bank processes what arrived since the last window instead of once a day.
- The schedules are tables (`ACHOrigSchedule`, `ACHRecvSchedule`), and each window run is a row (`ACHOrigWindowRun`, `ACHRecvWindowRun`) with the count for each step. Ops screens read those rows, so "what did the 9 AM window do?" has an answer after the fact (an audit trail, not just a log line).
- Sending a file per schedule is how a bank meets the Same Day deadlines; the 9:00 AM file here would make the 10:30 AM FedACH deadline. Same Day eligibility rules ($1M, no IAT, the Same Day fee) are still not built.

## 4. ACH Origination, as built (we are the ODFI)

1. **Register the payee.** The member adds an external bank account (`ACHOrigRecvAccount`, status `PENDING_MICRO_DEP`). Real banks verify it with micro-deposits; Nacha requires account validation before first use for WEB debits.
2. **Request the transfer** (`TransferService.preview`, then `submit`). Checks: the member owns the source account, available balance, daily and monthly limits (`ACHOrigLimitRule`), **OFAC** name screening, account verification (simulated Plaid), and a banking-day effective date.
3. **Memo hold.** In the same DB transaction as the insert, the amount + fee is held against available balance (`ACHMemoPost`, `memoStatus = 'POSTED'`). That stops the member spending the money twice before the file goes out. The transfer waits as `PENDING_BATCH`.
4. **Processing windows** (`origination/OriginationWindow`, Ba's steps 1–7). On the transfer's date, at 6, 9, 12 and 3, check it **again**, because things change between Confirm and the date: is the account still active, do limits still allow it, is the name on the OFAC list *now*? Pass → `READY_TO_POST`. Fail → `REJECTED`, and the hold is **released** (a `RELEASE` memo row), so the member gets the money back at once.
5. **OFAC hit → compliance.** The transfer is `FLAGGED` and keeps its hold. A compliance officer (separate role, `achcomp`) clears a false positive (`CLEARED`, goes on at the next window) or blocks a true match (`BLOCKED`, rejected). Each decision is logged in `ACHOrigOfacReview`. That's *separation of duties*: the ops user who runs files can't approve sanctions hits.
6. **EOD file** (`outbound/EodFileJob`): one last window, then picks up `READY_TO_POST` transfers, re-screens OFAC, assigns trace numbers, groups them into batches (SEC code, company, effective date, entry description), writes the file, then **re-parses and validates its own output**. A bad file never leaves the bank.
7. **Persist, then send.** One DB transaction writes `ACHOrigFile` (`QUEUED`), batches and entries; the Fed simulator then takes the file (`SENT`). 3 attempts with backoff; all failing gives `ERROR`.
8. **Audit.** Every state change adds an `ACHOrigFedAction` row; DB triggers refuse UPDATE and DELETE on it.

Not built yet: returns coming back to us, NOCs from RDFIs, reversals, Same Day windows, final posting to the core.

## 5. ACH Receiving, as built (we are the RDFI)

1. **Ingest** (`inbound/ReceivingScheduler`, `ReceivingEngine`): the Fed's file arrives in `fed/inbound`; `ACHRecvFile` starts as `INGESTING`.
2. **Trust it first:** check the signature (simulated HMAC), then the File Header (is it for our routing number? valid date, time, modifier?).
3. **Duplicate check:** same date, time and File ID Modifier as a file already seen gives `DUPLICATE`.
4. **Load** one batch at a time (streaming, so a big file doesn't fill memory): entries `SETTLED` / `RECEIVED`, addenda to `ACHRecvAddenda`.
5. **Balance check:** batch and file control totals must match the entries; otherwise `OUT_OF_BALANCE`, roll everything back and write a rejection notice.
6. **Hand off:** `VALIDATED`, archive the raw file (kept for audit), publish `ACH_FILE_INGESTED_EVENT`.
7. **No OFAC on received entries:** Ba's design screens once, at origination. (A real RDFI still answers for its own members, especially on IAT.)
8. **Transcode and match** (`ReceivedEntryProcessor`, Ba's step 4): NACHA tran code → core code (`22`→`DDA_CR`, `27`→`DDA_DR`, `32`/`37` savings, prenotes). Find the account: exact; a **known alias** (`ACHRecvAcctSubs`, e.g. after a NOC or a merger); or **zero padding** (`982341` = `000982341`, only if exactly one account matches). Not found → `MANUAL_REVIEW`.
9. **Returns** (step 5): not found **R03**, closed **R02**, frozen **R16**, deceased **R15**. Debits also: stop payment **R08**, blocked originator **R10** (consumer) or **R29** (corporate, CCD/CTX), no funds **R01**. A return is recorded in `ACHRecvReturn` as `PENDING`. Over `MAX_ENTRY_AMOUNT` isn't returned; it waits for a person.
10. **Memo post** (step 6): **debits are held at once** (so the money can't be spent twice); **credits are available at once only when an ACH Business Rule sets `earlyPosting`** (e.g. a known payroll's Company ID), because a credit can still be returned or reversed. Other credits wait. This is "early pay": the member sees a payroll before its settlement date, and the bank takes the risk that it's returned.
11. **Final posting** (step 7, `CorePostingFileJob`): at 19:00 a pipe-delimited file (`H` header, `D` details, `T` totals) for the core; ledger balances change only here. Idempotent per date.

Not built yet: sending returns to the Fed, NOCs we send, a manual-review screen.

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
- **Memo posting vs. final posting.** *Available* balance changes intraday (holds, early-posted credits); *ledger* balance changes once, at end of day, from one file the core ingests. Two numbers, two moments.
- **Re-check at the last responsible moment.** Origination checks at Confirm *and* in the window on the date: accounts get frozen, limits change, OFAC lists update daily.
- **Separation of duties.** OFAC decisions need a compliance role the operations user doesn't have; every decision is stored with who and why.
- **Simulated outside systems** (Fed, core banking, Plaid, OFAC list) behind small interfaces, so real ones can be swapped in.

## 8. Decisions log

- **2026-11-07: Routing number box.** Ba: only error when the routing number isn't 9 digits; any 9 digits pass until real routing validation is written. (The transfer step still requires the 3-7-1 check digit, because the EOD file validator rejects a file containing a bad one.)
- **2026-11-08: Ba's blueprint built** (plan and Q1–Q10 in `docs/BLUEPRINT-PLAN.md`). Highlights: the hold stays at Confirm and the window re-checks (Q3); unknown accounts get NACHA's R03 after manual review; trusted Company IDs are a parameter; a new compliance login resolves OFAC hits.
- **2026-11-12: Ba's critique: processing screens.** ACH Origination File → **ACH Origination Processing**: today's date, pick a schedule, see the transactions that window made ready, **Send to the Fed** creates the file for them. ACH Receiving File → **ACH Receiving Processing**: receiving only delivers to the inbound folder; a window processes it; step 7's posting file is on the same screen. Ba: "you must pull data from the database", so the schedules and window runs became tables (V19), and `ACHOrigFile` got a `scheduleID`.
- **2026-11-12: Ba's follow-up.** Ready but not sent goes in the next business day's 6 AM file, so the 5 PM catch-all file and its job are gone; on a weekend or holiday the processing screens show the next business day, view only, and the API refuses process/send/receive (`BankingCalendar.businessDate`, `requireBankingDay`). New search screens: **ACH Origination Transactions** and **ACH Receiving Transactions**, by effective date (start to end, today by default), view only.
- **2026-11-12: ACH Business Rules.** A table (`ACHBusinessRule`, V20) of rules per app, each with pseudo code for people and a JSON rule for the machine: `{"when": {"all": [{field, op, value}]}, "then": [{set, value}]}`. A small evaluator (`rules/BusinessRules`, no library) runs them; receiving step 6 asks it for `earlyPosting`, which replaced the `TRUSTED_COMPANY_IDS` parameter. Rules are checked on save (unknown field or op → 400), so a typo can't break a processing window. Interview angle: business rules as data, so operations can change them without a deploy, with an audit trail (who, when).
- **Spec errors found in Ba's sample files** (worth mentioning in an interview, since this is what a validator is for): batch numbers had 6 digits instead of 7; some amounts were 11–12 digits instead of 10; several batch control totals didn't match their entries (e.g. $26,750 stated vs $26,700 in the entries); one routing number (`031000030`) failed the 3-7-1 check digit (the right one is `031000037`). The samples were rebuilt with computed control records.
