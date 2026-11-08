# Demo guide for the designer

A walk-through of what's built from your documents, and what isn't yet.

## Two ways to run it

| | Online demo | Full app (the local machine) |
|---|---|---|
| Where | https://e16163.github.io/ACH-Payments/ | http://localhost:8080/ach/ |
| What runs | The real screens; the server's rules run in the browser on made-up data | Java server (Spring Boot on Tomcat) + MySQL, with your tables |
| Saved? | No. Resets when you close the tab | Yes, in the `ach_payments` / `ach_origination` / `ach_receiving` databases |
| Member login | `member` / `member123` | `member` / `member123` |
| Admin login | `admin` / `admin123` | the local admin login |
| ACH origination login | `achorig` / `achorig123` | `achorig` with the local password |
| ACH receiving login | `achrecv` / `achrecv123` | `achrecv` with the local password |
| Compliance login (OFAC Review) | `achcomp` / `achcomp123` | `achcomp` with the local password (`APP_COMP_PASSWORD`) |
| ACH files | Kept in the browser tab | Written to `~/dev/fed/outbound`, the simulated Fed |

Use the online demo to click through. Use the full app to see the rows land in your tables (the last section).

The bank is a made-up **Demo Credit Union**, not Navy Federal. The member is your sample member **M10982347 (John Doe)**, with your seed accounts: Primary Checking ••••1920 ($8,500) and Savings ••••1946 ($15,000).

---

## Part 1: Parameter UI (UC-SYS-PARAM-01)

1. Open the site and sign in as **admin**.
2. **System Configuration** opens: the AppParam table for the app chosen in **App** (`ACH_ORIG` first). It shows the name, description, type, value, last modified and modified by for each parameter.
3. **Search:** type `cutoff` in **Search parameters**. Only `FED_FILE_CUTOFF_TIME` is left. Type `zzz` and you get "No parameters match your search." Clear the search box.
4. **App switch:** choose **ACH Receiving UI (ACH_RCV)** and you see `MEMO_POST_IMMEDIATE`. Switch back to **ACH_ORIG**.
5. **EX-01 (invalid value):** click **Edit** on `EXTERNAL_XFER_FEE` (type DECIMAL) and type `abc` as the value. The form shows "Value must be a valid decimal number." and won't save.
6. **Save:** change the value to `2.50` and click **Save Changes**. You see "Parameter updated successfully. Changes applied to system cache." **Modified by** is now the admin, and **Last modified** is now. The server sets both; the screen can't send them.
7. **EX-02 (no permission):** a member who calls the parameter API gets 403, and the server writes a WARN row to AppLog. In the demo, the member simply never sees this menu.
8. Click **Sign out** at the top right.

Keep the $2.50 fee; Part 2 uses it.

## Part 2: External ACH Transfer (UC-ACH-ORG-01)

**Step 1 (log in + MFA):**
1. Sign in as **member**. Before anything else, it asks "Verify it's you".
2. Click **Send verification code**. The demo shows the code on screen; a real credit union would text it.
3. Type the code and click **Verify**. **Accounts** opens, with balances and account numbers masked to the last 4.

**Navigate:** **Transfer & Pay ▾ → Add ACH Transfer**.

**Step 2 (source account):** **Primary Checking ••••1920** is picked, with "Available balance: $8,500.00".

**Step 3 (recipient):** **To** only lists your registered external bank accounts (Ba, 2026-11-07). With none, it says to add one first and shows an **Add External Bank Account** button.
1. Go to **Transfer & Pay ▾ → External Bank Accounts** and click **Add New External Bank Account**.
2. **Account holder name:** `Jane Smith`. **Routing number:** type `02100002` (8 digits) and click out of the box: "Enter all 9 digits of the routing number." For now any 9 digits pass, until real routing validation is written.
3. Change it to `021000021`. You see "✓ JPMorgan Chase Bank, N.A.", looked up in the ABADirectory table. (A 9-digit number the directory doesn't know, such as Navy Federal's `256074974`, asks for the **Bank name** instead.)
4. **Account number:** `123456789`. **Account type:** Checking. Click **Add Account**.
5. Add a second account the same way: `Blocked Test Person`, `021000021`, account `987654321`, Checking (the OFAC check below uses it).
6. Back on **Add ACH Transfer**, pick **To: JPMorgan Chase Bank, N.A. ••••6789 · Checking (pending verification)**.

**EX-01 (bad routing):** add an account with routing `051000031`, the one from your sample data. It's saved (9 digits), with no bank name. Transfer to it and **Continue** says "Invalid routing number. Please check the 9-digit ABA number.": a transfer still needs the ABA check digit (weights 3-7-1), because the EOD job rejects a NACHA file with a bad one. We changed your sample to `051000033`, which passes (see DB-MODEL.md section 0).

**Step 4 (amount, date, frequency):**
1. **Amount:** type `1500`. It formats to `1,500`.
2. **Date picker:** weekends, Federal Reserve holidays (e.g. Thanksgiving, Nov 26) and past days are disabled. The earliest date is the next banking day (no Same Day for retail). Pick the first enabled day.
3. **Frequency:** One time. If you pick Weekly, Biweekly or Monthly, it asks for **Number of transfers (2 to 52)**.
4. **Memo:** `Rent` (up to 10 characters, stored in upper case).

**Step 5 (limits, EX-02):**
1. Change the amount to `30000` and click **Continue**. A popup says: "This transfer exceeds your daily external limit of $25,000. Please reduce the amount or contact support." That's your ACHOrigLimitRule `DAILY_CHECKING_DEBIT_CAP` (CHECKING, 1 day, $25,000).
2. Click **OK**, set the amount back to `1500`, and click **Continue**.

**EX-03 (account not verified):** the Plaid check is a placeholder that always passes, so this exception can't be shown yet. The error message and the place where it's checked are built.

**Step 6 (review and confirm):**
1. **Review your transfer** shows the fee, the total and the estimated arrival:
   - **Fee $2.50**, from the parameter you changed in Part 1
   - **Total $1,502.50**
   - **Estimated arrival** date: the next banking day after the transfer date, because the 5 PM file settles the next banking day
2. Under **Fee disclosure and transfer terms**, **Confirm Transfer** stays disabled until you tick the box. Tick it and click **Confirm Transfer**.

**Step 7 (confirmation):**
1. **Transfer scheduled** shows:
   - the reference number, `TRF-yyyyMMdd-0001` in your serial format
   - status **Scheduled**
   - the amount and recipient
   - the date and estimated arrival
2. **Download receipt** saves a text receipt.
3. Click **Done**, then **Accounts**. Checking now shows **$6,997.50**: $1,502.50 is on memo hold.

**OFAC screening:** start another transfer to **Blocked Test Person's** account (••••4321). Keep it; Part 3 uses it. That's a made-up name on the simulated SDN list. The review page says "This transfer will be reviewed by our compliance team before it's sent." The result is **Transfer under review**, and no money is held.

## Part 2B: External Bank Accounts

1. Still signed in as **member**, click **Transfer & Pay ▾ → External Bank Accounts**. The list shows the sample account, **Sample Bank ••••1012**, Checking, **Active**, plus the accounts you added in Part 2. Jane's ••••6789 is now **Active**: the transfer's account check verified it.
2. Click **Add New External Bank Account**. Enter `Jane Smith`, routing `021000021` (the bank name appears), account `55501234`, and **Savings**. Click **Add Account**.
3. The new account is first in the list, with status **Pending micro-deposit verification**, as credit unions do before an external account can be used.
4. Add the same account again: "This account is already in your external bank accounts."

---

## Part 3: End-of-day ACH file (UC-ACH-EOD-01)

From your blueprint: on the transfer's date, processing windows at 6 AM, 9 AM, 12 PM and 3 PM Eastern (`ORIG_WINDOW_TIMES`) check each transfer again (account, limits, OFAC, hold) and mark it `READY_TO_POST`. The file builds by itself after `FED_FILE_CUTOFF_TIME` (5 PM) from `READY_TO_POST` transfers only. **Run now** runs one last window, then builds the file, so you needn't wait for the windows.

1. Sign out, sign in as **achorig**, and click **ACH Origination File**.
2. You see:
   - **Waiting for a file (1):** Jane Smith's transfer
   - **Left out (1):** Blocked Test Person, reason **OFAC review**
   - a **Compliance alert** (your EX-01)
3. **Business date** is already set to the date of Jane's transfer. Run now takes a date because a transfer made today is dated the next banking day or later.
4. **EX-02 (the Fed is down):** sign in as **admin** and, in **System Configuration**, edit `FED_SIM_FAIL_ATTEMPTS` and set it to `3`. Sign back in as **achorig** and, in **ACH Origination File**, click **Run now**. The file ends in **ERROR** with an **Operations alert**. The full app waits 1 second, then 2, between its 3 tries.
5. As **admin**, set `FED_SIM_FAIL_ATTEMPTS` back to `0`. As **achorig**, click **Resend** on the file. It's now **SENT**.
6. Click **View** to read the NACHA file: the file header, batch header, Jane's entry line, the batch and file controls, and the rows of 9s that pad it to a multiple of 10 records. **Download** saves the `.ach` file.
7. Click **Run now** again: "Nothing is due". A rerun can't duplicate anything.

In the full app, the transfer's `processStatus` is now `PROCESSED` (the query in Part 4): `PENDING_BATCH` at Confirm, `READY_TO_POST` after a window, `PROCESSED` once in a file. There's no member screen yet that lists past transfers.

To see a recurring series, make a Weekly transfer with 3 payments in Part 2. Each time you run the file, the next payment appears under **Waiting**, with its own date and its own hold. If the money isn't there on that date, that payment is rejected, shows under **Left out**, and the series continues.

## Part 3A: OFAC Review (your blueprint, Origination step 5)

1. Sign out and sign in as **achcomp**. **OFAC Review** lists Blocked Test Person's transfer from Part 2.
2. Type a note and click **Clear** (a false positive). It leaves the list. Sign in as **achorig**: in **ACH Origination File** it's now under **Waiting**, and **Run now** sends it. Its hold is placed at the window, since none was placed at Confirm.
3. As **member**, make another transfer to Blocked Test Person, then, as **achcomp**, click **Block**. As **achorig**, it shows under **Left out** with the reason **OFAC blocked**, and it never goes in a file.

Only **achcomp** sees **OFAC Review** and can clear or block; the other logins are refused. In the full app, each decision is a row in `ach_origination.ACHOrigOfacReview`, with the officer's login and the note.

## Part 3B: ACH Receiving (UC-ACH-RCV-01, UC-ACH-RCV-02)

From your blueprint, the engine processes the Fed's files at 6 AM, 9 AM, 12 PM, 3 PM and 6 PM Eastern (`RECV_WINDOW_TIMES`) on banking days. **Receive file** simulates a delivery, so you needn't wait.

1. Sign out and sign in as **achrecv**. **ACH Receiving File** opens.
2. In **Simulate a file from the Fed**, choose **Sample 1: payroll (valid)** and click **Receive file**: **VALIDATED**, 50 entries, $129,450.00. Do the same with **Sample 2: retail (valid)**: 50 entries, $71,840.00 credits and $1,144.90 debits.
3. Click **View transactions** on Sample 1. The **ACH Inbound Overview** shows your 9 columns, 10 rows a page, sorted by trace number. Click **Next**.
4. Click **Select** on a row to see all 24 fields.
5. Back in **ACH Receiving File**, try your exceptions:
   - **EX-03:** receive Sample 1 again: **DUPLICATE**, no new entries.
   - **EX-02:** choose **Sample 1 with a wrong batch total**: **OUT_OF_BALANCE**, nothing saved, and a rejection notice for the Fed.
   - **EX-01:** choose **Sample 1 changed in transit**: **REJECTED_SIG_FAIL**, nothing saved, and a Security Operations alert. **View transactions** on it shows "No receiving transactions found in this file batch."

6. **Processing (your blueprint, steps 4–6).** Each entry's **Status** column shows what happened to it. Use the **Status** filter, and **Select** an entry to see its **Processing** section. In Sample 1:
   - most entries are **MEMO_POSTED**: the account was found and, as payroll from the trusted Company ID `1900483920` (`TRUSTED_COMPANY_IDS`), the money is available at once;
   - James Thomas (account `987654329019`) is **AUTO_CORRECTED** to `0987654329019` (zero padding), and Laura Anderson (`987654328019`) to `555000328019` (a known alias);
   - three are **REJECTED** with a return code: **R03** (no such account), **R16** (frozen) and **R02** (closed).
7. In Sample 2, the debits are held (**MEMO_POSTED**) unless they're returned: **R10** (blocked originator), **R08** (stop payment), and the same **R02**, **R16** and **R03** accounts as Sample 1. Credits from other companies stay **RECEIVED** until the end-of-day posting file.
   - **R01 (no funds)** needs Sample 2 *before* Sample 1 (in the online demo, a fresh tab): then Supplier Kappa's $19.99 debit meets a $5.00 balance. After Sample 1, Kappa already has an $8,500 payment from a trusted company, available at once, so the debit goes through. That's memo posting doing its job.

Entries aren't OFAC screened (they were screened at origination). In the full app, the end-of-day posting file (step 7) is written at 7 PM (`CORE_POSTING_TIME`) to `~/dev/core/posting`, and the returns wait as `PENDING` rows in `ACHRecvReturn`; sending them to the Fed is not built yet.

## Part 3C: Back-office screens (UC-ACH-BO)

These are read-only for now. They're guessed from your table design until your use cases arrive, and the rows are made-up samples. Click **Select** on any row to see all its fields.

1. Sign in as **achorig**. It opens on **ACH Origination File**, which works as in Part 3, with four more tabs:
   - **ACH Origination Return:** returns on entries we sent, and their actions
   - **ACH Origination Reversal** and **ACH Origination Resubmission:** the action rows, with the file each belongs to
   - **ACH Origination NOC:** NOCs we received, their actions, and the account and transaction code substitutions
2. Sign in as **achrecv**. It has the receiving screens from Part 3B, with three more tabs:
   - **ACH Receiving Return:** returns we sent, and return requests from other banks
   - **ACH Receiving Reversal:** received entries with `REVERSAL` as the Company Entry Description
   - **ACH Receiving NOC:** NOCs we sent, and the substitutions
3. **admin** has System Configuration, System Audit & App Logs, Open Core Banking API and ACH Payments API, and doesn't get these back-office screens.

## Part 3D: System Audit & App Logs (UC-SYS-LOG-01)

1. Do a few things first: sign in and out as **achorig** and **achrecv**, and type a wrong password once.
2. Sign in as **admin** and click **System Audit & App Logs**. **App** at the top lists the 9 apps, as in System Configuration, plus **ACH Payments System (ACH_SYS)**. **ACH Origination UI** opens first, with 20 rows a page, newest first, with milliseconds and a colored level badge.
3. **Filter:** pick **ACH Payments System (ACH_SYS)** and set **Level** to `WARN` to find the failed sign-in. Then try **Search**, **From** and **To**.
4. **EX-02:** search for `zzz`: "No system logs found matching the selected criteria."
5. Pick **ACH Receiving UI (ACH_RCV)**, set **Level** to `ALL`, clear the search, and click **View Details** on the sample `ERROR` row, `Failed to parse incoming ACH file HEADER record`. The stack trace opens in a code viewer, with **Copy stack trace**.

**EX-01** only happens when the database is down, so the entry goes to `~/dev/logs/app-error.log` in the full app. The online demo has no database.

## Part 3E: API pages

1. Sign in as **admin** and click **Open Core Banking API**. Pick an operation from the **Operation** list, for example **Screen Individual or Business Entity against OFAC Lists**. You see its method and path, description, input parameters, request body and responses, with each field's type, whether it's required, its description and example. The fields of `hits` are listed under it.
2. Click **ACH Payments API**. The **API** list switches between **ACH Origination API** (11 operations) and **ACH Receiving API** (4 operations).

These pages document the designer's specs. They don't call the APIs, which aren't built yet.

## Part 4: System Design (in the full app)

These screens write to your tables. On the local machine, after a transfer, run:

```sql
-- the transfer, PENDING_BATCH then PROCESSED, with T8 columns (originatorID 9000000001, sourceID ONLINE_BANKING)
SELECT transferSerNo, memberNo, transferAmt, feeAmt, effectiveDt, processStatus, ofacStatus, memoStatus, originatorID, lastModBy
FROM ach_origination.ACHOriginatorTransfer ORDER BY createTm DESC LIMIT 5;

-- the saved recipient (REC-yyyyMMdd-nnnn)
SELECT recvAcctSerNo, acctOwner, routingNo, acctType FROM ach_origination.ACHOrigRecvAccount ORDER BY recvAcctSerNo DESC LIMIT 5;

-- the memo hold and the balance it reduced
SELECT tranRef, acctNo, amount, postType, memoStatus FROM ach_payments.ACHMemoPost ORDER BY memoPostID DESC LIMIT 5;
SELECT acctNo, ledgerBal, availBal FROM ach_payments.SimCoreAccount;

-- the file, its batches and entries, and the audit trail
SELECT fileID, fedStatus, totalCreditCount, totalCreditAmt, sendTm FROM ach_origination.ACHOrigFile ORDER BY fileID DESC LIMIT 5;
SELECT transferSerNo, tranCode, recvRoutingNo, recvName, amount, traceNo, fedStatus FROM ach_origination.ACHOrigTransaction ORDER BY traceNo DESC LIMIT 5;
SELECT * FROM ach_origination.ACHOrigFedAction ORDER BY actionTm DESC LIMIT 10;

-- the files received from the Fed, with their batches and entries
SELECT fileID, fileName, fileHeaderDt, fileHeaderTm, processStatus, totalCreditAmt, totalDebitAmt FROM ach_receiving.ACHRecvFile;
SELECT batchID, fileID, companyName, stdEntryCode, totalBatchCount, totalCreditAmt, totalDebitAmt FROM ach_receiving.ACHRecvBatch;
SELECT tranID, transferSerNo, fileID, recvName, amount, creditFlag, traceNo, fedStatus, processStatus FROM ach_receiving.ACHRecvTransaction ORDER BY traceNo LIMIT 10;

-- your blueprint: what processing did to each entry, the returns waiting to go back, and the compliance decisions
SELECT tranID, recvAcctID, correctedAcctNo, coreTranCode, processStatus, processReason, returnCode FROM ach_receiving.ACHRecvTransaction ORDER BY tranID DESC LIMIT 20;
SELECT tranID, returnCode, amount, acctName, returnStatus, returnRtn FROM ach_receiving.ACHRecvReturn ORDER BY 1 DESC LIMIT 10;
SELECT * FROM ach_origination.ACHOrigOfacReview ORDER BY 1 DESC LIMIT 10;

-- Parameter UI changes and security events
SELECT * FROM ach_payments.AppParam;
SELECT * FROM ach_payments.AppLog ORDER BY 1 DESC LIMIT 10;
```

What's in the database today:
- **Your 31 tables:**
  - your names, types and keys, in the three databases from your diagram
  - plus the foreign keys and a few columns your screens needed
  - plus all 13 of your sample rows
  - an automated test compares the schema to your original DDL
- **Changes from your samples, each marked FIX:**
  - routing number `051000031` → `051000033`
  - parent rows your sample rows pointed to
  - one CHECK constraint, to work around a MySQL foreign-key bug
  - All of these are listed in `docs/DB-MODEL.md` section 0.

## What isn't built yet

The transfer now reaches the (simulated) Fed. The rest of the back office from your System Design isn't built yet:
- Same Day windows (the processing windows from your blueprint are built; the file still goes once, at 5 PM)
- sending the returns to the Fed (they're recorded, waiting as `PENDING`)
- the engines and actions behind NOCs, reversals and resubmissions (their screens are read-only)
- a screen for the receiving manual-review queue
- fraud rules
- the core banking system reading the posting file (the simulated core is updated directly)

These are the remaining roadmap cards in `docs/PLAN.md`.

**Questions for you:** anything in Parts 1–2 that doesn't match what you meant, and the open decisions in `docs/USE-CASES.md`.
