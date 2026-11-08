# Use Case Plans

the use case specs live in [ACH Payments System Design/](ACH%20Payments%20System%20Design/). The work is tracked as tasks on the GitHub Projects board (kanban). Each section below gives:
- what the use case needs (tables, API, UI, rules);
- the assumptions made where the spec is silent;
- how it will be verified.

The order matters. The Parameter UI comes first because it lays the groundwork the Transfer UI reuses:
- login and roles;
- the `AppParam` and `AppLog` tables;
- the validate-then-save pattern.

---

## UC-SYS-PARAM-01: Parameter UI

Spec: [Use Case Parameter UI.docx](ACH%20Payments%20System%20Design/Use%20Case%20Parameter%20UI.docx). Table: `AppParam` and `AppLog`, exactly as in the design doc. `paramDesc` is already in the DDL, so no table change is needed.

**Purpose (the designer, 2026-11-03):** a tool for the **system designers** to enter new parameters, and edit existing ones, for each app. Example: a `credit_limit` for ACH Origination transfers. The apps' own code reads these values later.

### What gets built

| Layer | Piece | Spec step |
| --- | --- | --- |
| DB | Flyway `V1__app_param_and_log.sql`: The design's `AppParam` and `AppLog` DDL, seeded with the two sample parameters | Precondition: the table has records |
| Security | Spring Security login. `ROLE_SYS_ADMIN` is required for every `/api/params` call. Anyone else gets **403**, and a `WARN` row is written to `AppLog`. | Precondition, EX-02 |
| API | `GET /api/params/systems`: the fixed app list below (code + name) for the dropdown | 1 |
| API | `GET /api/params?systemName=ACH_ORIG`: the grid rows | 1, 2 |
| API | `PUT /api/params/{systemName}/{paramName}`: an **upsert**. The body is `paramDesc`, `paramRawType` and `paramRawValue`. `systemName` must be one of the apps below. The server sets `lastModTm` itself and takes `lastModBy` from the logged-in user, never from the request. | 3, 4, 5, audit rule |
| Validation | The same rules run in the UI (before Save) and on the server, so a direct API call can't skip them: `INTEGER`, `DECIMAL`, `BOOLEAN` (`TRUE`/`FALSE` only), `JSON` (must parse) and `STRING`. Column lengths are checked too (30/50/255/500). | 4, EX-01 |
| Cache | `ParamCache` caches parameter lookups for the rest of the app, and is cleared on every save | 6 |
| UI | One page: system dropdown → grid → Edit / Add New modal. The value input changes with the type (BOOLEAN dropdown, number input, text area for JSON). Errors show inline in red and block Save. Saving shows the toast "Parameter updated successfully. Changes applied to system cache." | 1–6, EX-01, guidelines |

### Assumptions (the spec is silent)

- **P1. Login. ✅ Decided (2026-11-03): simple demo logins.** Real login, roles and MFA come later (roadmap step 16). For now there are two demo users, `admin` (`ROLE_SYS_ADMIN`) and `operator` (no admin role), using browser HTTP Basic login. Their passwords come from environment variables, like the DB password, and are never committed.
- **P2. Critical parameters. ✅ Dropped (the designer, 2026-11-03).** There's no reason dialog and no "critical" rule. The spec's "safety confirmation" guideline is out of scope.
- **P3. App list. ✅ Decided (2026-11-03): one `systemName` per UI in the diagram.** The list is fixed in code. The server rejects any other `systemName`, and new apps are added to the list as they're built.

  | `systemName` | App |
  | --- | --- |
  | `ACH_ORIG` | ACH Origination UI |
  | `ACH_ORIG_RETURN` | ACH Origination Return UI |
  | `ACH_ORIG_REVERSAL` | ACH Origination Reversal UI |
  | `ACH_ORIG_RESUB` | ACH Origination Resubmission UI |
  | `ACH_ORIG_NOC` | ACH Origination NOC UI |
  | `ACH_RCV` | ACH Receiving UI |
  | `ACH_RCV_RETURN` | ACH Receiving Return UI |
  | `ACH_RCV_REVERSAL` | ACH Receiving Reversal UI |
  | `ACH_RCV_NOC` | ACH Receiving NOC UI |

  the design's two sample parameters already use `ACH_ORIG` and `ACH_RCV`.
- **P4. Parameter names are saved as typed.** The design's example is lowercase (`credit_limit`) and the samples are uppercase, so the UI doesn't force either. The table's collation ignores case, so `credit_limit` and `CREDIT_LIMIT` count as the same parameter and can't both exist.
- **P5. No effective date yet.** [DB-MODEL.md](DB-MODEL.md) S2 suggests adding `effectiveDt` to `AppParam`. This use case keys the table on (`systemName`, `paramName`), so it's left out for now.

### What the trial build showed

A first version was built and tested before the plan was approved. It's kept on disk, **uncommitted**, for review. Results:
- 60 tests pass (43 Java, 7 of them against a real MySQL test database, plus 17 React).
- On the deployed app, these worked over the API: 401 when not logged in; 403 plus a `WARN` row in `AppLog` for a non-admin; 400 "Value must be a valid integer."; and `lastModBy` couldn't be spoofed.

**The trial code must change to match the design's answers:**
- remove the critical-parameter rule, the reason dialog and the reason/change rows written to `AppLog`;
- the dropdown comes from the fixed app list (P3), and the server rejects unknown apps;
- stop forcing parameter names to uppercase (P4).

The trial also changed the plan in four ways:
- **Database tests need a separate MySQL database.** The upsert SQL, MySQL row-alias syntax and timestamps can't be faked, so tests run against a dedicated `ach_payments_test` database, never the real one. The README needs a "Running the database tests" section.
- **`lastModTm` must be set explicitly on update.** MySQL's `ON UPDATE CURRENT_TIMESTAMP` doesn't fire when the saved values are identical, so re-saving the same value would keep the old timestamp.
- **A save without a valid CSRF token is logged as user `anonymous`.** It's rejected before login is checked, so it's labelled "Access denied" instead of "CSRF check failed". To fix: log it under the right label.
- **Number fields are text boxes with a numeric keyboard, not a strict mask.** A strict mask would make spec EX-01 (typing "five" shows an error) impossible.
- **Not done yet:** syntax highlighting for JSON values. They show in a plain monospace text area.

### Done when

- Tests cover each validation rule, passing and failing.
- An upsert inserts, then updates, with `lastModBy` coming from the logged-in user.
- A non-admin gets 403, and an `AppLog` row is written.
- Saving to an app outside the list is rejected.
- The cache is cleared on save.
- In the deployed app, an admin picks "ACH Origination UI", adds `credit_limit` (DECIMAL, `5000.00`), sees the toast, and the grid shows it with their user ID. Editing it to `abc` shows the inline error and blocks Save.

---

## UC-ACH-ORG-01: Origination Transfer UI (External ACH Transfer)

Spec: [Use Case ACH Origination Transfer UI.docx](ACH%20Payments%20System%20Design/Use%20Case%20ACH%20Origination%20Transfer%20UI.docx). Actor: a **retail member** in online banking. This is the "digital banking channel" from PLAN.md A8, which is the demo member portal in roadmap step 17.

### Tables it needs

| Table | In the design's model? | Used for |
| --- | --- | --- |
| `ACHOriginatorTransfer` | ✅ | The transfer request (step 6). It needs the [DB-MODEL.md](DB-MODEL.md) fixes. |
| `ACHOrigRecvAccount` | ✅ | The beneficiary's external account (step 3). No verification column for now, because the Plaid check runs on each transfer (T3). |
| `ACHOrigLimitRule` | ✅ | Daily and monthly caps (step 5, EX-02), used as the designer wrote it (T4) |
| `ACHOriginator`, `Source` | ✅ | `originatorID` (the credit union itself, for retail transfers) and `sourceID = 'ONLINE_BANKING'`. The `ONLINE_BANKING` `Source` row is seeded by V7 (D6), since `sourceID` now has a foreign key. |
| `AppParam` | ✅ (from UC-SYS-PARAM-01) | Transfer fee, cutoff times |
| `SimCoreAccount` | ❌ new (DB-MODEL M4) | The member's funding accounts and **available balance** (steps 1–2). The core is simulated (A2). |
| `ACHMemoPost` | ❌ new (DB-MODEL M4) | The immediate hold on the source account (step 7) |
| `BankingCalendar` | ❌ new (DB-MODEL M6) | Federal holidays, used by the date picker and for estimated arrival |
| `ABADirectory` | ❌ new | Routing number → bank name (step 3 lookup). A small seed list stands in for the Fed's E-Payments Routing Directory. |
| `OfacSdnName` | ❌ new | A simulated OFAC list for the screening check (step 5) |

### Flow → build pieces

| Step | Backend | UI |
| --- | --- | --- |
| 1–2 | `GET /api/member/accounts` returns the logged-in member's active accounts and available balances | **Transfer & Pay** menu → **Add ACH Transfer** (named "External ACH Transfer (Other Bank)" until 2026-11-07); source account picker that shows the balance |
| 3 | The recipient is one of the member's registered external accounts (EA5). Its routing number must pass the 3-7-1 check digit; the bank name comes from `ABADirectory` | **To** list of registered external accounts. On a bad check digit, EX-01 shows "Invalid routing number. Please check the 9-digit ABA number." |
| 4 | — | Amount input with commas and a max guardrail. The date picker blocks weekends and holidays. Frequency fields; memo (max 10 characters → `companyEntryDesc`). |
| 5 | `POST /api/transfers/preview` checks limits (sum of the member's pending and sent transfers within `dayNo` days, plus this one), the OFAC name check, and the **Plaid account check** (T3) | EX-02 modal: "This transfer exceeds your daily external limit of $X…" |
| 6 | `POST /api/transfers` re-runs every check on the server, generates `transferSerNo` (`TRF-yyyyMMdd-nnnn`, the design's format) and inserts with `processStatus = 'PENDING'`, `sourceID = 'ONLINE_BANKING'` and `lastModBy` = the logged-in member | Review screen: fee disclosure, estimated arrival date, transfer terms, and a **Confirm Transfer** button |
| 7 | In the same DB transaction, the memo hold reduces available balance, writes an `ACHMemoPost` row and sets `memoStatus = 'POSTED'` | Success modal with the reference number (`transferSerNo`), tracking details (status and estimated arrival) and a **Download receipt** button |
| EX-03 | If the Plaid check returns `false`, preview and submit both block the transfer | Message: this account couldn't be verified for ACH transfers |

Executing the transfer is **out of scope** for this use case and is picked up by roadmap steps 9–10. That covers:
- turning the request into an `ACHOrigTransaction`;
- building the Fed file;
- scheduling recurring runs from `nextPmtDt`.

### Questions (✅ all answered 2026-11-03; the design's spec is followed wherever it says something)

- **T1. SEC code and memo. ✅ Decided (2026-11-03): follow the design's spec.**
  - The member's memo goes in `companyEntryDesc`, as the design's spec step 4 maps it. That field holds 10 characters, so the memo input is limited to 10.
  - The SEC code is **WEB** (credit), the Nacha code for a consumer-initiated transfer to someone else's account. The design's spec doesn't name one.
- **T2. Who is the originator? ✅ Decided (2026-11-03):** the credit union. There's one `ACHOriginator` row for it, and the member is identified by `memberNo`, which the design's table already has.
- **T3. How are accounts verified (EX-03)? ✅ Decided (the designer, 2026-11-03):** one boolean function, `AccountVerifier.isEligibleForAch(routingNo, acctNo, acctType)`, which will call the **Plaid API** to check that the account exists and can take ACH transfers.
  - We don't have Plaid access yet, so the only implementation is an empty placeholder (`PlaidAccountVerifier`) that **always returns `true`**, with a TODO.
  - The real Plaid call comes later.
  - Tests swap in a version that returns `false`, to prove EX-03 blocks the transfer.
  - Micro-deposits are dropped for now.
- **T4. Limit tiers. ✅ Decided (2026-11-03): The design's `ACHOrigLimitRule` as written.** A rule applies to an account type (`CHECKING`, `SAVINGS` or `ALL`) over `dayNo` days. The check adds up the member's transfers from that account type within `dayNo` days, plus the new one, and compares the total to `limitAmt`.
- **T5. Fee and timing. ✅ Decided (2026-11-03):** free and next banking day.
  - The fee is a parameter (`ACH_ORIG` / `EXTERNAL_XFER_FEE` = `0.00`, editable in the Parameter UI).
  - The earliest effective date is the next banking day.
  - No Same Day for retail.
- **T6. OFAC hits. ✅ Decided (2026-11-03; UC-ACH-EOD-01 E2 renamed `CLEARED` to `OFAC_PASSED`): follow the design's `ofacStatus` values** (`CLEARED`, `FLAGGED`, `REVIEW`). A hit saves the transfer with `ofacStatus = 'FLAGGED'`, held until staff clear it, and the member sees "under review".
- **T7. Member login. ✅ Decided (2026-11-03):** demo member users with simulated MFA, the same approach as P1.
- **T8. Missing columns in `ACHOriginatorTransfer`. ✅ Decided (2026-11-03): add them, using the names the design's spec maps the inputs to.** The new columns are `effectiveDt DATE`, `companyEntryDesc VARCHAR(10)` (T1), `feeAmt DECIMAL(13,2)` and `createTm DATETIME`. The design's existing columns are unchanged.
- **T9. The sample routing number `051000031` is invalid.** It fails the ABA check digit (weighted sum 58), so EX-01 would reject it. The demo data (V7) uses `051000033`: the number with the correct check digit, which also matches the `05100003` prefix of the sample trace number.

### Done when

The Transfer UI is finished when all of these hold:
- A demo member can complete steps 1–7 end to end:
  - an `ACHOriginatorTransfer` row exists with `PENDING`;
  - available balance drops by the amount;
  - an `ACHMemoPost` row exists.
- Tests prove each of these:
  - EX-01: a bad check digit is rejected;
  - EX-02: a transfer one cent over the limit is rejected, and one at exactly the limit passes;
  - EX-03: when the account check returns `false`, the transfer is blocked;
  - the weekend/holiday date rules;
  - the server rejects a payload that skips UI validation;
  - `lastModBy` can't be spoofed.
- The screens use the design's wording: the "Transfer & Pay" menu, "External ACH Transfer (Other Bank)", "Confirm Transfer", tracking details and a receipt download. Since 2026-11-07 the menu item, page title and breadcrumb say **Add ACH Transfer**, as Ba asked.

---

## UC-ACH-EOD-01: EOD ACH File Generation & Fed Transmission

**Status: built.** Source: The design's `Use Case ACH Origination File Creation.docx`. This is the step after UC-ACH-ORG-01: at the cutoff, pending transfers become one NACHA file and go to the simulated Fed. Same Day windows (PLAN 5.6) stay out of scope, because the design's spec is one EOD file and T5 says no Same Day for retail. Board card R10 shrinks to those windows.

### Flow → build pieces

| Design step | What gets built |
|---|---|
| 1. Trigger & cutoff | A scheduler checks once a minute (America/New_York). It runs on banking days once `ACH_ORIG/FED_FILE_CUTOFF_TIME` has passed, so an admin's change takes effect without a restart. |
| 2. Extraction | Picks up `ACHOriginatorTransfer` rows with `processStatus = 'PENDING_BATCH'`, `ofacStatus = 'OFAC_PASSED'`, `memoStatus = 'POSTED'` and `effectiveDt <= today`. Each becomes one `ACHOrigTransaction` row with a trace number. |
| 3. Batches | Groups by SEC code, `companyID` and `odfiID`, plus `effectiveDt` and `companyEntryDesc` (E1). |
| 4. Records | `AchFile` + the existing `AchFileWriter`: Types 1, 5, 6, 8, 9, and the all-9s block padding the designer asks for. |
| 5. Totals | Entry counts, debit and credit totals, entry hash. The file is re-read with `AchFileParser` and `AchFileValidator` before it can be queued. A file that fails is never sent: nothing is saved, and `AppLog` gets a FATAL row. |
| 6. Persistence | One DB transaction writes `ACHOrigFile` (`fedStatus = 'QUEUED'`), `ACHOrigBatch`, the entries and the status changes. |
| 7. Transmission | The Fed simulator (PLAN A2) writes `<fileName>` to the `fed/outbound` folder. Success sets `fedStatus = 'SENT'` and `sendTm`, and adds an `ACHOrigFedAction` row. |
| EX-01 | Names are screened again during compilation. A hit sets `ofacStatus = 'FLAGGED'`, leaves the item out of the file and writes a WARN alert. The rest of the file proceeds. |
| EX-02 | 3 attempts in total, waiting 1 s then 2 s. If all fail: `fedStatus = 'ERROR'`, a FATAL `AppLog` row, and a notice on the ACH Origination File page. |
| Idempotency | A rerun first resends any `QUEUED` file, rebuilt from its saved rows, so the bytes match and no transactions are duplicated. Only rows still `PENDING_BATCH` go into a new file. A file in `ERROR` is only resent from the ACH Origination File page. |
| Audit trail | Each state change adds an `ACHOrigFedAction` row. MySQL triggers (V12) refuse `UPDATE` and `DELETE` on that table, to meet the design's "unalterable". With binary logging on, `ach_app` can only create them after one root setting: README section 4. `TRUNCATE` and `DROP` skip triggers, so a DB admin could still remove the table; closing that needs a separate, less privileged app user. |

### Decisions (✅ answered 2026-11-05)

- **E1. Which transfers go in tonight's file? ✅ Dated today, as the designer wrote.** 5 PM is after the last Same Day deadline, so the entry settles the next banking day. **Estimated arrival becomes `effectiveDt` + 1 banking day**, on the review page, the receipt and the online demo; today it equals `effectiveDt`. Transfers missed on an earlier day (for example, server down) are still picked up, because the query uses `<=` today.
- **E2. Status names. ✅ the design's new names.** New transfers are saved as `OFAC_PASSED` / `PENDING_BATCH`. The sample row is updated from `CLEARED` by a new migration, because V7 can't change. `FLAGGED` and `REVIEW` stay. This replaces T6's `CLEARED`.
- **E3. Screen. ✅ Admin "ACH Origination File" page in the real app and the online demo.** (Since 2026-11-07 it belongs to `achorig` only; see [API-VIEWER-PLAN.md](API-VIEWER-PLAN.md).) Run now, the list of files with status and totals, items left out, view and download the `.ach`, and Resend on an `ERROR` file. The online demo needs the NACHA format in TypeScript too, so a shared expected file (a golden file) is checked by both the Java and the TypeScript tests to stop them drifting.
- **E4. Recurring transfers. ✅ Follow the design's table design.** `pTransferSerNo` is "Parent Transfer Serial Number (for recurring transfer series)", so each payment is its own row linked to the first.
  - When a payment goes in a file, or is rejected, the next row is created with the next date, `pTransferSerNo` set to the first row, `transferReqNo` + 1 and `transferReqRemaining` - 1.
  - Dates are counted from the first payment's date (+7, +14 or +n months, so Jan 31 → Feb 28 → Mar 31). A non-banking day moves to the next banking day.
  - The first payment uses the hold from submit. Later ones place their hold, including the fee, when due.
  - **Insufficient funds:** that payment is marked `REJECTED` with no retry and logged, and the series continues. The design's docs are silent and credit union agreements differ (some reject with no retry, some retry, some cancel), so this is the simplest choice to change later.

### Fixes to what's already built

- **`transferReqNo` bug:** The designer defines it as the current position in a series, and the one-time sample has 1 with 0 remaining. `TransferService` stores the total count in both columns. Fix: 1 and count - 1, with the receipt showing `transferReqNo + transferReqRemaining` as the total.
- **Blank memo:** `companyEntryDesc` is required in a NACHA batch header, but the memo is optional. A blank memo sends `TRANSFER`. So does a memo that's a word Nacha reserves (`REVERSAL`, `RECLAIM`, `NONSETTLED`, `RETRY PYMT`, `REDEPCHECK`, `HCCLAIMPMT`, `AUTOENROLL`).

### Assumptions (the spec is silent)

- **E5. Batch grouping adds two fields.** A NACHA batch header has only one effective date and one entry description, and T1 puts the member's memo in that description. So each different memo gets its own batch. This is required for a valid file, and it's the one place this plan adds to Design step.
- **E6. New column `ACHOrigTransaction.recvRoutingNo CHAR(9) NULL`.** A Type 6 record needs the receiving bank's routing number, and the design's table has none. It's added, and `NULL` on the sample row.
- **E7. Values in the file.**
  - Service class `220` (credits only); the member's debit stays on the core side.
  - WEB credit (T1), tran code `22` for checking, `32` for savings.
  - Company ID `9000000001`, `odfiID` `12345678`, immediate origin `123456780`.
  - Immediate destination is a new parameter, `ACH_ORIG/FED_IMMEDIATE_DEST` = `011000015`.
  - Company name is a new parameter, `ACH_ORIG/ORIG_COMPANY_NAME` = `DEMO CU`, because "DEMO CREDIT UNION" is 17 characters and the field holds 16.
  - The fee isn't in the file. `settlementDt` stays `NULL`, because the Fed sets it.
- **E8. IDs.** `fileID` follows the sample, `FILE_yyyyMMdd_nn`, with `fileIDModifier` A, B, C… per day. `processID` is `EOD-yyyyMMddHHmmss-A<attempt>`, which keeps the `ACHOrigFedAction` key unique across retries (DB-MODEL F11). New `Action` rows sit next to 401 `FED_SEND`: `FILE_QUEUED`, `FED_SEND_FAILED`.
- **E9. Status after sending.** The designer (2026-11-05): the batch updates the transfer to **Processed**. So `ACHOriginatorTransfer.processStatus` goes `PENDING_BATCH` → `PROCESSED` when the transfer goes in a file, and delivery is tracked by `fedStatus` on the file and its entries: `QUEUED` → `SENT` or `ERROR`.
- **E8a. Action rows.** Next to the design's `401 FED_SEND`: `402 FILE_QUEUED`, `403 FED_SEND_FAILED`, `404 FILE_ERROR`.
- **E8b. Run now takes a business date.** A transfer made today is dated the next banking day or later, so running for today finds nothing. Design step 2 allows a "scheduled business date", so the page offers one, prefilled with the next date that has transfers waiting. The automatic 5 PM run always uses today.
- **E10. Nothing to send, or not a banking day:** no file, and an INFO row in `AppLog`.
- **E11. Simulated parts.** The design's "compliance dashboard" and "notification to operations" don't exist yet, so both are an `AppLog` row plus a notice on the ACH Origination File page, until card R15. `ACH_ORIG/FED_SIM_FAIL_ATTEMPTS` (default 0) makes the simulator fail, so EX-02 can be shown.
- **the sample transfer** `TRF-20261101-9921` has no `effectiveDt` and `memoStatus = 'PENDING'`, so it never enters a file.

### Build order

1. Migrations: V11 for the E2 status update, E6 column, E7 and E11 parameters and E8 actions; V12 for the audit triggers. → verify: full suite from empty DBs; a test proves `UPDATE` and `DELETE` on `ACHOrigFedAction` fail.
2. Fix `transferReqNo`, status names and estimated arrival. → verify: updated transfer tests, a Friday date shows Monday.
3. File builder (steps 2–5, E5). → verify: golden file, and the file passes `AchFileValidator`.
4. Persistence and idempotency (step 6). → verify: running twice gives one file and no duplicate entries; EX-01 leaves a flagged item out while the rest go.
5. Fed simulator, retries and scheduler (step 7, EX-02). → verify: tests for fail-twice-then-send, fail-three-times → `ERROR`, and the cutoff on non-banking days.
6. Recurring (E4). → verify: series dates, month-end, insufficient funds skips one payment and the series continues.
7. ACH Origination File page, real app. → verify: browser run from member transfer to `.ach` file.
8. Online demo, TypeScript builder. → verify: same golden file, same browser run.
9. Update `docs/DEMO.md`. → verify: every step followed once in both versions.

### Done when

- A member transfer dated today ends as `PROCESSED` with `fedStatus = 'SENT'`, in an `.ach` file that `AchFileValidator` passes, with rows in `ACHOrigFile`, `ACHOrigBatch`, `ACHOrigTransaction` and `ACHOrigFedAction`.
- Tests prove: EX-01, EX-02, a rerun duplicates nothing, block padding to a multiple of 10 records, and the audit table can't be changed.
- the designer can run it and open the file from the link, and on the real app.

---

## UC-ACH-RCV-01 and UC-ACH-RCV-02: Inbound FedACH file and Receiving Transaction UI

**Status: built.** Sources: The design's `Use Case - Inbound FedACH File - ACH Receiving Processing.docx`, `Use Case ACH Receiving Processing and UI.docx` (which says UC-ACH-RCV-04; the designer confirmed it's 02) and `NACHA ACH File Format Specification-2.docx`.

| Design step | What was built |
|---|---|
| 1. Ingestion | `ReceivingScheduler` picks up `fed/inbound/*.ach` once on each banking day at `ACH_RCV/FED_RECV_FILE_TIME` (06:00:00 Eastern). `ACHRecvFile` starts as `INGESTING`. |
| 2. Header | The signature, then the Type 1 record: destination = `ACH_RCV/RDFI_ROUTING_NO`, date, time and modifier valid, 94 characters. |
| 3-4. Batches, entries | `AchFileParser.stream` hands over one batch at a time; entries go in by bulk INSERT with `fedStatus = 'SETTLED'`, `processStatus = 'UNPROCESSED'`, `sourceID = 'FED_ACH_INBOUND'`. Addenda go to `ACHRecvAddenda`. |
| 5. Control check | `AchFileValidator.Check` compares Types 8 and 9 one batch at a time; the record count must be a multiple of 10. |
| 6. Handoff | `VALIDATED`, the file moves to `fed/archive`, and `ACH_FILE_INGESTED_EVENT` is published. It's only logged until the design's memo posting use case. |
| EX-01 | `REJECTED_SIG_FAIL`, `fed/quarantine`, a FATAL `AppLog` row. A missing `.sig` counts as a failure. |
| EX-02 | `OUT_OF_BALANCE`, everything rolled back, `fed/outbound/<fileID>.rejection.txt`, the stack trace in `AppLog`. |
| EX-03 | `DUPLICATE`, `fed/quarantine`, a WARN `AppLog` row, no entries. |
| RCV-02 screens | ACH Receiving File (by day) and ACH Inbound Overview, with `GET /api/v1/ach/receiving/transactions` and `/transactions/{tranID}`, EX-01 banner, EX-02 toast. |

Decisions:
- **R1. No OFAC on received entries.** Design: screened once, at origination. Real RDFIs still answer for their own members, and IAT entries can't rely on the ODFI.
- **R2. No memo posting yet.** Design: only certain received entries are memo posted, in a use case to come.
- **R3. Simulated.** Decryption is a log line, and the signature is a detached HMAC-SHA256 (`app.fed.signing-key`, PLAN A6). "Security Operations", "Operations" and the Fed rejection are an `AppLog` row, a page notice and a text file.
- **R4. `REJECTED`.** The designer has no exception for a file that's malformed or for another bank, so these get this extra status.
- **R5. `fileID` filter.** The design's SQL filters by file but the URL has no such parameter, so `fileID` is added.
- **R6. Blank settlement date.** Means the effective date.
- **R7. Duplicates, as the designer wrote it.** A file with the same date, time and modifier as any recorded file is `DUPLICATE`, even if the earlier one was rejected, so a corrected resend needs a new modifier.
- **R8. Streaming limit.** One batch is held in memory at a time, so a single huge batch is still all in memory.
- **R9. Roles.** `ROLE_SUPPORT` can read, but no support user exists yet. `achrecv` and admins can receive.

**Redesigned 2026-11-08 from Ba's blueprint** ([BLUEPRINT-PLAN.md §3](BLUEPRINT-PLAN.md)). These changes replace parts of the table above:
- Step 1 now runs at `ACH_RCV/RECV_WINDOW_TIMES` (06:00, 09:00, 12:00, 15:00, 18:00); `FED_RECV_FILE_TIME` is gone.
- Entries go in as `processStatus = 'RECEIVED'` (existing rows: `UNPROCESSED` → `RECEIVED`, `PROCESSED` → `POSTED`).
- New steps 4–7 (`ReceivedEntryProcessor`, `CorePostingFileJob`): account matching (exact, alias, zero padding), return codes (R01, R02, R03, R08, R10, R15, R16, R29), memo posting (debits, and credits from `TRUSTED_COMPANY_IDS`), and the end-of-day core posting file.
- R2 is replaced: memo posting is built.

## UC-ACH-ORG-01 and UC-ACH-EOD-01: Origination redesign (Ba's blueprint, 2026-11-08)

See [BLUEPRINT-PLAN.md §4](BLUEPRINT-PLAN.md). What changes in the two sections above:
- Submit still checks everything and places the hold at Confirm. The transfer then waits as `PENDING_BATCH`.
- On its date, windows at `ACH_ORIG/ORIG_WINDOW_TIMES` check it again: account status and funds, limits, OFAC. Then they mark it `READY_TO_POST`. A failure rejects it and releases the hold.
- An OFAC hit is `FLAGGED` and waits for the new `achcomp` compliance login (OFAC Review page). Clear sets it to `CLEARED`, and it goes on at the next window. Block sets it to `BLOCKED` and rejects it.
- EOD step 2 now picks up `READY_TO_POST` transfers whose `ofacStatus` is `OFAC_PASSED` or `CLEARED`, after one last window. The extra OFAC screen at compilation (EX-01) stays as a safety net. Hold placement for later payments of a series (E4) moved into the window.

## UC-ACH-BO: Back-office logins and screens

The designer's instruction: `achorig` sees the ACH origination back office, `achrecv` sees the ACH receiving back office, and the `ACHOperator` login is removed. The screens are guessed from the table design until the use cases arrive.

| Login | Role | Screens |
| --- | --- | --- |
| `admin` | `SYS_ADMIN` | System Configuration, System Audit & App Logs, Open Core Banking API, ACH Payments API |
| `achorig` | `ACH_ORIG_OPS` | ACH Origination File, Return, Reversal, Resubmission, NOC |
| `achrecv` | `ACH_RECV_OPS` | ACH Receiving File, ACH Receiving Transactions, Return, Reversal, NOC |

| Screen | Tables shown |
| --- | --- |
| ACH Origination Return | `ACHOrigReturn`, `ACHOrigReturnAction` |
| ACH Origination Reversal | `ACHOrigReversalAction`, with `ACHOrigFile` and `Action` |
| ACH Origination Resubmission | `ACHOrigResubAction`, with `ACHOrigFile` and `Action` |
| ACH Origination NOC | `ACHOrigNOC`, `ACHOrigNOCAction`, `ACHOrigAcctSubs`, `ACHOrigTransCodeSubs` |
| ACH Receiving Return | `ACHRecvReturn`, `ACHRecvReturnRequest` |
| ACH Receiving Reversal | `ACHRecvTransaction` rows with `companyEntryDesc = 'REVERSAL'` |
| ACH Receiving NOC | `ACHRecvNOC`, `ACHRecvAcctSubs`, `ACHRecvTransCodeSubs` |

Assumptions, to replace when the use cases arrive:
- **B1. Read-only.** The screens list rows, up to 100 per list, and show every field of a row. Nothing here changes data, because the engines and rules behind each action aren't specified yet.
- **B2. Admin.** Admin keeps its screens but not these seven. Since 2026-11-07 admin also no longer has ACH Origination File, ACH Receiving File or ACH Receiving Transactions, or their APIs ([API-VIEWER-PLAN.md](API-VIEWER-PLAN.md)).
- **B3. Receiving Reversal.** There's no receiving reversal table, so the screen shows received entries that Nacha marks as reversals.
- **B4. Sample rows.** V14 adds made-up rows so the screens aren't empty.

## External Bank Accounts (Ba, 2026-11-07)

Ba asked for an **External Bank Accounts** link in the member's ACH Origination UI: a list of the member's registered external accounts, and an **Add New External Bank Account** button whose new account then shows in the list. The API is Ba's `ach-origination-system.yaml`.

| Piece | Built |
| --- | --- |
| Menu | **Transfer & Pay ▾**: **Add ACH Transfer**, **External Bank Accounts** |
| List | `GET /api/v1/holders/{holderId}/external-accounts`: bank (from `ABADirectory`), masked account number, type, status. Newest first. |
| Add | `POST /api/v1/holders/{holderId}/external-accounts` with Ba's fields `bankName`, `routingNumber`, `accountNumber`, `accountType`, `accountHolderName`; returns `201` with `externalAccountId` and `status` |
| Table | `ACHOrigRecvAccount`, the table transfers already save recipients in. `externalAccountId` is `recvAcctSerNo` (`REC-yyyyMMdd-nnnn`). |
| Log | `ACH_ORIG INFO Member … added external account … ending 1234, pending micro-deposit verification` |

Decisions:
- **EA1. Status. ✅ Ba's `ACTIVE_PENDING_MICRO_DEPOSIT`.** Credit unions verify a new external account with two trial deposits under $1 and keep it pending until the member confirms the amounts (1–5 business days), and Nacha requires account validation before an account number is first used for WEB debits. The value is 28 characters but `acctStatus` is `VARCHAR(20)`, so, keeping the design's column, it's stored as `PENDING_MICRO_DEP` and the API returns Ba's value.
- **EA2. The list.** Ba's list returns "active" accounts, and a new account must show in it, so it returns `ACTIVE` and `ACTIVE_PENDING_MICRO_DEPOSIT` accounts. Recipients saved by transfers before EA5 appear as `ACTIVE`.
- **EA3. `holderId`** is the member number, and must be the signed-in member's own (403 otherwise). `/api/me` now returns `memberNo` so the screen can build the path.
- **EA4. Checks.** Name up to 22 characters, 4–17 digit account number, Checking or Savings. The routing number only needs 9 digits for now (the user, 2026-11-07), until real routing validation is written; the screen says "Enter all 9 digits of the routing number." once the member leaves the box with fewer. `bankName` is required, as in Ba's spec: the directory's name fills it, and for a routing number the directory doesn't know the member types it. The list shows the directory's name, or none. The same routing number, account number and type can't be added twice.
- **EA5. Add ACH Transfer picks the recipient (2026-11-07).** **To** is a list of the member's registered external accounts only, shown as bank, masked number and type (Ba's list fields); with none, it says to add one first and links to **Add External Bank Account**. The request sends `externalAccountId`, as in Ba's outbound transfer API, instead of a typed name, routing number and account; the server uses it only if it's the member's own `ACTIVE` or pending account. Pending accounts can be picked: the transfer's account check (EX-03) verifies it and makes it `ACTIVE`. A transfer no longer saves new recipient accounts. The transfer still needs the ABA check digit (EX-01), because the EOD job rejects a NACHA file with a bad one; a bank missing from `ABADirectory` passes with no bank name.
- **EA6. Not built yet.** Sending the micro-deposits and the member confirming them, removing an account, and real routing number validation (e.g. the Fed's E-Payments Routing Directory).

## UC-SYS-LOG-01: App Log capture and System Audit & App Logs UI

Spec: [Use Case AppLog UI.docx](ACH%20Payments%20System%20Design/Use%20Case%20AppLog%20UI.docx). Every row goes through one function, `AppLogRepository.log(...)`, which is the only code that inserts into `AppLog`.

### What gets logged

We log one row per business action, not one per SQL statement. OWASP's logging guide warns that logging too much hides real problems.

| Event | Level | `systemID` |
| --- | --- | --- |
| Sign-in and sign-out, on the sign-in page | INFO | `ACH_ORIG` for `achorig` and members, `ACH_RCV` for `achrecv`, `ACH_SYS` for admin |
| Failed sign-in | WARN | `ACH_SYS` |
| Access denied, failed CSRF check | WARN | `ACH_SYS` |
| Parameter saved | INFO | The parameter's app |
| Transfer submitted | INFO | `ACH_ORIG` |
| Transfer refused, by kind only | WARN | `ACH_ORIG` |
| Run now, Resend | INFO | `ACH_ORIG` |
| File sent to the Fed, by a user or the 5 PM job | INFO | `ACH_ORIG` |
| Receive file | INFO | `ACH_RCV` |
| Any failed Run now, Resend or Receive file | ERROR or WARN | As above |
| MFA, EOD and ingestion rows that already existed | As before | `ACH_ORIG` or `ACH_RCV` |
| Unhandled errors, with the stack trace | ERROR, or FATAL in a scheduled job | The app involved, otherwise `ACH_SYS` |

### Decisions and assumptions

- **L1. `systemID`.** An added nullable column. The design's table has no system field.
- **L2. `logTm`.** `DATETIME(3)`, marked FIX, because the use case shows milliseconds.
- **L3. Transactions.** A row written inside a transaction commits or rolls back with its action, so a failed action can't leave a success row in `AppLog`.
- **L4. Safety.** Messages lose control characters and stop at 1,000 characters, and a failed sign-in logs the name only if it's a real login, because someone may type a password in that box. Stack traces stop at 60,000 characters. Rows never hold passwords, MFA codes or full account numbers.
- **L5. EX-01.** If the insert fails, the entry goes to `~/dev/logs/app-error.log` (`APP_LOG_FALLBACK_FILE`), with no retry. Inside a transaction it waits for the outcome, and an action that failed is marked `[NOT COMMITTED]`. A write waits at most 5 seconds for a database connection.
- **L6. Filters.** App, level, keyword in `logMesg`, and From and To dates. The dates are whole days, both included. **App** (Ba, 2026-11-07) lists the 9 apps from System Configuration plus **ACH Payments System (`ACH_SYS`)**, which holds sign-ins, failed sign-ins and access-denied rows; the first app is selected, as in System Configuration. Today rows are written only as `ACH_ORIG`, `ACH_RCV` or `ACH_SYS`: the seven back-office apps are read-only screens that log nothing yet, so their lists are empty.
- **L7. Button name.** The use case says both "Inspect" and "View Details". The screen uses **View Details**, from its implementation section.
- **L8. Role.** The use case names `ROLE_ADMIN` or `ROLE_SYS_OPS`. We use `SYS_ADMIN`.
- **L9. Grid.** A System ID column is added to the use case's columns.
- **L11. Failed sign-ins.** At most 30 rows a minute, then one row saying the rest aren't logged, so a script can't fill the table. Sign-in attempts themselves aren't limited.
- **L12. Older rows.** V16 fills in `systemID` from each message.
- **L10. Not logged.** HTTP Basic calls from scripts, which send credentials on every request, and sessions that simply expire.

## Build order

1. **UC-SYS-PARAM-01.** ✅ Built (board P1–P3): matches the design's answers, and every "Done when" check passed in the deployed app.
2. **UC-ACH-ORG-01.** ✅ Built (board X1–X7, D6, R6, T7 member login): every "Done when" check passed, including a full member run in the deployed app (MFA → Transfer & Pay → EX-01/EX-02 → review → Confirm Transfer → receipt).
3. **UC-ACH-EOD-01.** ✅ Built.
4. **UC-ACH-RCV-01, UC-ACH-RCV-02.** ✅ Built.
5. **Ba's blueprint: Receiving and Origination redesign.** ✅ Built 2026-11-08 (BLUEPRINT-PLAN.md §7, steps 1–12).
