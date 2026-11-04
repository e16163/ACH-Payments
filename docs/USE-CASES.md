# Use Case Plans

Ba's use case specs live in [design/](design/). Each section below gives:
- what the use case needs (tables, API, UI, rules);
- the assumptions made where the spec is silent;
- how it will be verified.

The order matters. The Parameter UI comes first because it lays the groundwork the Transfer UI reuses:
- login and roles;
- the `AppParam` and `AppLog` tables;
- the validate-then-save pattern.

---

## UC-SYS-PARAM-01: Parameter UI

Spec: [Use-Case-Parameter-UI.docx](design/Use-Case-Parameter-UI.docx). Table: `AppParam` and `AppLog`, exactly as in Ba's design doc.

### What gets built

| Layer | Piece | Spec step |
| --- | --- | --- |
| DB | Flyway `V1__app_param_and_log.sql`: Ba's `AppParam` and `AppLog` DDL, seeded with his two sample parameters | Precondition: the table has records |
| Security | Spring Security login. `ROLE_SYS_ADMIN` is required for every `/api/params` call. Anyone else gets **403**, and a `WARN` row is written to `AppLog`. | Precondition, EX-02 |
| API | `GET /api/params/systems`: the distinct `systemName`s for the dropdown | 1 |
| API | `GET /api/params?systemName=ACH_ORIG`: the grid rows | 1, 2 |
| API | `PUT /api/params/{systemName}/{paramName}`: an **upsert**. The body is `paramDesc`, `paramRawType`, `paramRawValue`, and `reason` (required only for critical params). The server sets `lastModTm` itself and takes `lastModBy` from the logged-in user, never from the request. | 3, 4, 5, audit rule |
| Validation | The same rules run in the UI (before Save) and on the server, so a direct API call can't skip them: `INTEGER`, `DECIMAL`, `BOOLEAN` (`TRUE`/`FALSE` only), `JSON` (must parse) and `STRING`. Column lengths are checked too (30/50/255/500). | 4, EX-01 |
| Cache | `ParamCache` caches parameter lookups for the rest of the app, and is cleared on every save | 6 |
| UI | One page: system dropdown → grid → Edit / Add New modal. The value input changes with the type (BOOLEAN dropdown, number input, text area for JSON). Errors show inline in red and block Save. Saving shows the toast "Parameter updated successfully. Changes applied to system cache." | 1–6, EX-01, guidelines |
| UI | **Critical parameters** open a second dialog where the admin must type a reason before the save goes through | Guidelines: safety confirmation |

### Assumptions (the spec is silent)

- **P1. Login. ✅ Decided (2026-11-03): simple demo logins.** Real login, roles and MFA come later (roadmap step 16). For now there are two demo users, `admin` (`ROLE_SYS_ADMIN`) and `operator` (no admin role), using browser HTTP Basic login. Their passwords come from environment variables, like the DB password, and are never committed.
- **P2. What counts as "critical". ❓ Open, ask Ba.** The default is that a parameter is critical if its name contains `CUTOFF` or `LIMIT`, which matches the spec's own examples ("cutoff times or limits"). The alternatives are a reason for every change, or an explicit list from Ba.
- **P3. Where the reason goes.** Each save writes an `INFO` row to `AppLog` with the user, the old value, the new value and the reason. That is the change history, so no new table is needed.
- **P4. New systems.** "Add New Parameter" adds to the system selected in the dropdown. The dropdown lists the systems already in the table.
- **P5. No effective date yet.** [DB-MODEL.md](DB-MODEL.md) S2 suggests adding `effectiveDt` to `AppParam`. This use case keys the table on (`systemName`, `paramName`), so it's left out for now.

### What the trial build showed

A first version was built and tested before the plan was approved. It's kept on disk, **uncommitted**, for review. Results:
- 60 tests pass (43 Java, 7 of them against a real MySQL test database, plus 17 React).
- On the deployed app, everything in "Done when" below worked over the API: 401 when not logged in; 403 plus a `WARN` row in `AppLog` for a non-admin; 400 "Value must be a valid integer."; a critical change without a reason rejected; `lastModBy` couldn't be spoofed; and the change history row includes the reason.

The trial changed the plan in four ways:
- **Database tests need a separate MySQL database.** The upsert SQL, MySQL row-alias syntax and timestamps can't be faked, so tests run against a dedicated `ach_payments_test` database, never the real one. The README needs a "Running the database tests" section.
- **`lastModTm` must be set explicitly on update.** MySQL's `ON UPDATE CURRENT_TIMESTAMP` doesn't fire when the saved values are identical, so re-saving the same value would keep the old timestamp.
- **A save without a valid CSRF token is logged as user `anonymous`.** It's rejected before login is checked, so it's labelled "Access denied" instead of "CSRF check failed". To fix: log it under the right label.
- **Number fields are text boxes with a numeric keyboard, not a strict mask.** A strict mask would make spec EX-01 (typing "five" shows an error) impossible.
- **Not done yet:** syntax highlighting for JSON values. They show in a plain monospace text area.

### Done when

- Tests cover each validation rule, passing and failing.
- An upsert inserts, then updates, with `lastModBy` coming from the logged-in user.
- A non-admin gets 403, and an `AppLog` row is written.
- A critical change without a reason is rejected.
- The cache is cleared on save.
- In the deployed app, an admin edits `FED_FILE_CUTOFF_TIME` with a reason, sees the toast, and the grid shows the new value and their user ID.

---

## UC-ACH-ORG-01: Origination Transfer UI (External ACH Transfer)

Spec: [Use-Case-ACH-Origination-Transfer-UI.docx](design/Use-Case-ACH-Origination-Transfer-UI.docx). Actor: a **retail member** in online banking. This is the "digital banking channel" from PLAN.md A8, which is the demo member portal in roadmap step 17.

### Tables it needs

| Table | In Ba's model? | Used for |
| --- | --- | --- |
| `ACHOriginatorTransfer` | ✅ | The transfer request (step 6). It needs the [DB-MODEL.md](DB-MODEL.md) fixes. |
| `ACHOrigRecvAccount` | ✅ | The beneficiary's external account (step 3). It needs a **`verifyStatus`** column for EX-03. |
| `ACHOrigLimitRule` | ✅ | Daily and monthly caps (step 5, EX-02). It needs per-tier or per-member scope (DB-MODEL S1). |
| `ACHOriginator`, `Source` | ✅ | `originatorID` (the credit union itself, for retail transfers) and `sourceID = 'ONLINE_BANKING'` |
| `AppParam` | ✅ (from UC-SYS-PARAM-01) | Transfer fee, cutoff times |
| `SimCoreAccount` | ❌ new (DB-MODEL M4) | The member's funding accounts and **available balance** (steps 1–2). The core is simulated (A2). |
| `ACHMemoPost` | ❌ new (DB-MODEL M4) | The immediate hold on the source account (step 7) |
| `BankingCalendar` | ❌ new (DB-MODEL M6) | Federal holidays, used by the date picker and for estimated arrival |
| `ABADirectory` | ❌ new | Routing number → bank name (step 3 lookup). A small seed list stands in for the Fed's E-Payments Routing Directory. |
| `OfacSdnName` | ❌ new | A simulated OFAC list for the screening check (step 5) |

### Flow → build pieces

| Step | Backend | UI |
| --- | --- | --- |
| 1–2 | `GET /api/member/accounts` returns the logged-in member's active accounts and available balances | Source account picker that shows the balance |
| 3 | `GET /api/routing/{rtn}` runs the 3-7-1 check digit and the directory lookup, then returns the bank name | Inline bank name once 9 digits are typed. On failure, EX-01 shows "Invalid routing number. Please check the 9-digit ABA number." and Submit stays disabled. |
| 4 | — | Amount input with commas and a max guardrail. The date picker blocks weekends and holidays. Frequency fields; memo. |
| 5 | `POST /api/transfers/preview` checks limits (sum of the member's pending and sent transfers within `dayNo` days, plus this one) and the OFAC name check | EX-02 modal: "This transfer exceeds your daily external limit of $X…" |
| 6 | `POST /api/transfers` re-runs every check on the server, generates `transferSerNo` (`TRF-yyyyMMdd-nnnn`, Ba's format) and inserts with `processStatus = 'PENDING'`, `sourceID = 'ONLINE_BANKING'` and `lastModBy` = the logged-in member | Review screen: fee, estimated arrival, terms, Confirm |
| 7 | In the same DB transaction, the memo hold reduces available balance, writes an `ACHMemoPost` row and sets `memoStatus = 'POSTED'` | Success modal with the reference number and a printable receipt |
| EX-03 | An unverified recipient account blocks submit | "Verify this account" prompt (see T3) |

Executing the transfer is **out of scope** for this use case and is picked up by roadmap steps 9–10. That covers:
- turning the request into an `ACHOrigTransaction`;
- building the Fed file;
- scheduling recurring runs from `nextPmtDt`.

### Questions for Ba (the spec doesn't settle these)

- **T1. Which SEC code and description?** A consumer sending money to someone else's account at another bank is normally a **WEB credit** under Nacha rules. The spec maps the user's memo to `companyEntryDesc`, but that is a 10-character **batch-level** field the receiving bank shows on statements, e.g. `TRANSFER`. **Proposal:** set `companyEntryDesc = 'TRANSFER'` and carry the member's memo in an 05 addenda record, which holds up to 80 characters.
- **T2. Who is the originator?** For a retail member's transfer, the proposal is that the credit union is the `originatorID`/Company ID, and the member is identified by `memberNo`.
- **T3. How are accounts verified (EX-03)? ✅ Decided (2026-11-03):** simulated "instant verification" now, which marks the account verified. Real micro-deposits (two small ACH credits the member confirms) come after the outbound file builder (roadmap step 10).
- **T4. What are the limit tiers?** Is a "tier" a retail/business grouping, or are limits set per member? This decides how `ACHOrigLimitRule` is scoped (DB-MODEL S1).
- **T5. Fee and timing.** What fee should be shown, and should retail transfers be offered Same Day? **Proposal:** a fee from `AppParam` (default $0), and the earliest effective date is the next banking day, with no Same Day for retail at first.
- **T6. OFAC hits.** When a screening hit happens, should the transfer be rejected, or saved as `ofacStatus = 'REVIEW'` for staff to clear? **Proposal:** saved for review, and the member sees "under review".
- **T7. Member login. ✅ Decided (2026-11-03):** demo member users with simulated MFA, the same approach as P1.

### Done when

The Transfer UI is finished when all of these hold:
- A demo member can complete steps 1–7 end to end:
  - an `ACHOriginatorTransfer` row exists with `PENDING`;
  - available balance drops by the amount;
  - an `ACHMemoPost` row exists.
- Tests prove each of these:
  - EX-01: a bad check digit is rejected;
  - EX-02: a transfer one cent over the limit is rejected, and one at exactly the limit passes;
  - EX-03: an unverified account is blocked;
  - the weekend/holiday date rules;
  - the server rejects a payload that skips UI validation;
  - `lastModBy` can't be spoofed.

---

## Build order

1. **UC-SYS-PARAM-01.** A trial build exists, uncommitted. Once this plan and P2 are approved, commit it with the four fixes above.
2. Ba answers DB-MODEL.md section 6 and T1–T7 above.
3. **UC-ACH-ORG-01**:
   - **a.** migrations for its tables;
   - **b.** routing, calendar and limit services with tests;
   - **c.** the transfer and memo-hold API;
   - **d.** the member UI.
