# Plan: Ba's Blueprint (2026-11-08)

**Status: approved 2026-11-08.** Every question in section 6 was answered with the recommendation; building follows section 7.

Ba sent 4 documents:

| Document | What it is |
| --- | --- |
| `ACH Receiving Processing.docx` | The blueprint for Receiving: 7 steps across 5 intraday windows, 7 endpoints, 2 tables |
| `ACH Origination Processing.docx` | The blueprint for Origination: 8 steps across 4 intraday windows, 8 endpoints |
| `NACHA ACH File Format Specification 01 - Overview.docx` | Record types, entry hash, and 2 sample files. Same text as the earlier `NACHA ACH File Format Specification-2.docx` |
| `NACHA ACH File Format Specification 02 - Details.docx` | Field-by-field positions for record types 1, 5, 6, 8 and 9, plus padding rules |

Ba's message asks for 4 things:
1. **A folder** called "ACH Payments System Design" holding every document Ba has sent.
2. **A NACHA parser** that parses and processes each batch and its transactions, for Receiving (the file we get) and Origination (the file we send).
3. **A redesign of Receiving and Origination** processing and posting from the blueprint.
4. **Study notes** in `docs/ACH-NOTES.md` (already started).

---

## 1. Folder of Ba's documents

**Today:** 5 of Ba's documents are in `docs/design/` under renamed copies. The rest are only in `~/Downloads`.

**Plan:** Create `docs/ACH Payments System Design/` in the repo. That way it's backed up on GitHub, the friend helping on the project gets it, and I can read it in later sessions. Copy in every document Ba sent, keeping Ba's original file names:
- 2 blueprints, 2 NACHA specs plus the earlier `-2` spec
- the system design doc and the architecture diagram
- 7 use cases
- 3 Open API docs

Then move out the 5 duplicates in `docs/design/`, delete that folder, and repoint the 6 links to it (DB-MODEL.md, USE-CASES.md, README.md). Not copied: the `FILE_20261105_01*.ach` files in Downloads, because those are files our own app made, not Ba's.

→ verify: `ls` shows 18 files; every link in docs/ and README opens.

## 2. NACHA parser against Ba's spec

**Today:** `nacha/AchFileParser` already parses all record types and streams one batch at a time. `AchFileWriter` writes them, and `AchFileValidator` checks lengths, check digits, entry hash, counts, totals and blocking. Receiving and Origination both use it. So this is a **check against Ba's spec plus tests**, not a rewrite.

**Problems found in Ba's spec** (the real NACHA layout is what the Fed accepts, so the parser must follow it; see question Q4):

| Where | Ba's spec says | NACHA standard (our parser) |
| --- | --- | --- |
| Details, Type 1 | Destination Name at 41–45, 35 long; Origin Name 76–94, 19 long | Destination Name 41–63 (23), Origin Name 64–86 (23), Reference Code 87–94 (8) |
| Details, Type 5 | Discretionary Data 21–30 (10 long), which shifts every later field 10 places left, so the record ends at position 87 instead of 94 | Discretionary Data 21–40 (20), Company ID 41–50, SEC 51–53, Entry Description 54–63, Descriptive Date 64–69, Effective Date 70–75, Settlement 76–78, Status 79, ODFI 80–87, Batch No. 88–94. The Overview's "SEC at 51–53" matches this. |
| Overview, Type 6 | Amount at 30–41, 12 long | 30–39, 10 long. Ba's Details says 30–39 too. |
| Overview, entry hash | "modulo 1010" | modulo 10¹⁰ (keep the last 10 digits), which Word turned into 1010 |
| Both sample files | Records run together with spaces collapsed (Word did this), so no line is 94 characters | They have to be rebuilt as real 94-character files before any parser can read them |

**Plan:**
1. Rebuild Ba's 2 sample files as real 94-character files in `src/test/resources/nacha/`, keeping every value Ba wrote. Recompute any control total, hash or count that doesn't add up, and list each fix in this doc.
   → verify: both pass `AchFileValidator`.
2. Add a parser test per sample that checks every batch: entry count, totals, and the first and last entry's name, amount and trace number.
   → verify: tests pass.
3. Add a test that walks Ba's Details tables field by field (types 6, 8 and 9 match NACHA exactly) and asserts the parser reads each field from the right positions.
   → verify: passes.
4. Change parser code **only if** a test finds a real gap.

## 3. Receiving redesign (we are the RDFI)

| Blueprint step | Today | Change |
| --- | --- | --- |
| 1. Windows 6 AM, 9 AM, 12 PM, 3 PM, 6 PM ET; `POST /api/v1/ach/receiving/trigger/{0600…1800}` | One run a day at 06:00 (`ACH_RCV/FED_RECV_FILE_TIME`) | One parameter listing the 5 window times. The scheduler runs at each one and picks up every file in `fed/inbound`. The 5 trigger endpoints run a window on demand and return Ba's `execution_id` (`EXEC-REC-yyyyMMdd-HHmm`). |
| 2. Validate structure, **save raw text** (`POST …/validate`) | Validated; the raw file is kept only in `fed/archive` | Add a raw-text column to `ACHRecvFile`. The endpoint returns Ba's response fields. |
| 3. Extract batches and transactions (`POST …/extract`) | Done in the same pass as step 2 | Split it into its own step and endpoint. The file status records how far it got, so a rerun picks up where it stopped. |
| 4. Transcodes and account matching (`POST …/process-transcodes-and-accounts`) | **Not built** | Map NACHA tran codes to core codes using `ACHRecvTransCodeSubs` (already in Ba's design). Match the account in `SimCoreAccount`. Auto-correct by zero padding, or by a known alias from `ACHRecvAcctSubs` (already in Ba's design), and store the corrected number. Anything still unmatched is flagged `MANUAL_REVIEW`. |
| 5. Rejections with return codes (`POST …/evaluate-rejections`) | **Not built** | Rules, in order: invalid tran code, no account `R03`, closed `R02`, frozen `R16`, insufficient funds on a debit `R01`, stop payment `R08`, blocked originator. Each rejection writes the code on the transaction and an `ACHRecvReturn` row. |
| 6. Memo posting (`POST …/memo-post`) | `MemoPostingEngine` exists (with a `RECV` source) but nothing calls it for received entries | Memo post entries that pass. Credits from **trusted Company IDs** (payroll, government benefits) are made available now; debits get a hold. Anything else waits for step 7. |
| 7. EOD core posting file (`POST …/generate-posting-file`) | **Not built** | At EOD, write `CORE_ACH_POSTING_yyyyMMdd.DAT` to `core/posting/`: one line per ready-to-post entry, with control totals. Only once per business date. Entries become `POSTED`. |

Ba's transaction statuses `RECEIVED → AUTO_CORRECTED → REJECTED / MEMO_POSTED → POSTED` go in `processStatus` on `ACHRecvTransaction` (Q2). Screens: the existing ACH Receiving pages show the new statuses, the corrected account, the return code, and a **Manual review** filter. Fixing manual-review items in the UI isn't in Ba's blueprint, so it isn't built.

## 4. Origination redesign (we are the ODFI)

**The big change:** today, every check and the memo hold happen the moment the member clicks **Confirm**. The blueprint moves processing into intraday windows on the payment's business date.

| Blueprint step | Today | Change |
| --- | --- | --- |
| Windows 6 AM, 9 AM, 12 PM, 3 PM ET (`POST /api/v1/ach/processing/trigger-window`) | Only the EOD job at 5 PM | One window parameter plus a scheduler; the endpoint runs a window on demand. The file still goes out at the 5 PM EOD run (step 8). |
| 1. Extract today's scheduled transfers (`POST …/origination/extract-schedules`) | The EOD job picks up `PENDING_BATCH` transfers | Pick up submitted transfers, and recurring payments by `nextPmtDt`, whose effective date is the business date. |
| 2. Account status and balance (`POST /api/v1/accounts/{accountNumber}/verify`) | Checked at submit only | Re-check in the window. A frozen or closed account, or insufficient funds, rejects the transfer with Ba's error format. |
| 3. Limits: daily, weekly, per transaction (`POST /api/v1/ach/limits/evaluate`) | Daily and monthly at submit, from `ACHOrigLimitRule` | Re-check in the window. Weekly is a 7-day `ACHOrigLimitRule` row (data, no code change). |
| 4. OFAC screening (`POST /api/v1/compliance/ofac/screen`) | At submit, and again in the EOD file job | Screen in the window. A hit gives `FLAGGED`, with Ba's `match_score` and `matched_list`. |
| 5. Resolve OFAC (`PATCH /api/v1/compliance/ofac/resolve`) | **Not built**; a flagged transfer just stays out of the file | Compliance officer page: list flagged transfers, then **Clear (false positive)** or **Block**, with notes. Cleared transfers continue at the next window; blocked ones are rejected and their hold released. Every action is logged. |
| 6. Memo posting (`POST /api/v1/accounts/memo-post`) | The hold is placed at submit | See Q3. |
| 7. Ready to post (`PATCH /api/v1/ach/transfers/status`) | `PENDING_BATCH` | `READY_TO_POST` replaces `PENDING_BATCH` as the status the EOD file job picks up. A migration renames existing rows. |
| 8. Batch compilation and FedACH (EOD) | Built (UC-ACH-EOD-01) | Picks up `READY_TO_POST` instead of `PENDING_BATCH`. Nothing else changes. |

The endpoints use Ba's JSON field names exactly (`transfer_id`, `business_date`, …) and are for back-office roles only; members never call them.

## 5. Notes

`docs/ACH-NOTES.md` gets each finished piece:
- the business-day timeline with both sets of windows
- account matching, auto-correction and return codes
- memo posting vs. final posting
- the OFAC review loop
- the spec errors from section 2 (a good interview story)

## 6. Decisions (✅ all answered 2026-11-08: the recommendation each time)

- ✅ **Q1. Where does the folder go?** **Chosen:** `docs/ACH Payments System Design/` in the repo, replacing `docs/design/`. Alternative: a folder outside the repo, e.g. `~/Documents/ACH Payments System Design`.
- ✅ **Q2. Table names.** The blueprint defines `ACHReceivingBatch` / `ACHReceivingTransaction` in snake_case. Ba's system design already has `ACHRecvBatch` / `ACHRecvTransaction`, which are built and hold the same data. **Chosen:** keep the existing tables and add only the missing columns: corrected account number, return code, memo-posted time, credit/debit indicator, raw file text. Alternative: create Ba's 2 new tables beside the old ones, which means two copies of every received entry.
- ✅ **Q3. When is the origination memo hold placed?** Ba's Transfer UI use case says at submit, and the blueprint says in the window. **Chosen:** keep the hold at submit, so the member can't spend the money twice before the window. In the window, step 6 confirms the hold exists, and places it only for recurring payments that don't have one yet. Alternative: move the hold to the window only.
- ✅ **Q4. Ba's spec errors (section 2).** **Chosen:** follow the NACHA standard positions, because files built from Ba's Type 1 and Type 5 tables would be rejected by the Fed. Record each difference here and tell Ba.
- ✅ **Q5. Receiving returns.** **Chosen:** for now, assign the return code and store it in `ACHRecvReturn`. Sending return entries back to the Fed in a file comes later; the blueprint doesn't cover it.
- ✅ **Q6. Trusted Company IDs** for receiving memo posting. **Chosen:** a parameter `ACH_RCV/TRUSTED_COMPANY_IDS`, editable on the Parameter page, seeded with Ba's sample payroll ID `1900483920`.
- ✅ **Q7. Stop payments (`R08`) and blocked originators.** Nothing stores these yet. **Chosen:** a small table for each, filled by seed data only; no screens for now.
- ✅ **Q8. OFAC compliance officer.** **Chosen:** a new back-office login `achcomp` (role `COMPLIANCE`) that sees only the OFAC review page.
- ✅ **Q9. Online demo.** **Chosen:** update it to match, after the real app, as with every earlier feature.

- ✅ **Q10. Routing check at the transfer step.** Kept: the Add External Bank Account box accepts any 9 digits (Ba), but sending a transfer still needs the 3-7-1 check digit, so one bad number can't stop the whole 5 PM file.

## 7. Build order (once approved)

Each step ends with the full test suite passing.

1. Folder and links (§1). → verify: every link opens.
2. NACHA samples and tests (§2). → verify: both of Ba's samples parse and validate; field positions are tested.
3. Receiving migration: new columns and parameters. → verify: migrations run on empty DBs.
4. Receiving windows and steps 2–3 split, with endpoints. → verify: a file dropped in `fed/inbound` is picked up at the next window; a rerun duplicates nothing.
5. Receiving step 4 (transcodes, accounts). → verify: one test each for matched, zero-padded, alias and not found.
6. Receiving step 5 (returns). → verify: one test per return code.
7. Receiving step 6 (memo post). → verify: a trusted credit raises available balance; a debit is held; others wait.
8. Receiving step 7 (EOD posting file). → verify: totals match; a second run makes no second file.
9. Origination migration and windows, steps 1–4. → verify: a transfer for today goes through the 9 AM window; a frozen account, an over-limit transfer and an OFAC hit each stop it.
10. OFAC resolve page and endpoint. → verify: Clear lets the transfer reach the EOD file; Block releases the hold.
11. Steps 6–7 and EOD picking up `READY_TO_POST`. → verify: member transfer → window → 5 PM file, end to end in the browser.
12. Online demo (Q9).
13. USE-CASES.md, DEMO.md, ACH-NOTES.md. → verify: every step in DEMO.md followed once.
