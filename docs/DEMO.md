# Demo guide for Ba

A 10-minute walk-through of what's built from your three documents, and what isn't yet.

## Two ways to run it

| | Online demo | Full app (Ben's Mac) |
|---|---|---|
| Where | https://e16163.github.io/ACH-Payments/ | http://localhost:8080/ach/ |
| What runs | The real screens; the server's rules run in the browser on made-up data | Java server (Spring Boot on Tomcat) + MySQL, with your tables |
| Saved? | No. Resets when you close the tab | Yes, in the `ach_payments` / `ach_origination` / `ach_receiving` databases |
| Member login | `member` / `member123` | `member` / `member123` |
| Admin login | `admin` / `admin123` | Ben's admin login |

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

**Navigate:** **Transfer & Pay ▾ → External ACH Transfer (Other Bank)**.

**Step 2 (source account):** **Primary Checking ••••1920** is picked, with "Available balance: $8,500.00".

**Step 3 (recipient):**
1. **Recipient name:** `Jane Smith`.
2. **EX-01 (bad routing):** type routing number `051000031`, the one from your sample data. You get "Invalid routing number. Please check the 9-digit ABA number." and **Continue** stays disabled. The check digit (weights 3-7-1) is wrong: we changed your sample to `051000033`, which passes (see DB-MODEL.md section 0).
3. Change it to `021000021`. You see "✓ JPMorgan Chase Bank, N.A.", looked up in the ABADirectory table.
4. **Account number:** `123456789`. **Account type:** Checking.

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
   - **Estimated arrival** date
2. Under **Fee disclosure and transfer terms**, **Confirm Transfer** stays disabled until you tick the box. Tick it and click **Confirm Transfer**.

**Step 7 (confirmation):**
1. **Transfer scheduled** shows:
   - the reference number, `TRF-yyyyMMdd-0001` in your serial format
   - status **Scheduled**
   - the amount and recipient
   - the date and estimated arrival
2. **Download receipt** saves a text receipt.
3. Click **Done**, then **Accounts**. Checking now shows **$6,997.50**: $1,502.50 is on memo hold.

**Extra, OFAC screening:** start another transfer with recipient name `Blocked Test Person`. That's a made-up name on the simulated SDN list. The review page says "This transfer will be reviewed by our compliance team before it's sent." The result is **Transfer under review**, and no money is held.

---

## Part 3: System Design (in the full app)

These screens write to your tables. On Ben's Mac, after a transfer, run:

```sql
-- the transfer, PENDING, with T8 columns (originatorID 9000000001, sourceID ONLINE_BANKING)
SELECT transferSerNo, memberNo, transferAmt, feeAmt, effectiveDt, processStatus, ofacStatus, memoStatus, originatorID, lastModBy
FROM ach_origination.ACHOriginatorTransfer ORDER BY createTm DESC LIMIT 5;

-- the saved recipient (REC-yyyyMMdd-nnnn)
SELECT recvAcctSerNo, acctOwner, routingNo, acctType FROM ach_origination.ACHOrigRecvAccount ORDER BY recvAcctSerNo DESC LIMIT 5;

-- the memo hold and the balance it reduced
SELECT tranRef, acctNo, amount, postType, memoStatus FROM ach_payments.ACHMemoPost ORDER BY memoPostID DESC LIMIT 5;
SELECT acctNo, ledgerBal, availBal FROM ach_payments.SimCoreAccount;

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

The transfer stops at **PENDING**. The back office from your System Design isn't built yet:
- the outbound NACHA file builder and Same Day windows
- a simulated Fed
- inbound file ingestion and the Receiving UI
- returns, NOCs, reversals and resubmissions
- fraud rules and the review queue
- end-of-day posting

These are roadmap cards R3–R17, in `docs/PLAN.md`. The next one turns pending transfers into a real NACHA file, so you can open it and see the transfer as an entry line.

**Questions for you:** anything in Parts 1–2 that doesn't match what you meant, and the open decisions in `docs/USE-CASES.md`.
