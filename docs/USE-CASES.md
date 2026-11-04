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

Spec: [Use-Case-Parameter-UI.docx](design/Use-Case-Parameter-UI.docx). Table: `AppParam` and `AppLog`, exactly as in Ba's design doc. `paramDesc` is already in his DDL, so no table change is needed.

**Purpose (Ba, 2026-11-03):** a tool for the **system designers** to enter new parameters, and edit existing ones, for each app. Example: a `credit_limit` for ACH Origination transfers. The apps' own code reads these values later.

### What gets built

| Layer | Piece | Spec step |
| --- | --- | --- |
| DB | Flyway `V1__app_param_and_log.sql`: Ba's `AppParam` and `AppLog` DDL, seeded with his two sample parameters | Precondition: the table has records |
| Security | Spring Security login. `ROLE_SYS_ADMIN` is required for every `/api/params` call. Anyone else gets **403**, and a `WARN` row is written to `AppLog`. | Precondition, EX-02 |
| API | `GET /api/params/systems`: the fixed app list below (code + name) for the dropdown | 1 |
| API | `GET /api/params?systemName=ACH_ORIG`: the grid rows | 1, 2 |
| API | `PUT /api/params/{systemName}/{paramName}`: an **upsert**. The body is `paramDesc`, `paramRawType` and `paramRawValue`. `systemName` must be one of the apps below. The server sets `lastModTm` itself and takes `lastModBy` from the logged-in user, never from the request. | 3, 4, 5, audit rule |
| Validation | The same rules run in the UI (before Save) and on the server, so a direct API call can't skip them: `INTEGER`, `DECIMAL`, `BOOLEAN` (`TRUE`/`FALSE` only), `JSON` (must parse) and `STRING`. Column lengths are checked too (30/50/255/500). | 4, EX-01 |
| Cache | `ParamCache` caches parameter lookups for the rest of the app, and is cleared on every save | 6 |
| UI | One page: system dropdown → grid → Edit / Add New modal. The value input changes with the type (BOOLEAN dropdown, number input, text area for JSON). Errors show inline in red and block Save. Saving shows the toast "Parameter updated successfully. Changes applied to system cache." | 1–6, EX-01, guidelines |

### Assumptions (the spec is silent)

- **P1. Login. ✅ Decided (2026-11-03): simple demo logins.** Real login, roles and MFA come later (roadmap step 16). For now there are two demo users, `admin` (`ROLE_SYS_ADMIN`) and `operator` (no admin role), using browser HTTP Basic login. Their passwords come from environment variables, like the DB password, and are never committed.
- **P2. Critical parameters. ✅ Dropped (Ba, 2026-11-03).** There's no reason dialog and no "critical" rule. The spec's "safety confirmation" guideline is out of scope.
- **P3. App list. ✅ Decided (2026-11-03): one `systemName` per UI in the diagram.** The list is fixed in code. The server rejects any other `systemName`, and new apps are added to the list as they're built.

  | `systemName` | App |
  | --- | --- |
  | `ACH_ORIG` | ACH Origination UI |
  | `ACH_ORIG_RETURN` | ACH Origination Return UI |
  | `ACH_ORIG_REVERSAL` | ACH Origination Reversal UI |
  | `ACH_ORIG_RESUB` | ACH Origination Resubmission UI |
  | `ACH_ORIG_NOC` | ACH Origination NOC UI |
  | `ACH_RCV` | ACH Receiving UI |

  Ba's two sample parameters already use `ACH_ORIG` and `ACH_RCV`.
- **P4. Parameter names are saved as typed.** Ba's example is lowercase (`credit_limit`) and his samples are uppercase, so the UI doesn't force either. The table's collation ignores case, so `credit_limit` and `CREDIT_LIMIT` count as the same parameter and can't both exist.
- **P5. No effective date yet.** [DB-MODEL.md](DB-MODEL.md) S2 suggests adding `effectiveDt` to `AppParam`. This use case keys the table on (`systemName`, `paramName`), so it's left out for now.

### What the trial build showed

A first version was built and tested before the plan was approved. It's kept on disk, **uncommitted**, for review. Results:
- 60 tests pass (43 Java, 7 of them against a real MySQL test database, plus 17 React).
- On the deployed app, these worked over the API: 401 when not logged in; 403 plus a `WARN` row in `AppLog` for a non-admin; 400 "Value must be a valid integer."; and `lastModBy` couldn't be spoofed.

**The trial code must change to match Ba's answers:**
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

Spec: [Use-Case-ACH-Origination-Transfer-UI.docx](design/Use-Case-ACH-Origination-Transfer-UI.docx). Actor: a **retail member** in online banking. This is the "digital banking channel" from PLAN.md A8, which is the demo member portal in roadmap step 17.

### Tables it needs

| Table | In Ba's model? | Used for |
| --- | --- | --- |
| `ACHOriginatorTransfer` | ✅ | The transfer request (step 6). It needs the [DB-MODEL.md](DB-MODEL.md) fixes. |
| `ACHOrigRecvAccount` | ✅ | The beneficiary's external account (step 3). No verification column for now, because the Plaid check runs on each transfer (T3). |
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
| 5 | `POST /api/transfers/preview` checks limits (sum of the member's pending and sent transfers within `dayNo` days, plus this one), the OFAC name check, and the **Plaid account check** (T3) | EX-02 modal: "This transfer exceeds your daily external limit of $X…" |
| 6 | `POST /api/transfers` re-runs every check on the server, generates `transferSerNo` (`TRF-yyyyMMdd-nnnn`, Ba's format) and inserts with `processStatus = 'PENDING'`, `sourceID = 'ONLINE_BANKING'` and `lastModBy` = the logged-in member | Review screen: fee, estimated arrival, terms, Confirm |
| 7 | In the same DB transaction, the memo hold reduces available balance, writes an `ACHMemoPost` row and sets `memoStatus = 'POSTED'` | Success modal with the reference number and a printable receipt |
| EX-03 | If the Plaid check returns `false`, preview and submit both block the transfer | Message: this account couldn't be verified for ACH transfers |

Executing the transfer is **out of scope** for this use case and is picked up by roadmap steps 9–10. That covers:
- turning the request into an `ACHOrigTransaction`;
- building the Fed file;
- scheduling recurring runs from `nextPmtDt`.

### Questions for Ba (the spec doesn't settle these)

- **T1. Which SEC code and description?** A consumer sending money to someone else's account at another bank is normally a **WEB credit** under Nacha rules. The spec maps the user's memo to `companyEntryDesc`, but that is a 10-character **batch-level** field the receiving bank shows on statements, e.g. `TRANSFER`. **Proposal:** set `companyEntryDesc = 'TRANSFER'` and carry the member's memo in an 05 addenda record, which holds up to 80 characters.
- **T2. Who is the originator?** For a retail member's transfer, the proposal is that the credit union is the `originatorID`/Company ID, and the member is identified by `memberNo`.
- **T3. How are accounts verified (EX-03)? ✅ Decided (Ba, 2026-11-03):** one boolean function, `AccountVerifier.isEligibleForAch(routingNo, acctNo, acctType)`, which will call the **Plaid API** to check that the account exists and can take ACH transfers.
  - We don't have Plaid access yet, so the only implementation is an empty placeholder (`PlaidAccountVerifier`) that **always returns `true`**, with a TODO.
  - The real Plaid call comes later.
  - Tests swap in a version that returns `false`, to prove EX-03 blocks the transfer.
  - Micro-deposits are dropped for now.
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
  - EX-03: when the account check returns `false`, the transfer is blocked;
  - the weekend/holiday date rules;
  - the server rejects a payload that skips UI validation;
  - `lastModBy` can't be spoofed.

---

## Build order

1. **UC-SYS-PARAM-01.** A trial build exists, uncommitted. Once you approve this plan, update it to match Ba's answers (the list above), apply the trial fixes, and commit.
2. Ba answers DB-MODEL.md section 6 and T1, T2, T4, T5 and T6 above.
3. **UC-ACH-ORG-01**:
   - **a.** migrations for its tables;
   - **b.** routing, calendar and limit services with tests;
   - **c.** the transfer and memo-hold API;
   - **d.** the member UI.
