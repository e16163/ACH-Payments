// In-browser stand-in for the Java API, used only by the GitHub Pages demo build (VITE_DEMO=1).
// It follows the server's rules and messages (TransferService, ParamValidator, MfaService, SecurityConfig)
// on made-up data, and keeps its state in sessionStorage, so closing the tab resets it.
import { ApiError } from '../api'
import { validateDesc, validateName, validateValue, PARAM_TYPES } from '../params/validation'
import { isValidRouting } from '../member/rules'

type Role = 'MEMBER' | 'SYS_ADMIN'
const USERS: Record<string, { password: string; role: Role }> = {
  member: { password: 'member123', role: 'MEMBER' },
  admin: { password: 'admin123', role: 'SYS_ADMIN' },
}

const APPS: [string, string][] = [
  ['ACH_ORIG', 'ACH Origination UI'],
  ['ACH_ORIG_RETURN', 'ACH Origination Return UI'],
  ['ACH_ORIG_REVERSAL', 'ACH Origination Reversal UI'],
  ['ACH_ORIG_RESUB', 'ACH Origination Resubmission UI'],
  ['ACH_ORIG_NOC', 'ACH Origination NOC UI'],
  ['ACH_RCV', 'ACH Receiving UI'],
]

// The ABADirectory, OfacSdnName, BankingCalendar and ACHOrigLimitRule seed rows (V7, V8, V10, V3).
const BANKS: Record<string, string> = {
  '011000015': 'Federal Reserve Bank of Boston',
  '021000021': 'JPMorgan Chase Bank, N.A.',
  '026009593': 'Bank of America, N.A.',
  '051000033': "Sample Bank (from Ba's design samples)",
  '121000248': 'Wells Fargo Bank, N.A.',
  '123456780': 'Demo Credit Union',
}
const OFAC = ['BLOCKED DEMO ENTITY', 'BLOCKED TEST PERSON', 'OFAC TEST BLOCKED PERSON', 'SANCTIONED TEST TRADING LLC']
const HOLIDAYS = [
  '2026-01-01', '2026-01-19', '2026-02-16', '2026-05-25', '2026-06-19', '2026-09-07', '2026-10-12', '2026-11-11',
  '2026-11-26', '2026-12-25', '2027-01-01', '2027-01-18', '2027-02-15', '2027-05-31', '2027-07-05', '2027-09-06',
  '2027-10-11', '2027-11-11', '2027-11-25',
]
const LIMIT_RULES = [
  // ORDER BY dayNo, limitAmt, as in TransferService.checkLimits
  { acctType: 'CHECKING', dayNo: 1, limit: 2_500_000 },
  { acctType: 'SAVINGS', dayNo: 1, limit: 1_000_000 },
  { acctType: 'ALL', dayNo: 7, limit: 5_000_000 },
  { acctType: 'CHECKING', dayNo: 30, limit: 50_000_000 },
]
const MAX_CENTS = 9_999_999_999_999 // Ba's DECIMAL(13,2): 99,999,999,999.99
const FREQUENCIES = ['ONETIME', 'WEEKLY', 'BIWEEKLY', 'MONTHLY']

type Account = { acctNo: string; acctName: string; acctType: string; cents: number }
type Param = { systemName: string; paramName: string; paramDesc: string; paramRawType: string; paramRawValue: string; lastModTm: string; lastModBy: string }
type Transfer = {
  transferSerNo: string; ofacStatus: string; memoStatus: string; recvName: string; bankName: string; routingNo: string
  acctNo: string; acctType: string; fromAcctNo: string; fromAcctName: string; fromAcctType: string; amount: number
  fee: number; effectiveDt: string; frequency: string; transferCount: number; memo: string | null; submitted: string
}
type State = {
  user: string | null
  mfaVerified: boolean
  mfa: { code: string; expires: number; tries: number } | null
  accounts: Account[]
  params: Param[]
  transfers: Transfer[]
}

const KEY = 'ach-demo-state'

function freshState(): State {
  return {
    user: null,
    mfaVerified: false,
    mfa: null,
    accounts: [
      { acctNo: '100049281920', acctName: 'Primary Checking', acctType: 'CHECKING', cents: 850_000 },
      { acctNo: '100049281946', acctName: 'Savings', acctType: 'SAVINGS', cents: 1_500_000 },
    ],
    params: [
      { systemName: 'ACH_RCV', paramName: 'MEMO_POST_IMMEDIATE', paramDesc: 'Enable immediate memo posting upon transaction ingestion', paramRawType: 'BOOLEAN', paramRawValue: 'TRUE', lastModTm: '2026-11-03T19:14:48', lastModBy: 'SYS_ADMIN' },
      { systemName: 'ACH_ORIG', paramName: 'FED_FILE_CUTOFF_TIME', paramDesc: 'Cutoff time for building daily Fed file (EST)', paramRawType: 'STRING', paramRawValue: '17:00:00', lastModTm: '2026-11-03T19:15:14', lastModBy: 'SYS_ADMIN' },
      { systemName: 'ACH_ORIG', paramName: 'EXTERNAL_XFER_FEE', paramDesc: 'Fee for a member external ACH transfer (UC-ACH-ORG-01)', paramRawType: 'DECIMAL', paramRawValue: '0.00', lastModTm: '2026-11-03T21:21:05', lastModBy: 'SYS_ADMIN' },
    ],
    transfers: [],
  }
}

function load(): State {
  const saved = sessionStorage.getItem(KEY)
  return saved ? (JSON.parse(saved) as State) : freshState()
}

function save(s: State) {
  sessionStorage.setItem(KEY, JSON.stringify(s))
}

// ---- helpers ----

const pad = (n: number, w = 2) => String(n).padStart(w, '0')
const isoDate = (d: Date) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
const isoDateTime = (d: Date) => `${isoDate(d)}T${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`

function isBankingDay(iso: string): boolean {
  const day = new Date(`${iso}T12:00:00`).getDay()
  return day !== 0 && day !== 6 && !HOLIDAYS.includes(iso)
}

function earliest(): string {
  const d = new Date()
  do d.setDate(d.getDate() + 1)
  while (!isBankingDay(isoDate(d)))
  return isoDate(d)
}

/** "$25,000" for whole dollars, "$1,250.50" otherwise (TransferService.money). */
function money(cents: number): string {
  const whole = cents % 100 === 0
  return (cents / 100).toLocaleString('en-US', {
    style: 'currency',
    currency: 'USD',
    minimumFractionDigits: whole ? 0 : 2,
    maximumFractionDigits: whole ? 0 : 2,
  })
}

/** A JSON amount (number or string) as cents, or null if it has more than 2 decimals or isn't a number. */
function toCents(value: unknown): number | null {
  const text = typeof value === 'number' ? String(value) : typeof value === 'string' ? value.trim() : ''
  const m = /^(\d+)(?:\.(\d+))?$/.exec(text)
  if (!m) return null
  const decimals = (m[2] ?? '').replace(/0+$/, '')
  if (decimals.length > 2) return null
  const cents = Number(m[1]) * 100 + Number(decimals.padEnd(2, '0') || 0)
  return Number.isSafeInteger(cents) ? cents : null
}

const normalize = (name: string) => name.toUpperCase().replace(/[^A-Z0-9]+/g, ' ').trim()
const last4 = (acct: string) => acct.slice(-4)

function fail(status: number, error: string, kind?: string): never {
  throw new ApiError(status, error, kind)
}
const invalid = (msg: string): never => fail(400, msg, 'INVALID')

// ---- sign-in (SecurityConfig) ----

export async function demoLogin(username: string, password: string): Promise<void> {
  const user = USERS[username]
  if (!user || user.password !== password) fail(401, 'Incorrect username or password.')
  const s = load()
  save({ ...s, user: username, mfaVerified: false, mfa: null })
}

export async function demoLogout(): Promise<void> {
  const s = load()
  save({ ...s, user: null, mfaVerified: false, mfa: null })
}

// ---- the API ----

type Body = Record<string, unknown>

export async function demoApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = init.method ?? 'GET'
  const body: Body = init.body ? JSON.parse(String(init.body)) : {}
  const s = load()
  const result = route(s, method, path, body)
  save(s)
  return result as T
}

function route(s: State, method: string, path: string, body: Body): unknown {
  if (!s.user) fail(401, 'Please sign in.')
  const role = USERS[s.user].role
  const denied = () => fail(403, "You don't have permission to do this.")

  if (path === '/me') return { username: s.user, roles: [role], mfaVerified: s.mfaVerified }

  if (path.startsWith('/params')) {
    if (role !== 'SYS_ADMIN') denied()
    return params(s, method, path, body)
  }
  if (role !== 'MEMBER') denied()
  if (path === '/member/mfa/send' && method === 'POST') {
    const code = pad(Math.floor(Math.random() * 1_000_000), 6)
    s.mfa = { code, expires: Date.now() + 5 * 60_000, tries: 0 }
    return { demoCode: code }
  }
  if (path === '/member/mfa/verify' && method === 'POST') return verifyMfa(s, String(body.code ?? ''))
  if (!s.mfaVerified) denied() // UC-ACH-ORG-01 precondition: MFA passed

  if (path === '/transfers/dates') return { earliest: earliest(), holidays: HOLIDAYS }
  if (path === '/member/accounts') return accounts(s)
  const routing = /^\/routing\/(.*)$/.exec(path)
  if (routing) {
    const routingNo = decodeURIComponent(routing[1])
    const name = bankName(routingNo)
    return name ? { routingNo, bankName: name } : fail(400, 'Invalid routing number. Please check the 9-digit ABA number.')
  }
  if (path === '/transfers/preview' && method === 'POST') {
    const c = check(s, body)
    return { bankName: c.bankName, amount: c.amount / 100, fee: c.fee / 100, total: (c.amount + c.fee) / 100, effectiveDt: c.effectiveDt, estimatedArrival: c.effectiveDt, underReview: c.ofacHit }
  }
  if (path === '/transfers' && method === 'POST') return submit(s, body)
  const receiptPath = /^\/transfers\/(.+)$/.exec(path)
  if (receiptPath && method === 'GET') {
    const t = s.transfers.find((x) => x.transferSerNo === decodeURIComponent(receiptPath[1]))
    return t ? receipt(t) : fail(404, 'Not found')
  }
  fail(404, 'Not found')
}

function verifyMfa(s: State, code: string) {
  const fails = "That code didn't work. Check it and try again, or send a new code."
  if (!s.mfa) fail(400, fails)
  s.mfa.tries++
  const expired = Date.now() > s.mfa.expires
  if (expired || s.mfa.tries > 5 || s.mfa.code !== code) {
    if (expired || s.mfa.tries >= 5) s.mfa = null
    fail(400, fails)
  }
  s.mfa = null
  s.mfaVerified = true
  return { mfaVerified: true }
}

// ---- Parameter UI (ParamController, ParamValidator) ----

function params(s: State, method: string, path: string, body: Body) {
  if (path === '/params/systems' && method === 'GET') return APPS.map(([systemName, appName]) => ({ systemName, appName }))
  const list = /^\/params\?systemName=(.*)$/.exec(path)
  if (list && method === 'GET') {
    const system = decodeURIComponent(list[1])
    return s.params
      .filter((p) => p.systemName === system)
      .sort((a, b) => a.paramName.localeCompare(b.paramName, 'en', { sensitivity: 'base' })) // MySQL's ORDER BY ignores case
  }
  const one = /^\/params\/([^/]+)\/([^/]+)$/.exec(path)
  if (one && method === 'PUT') {
    const systemName = decodeURIComponent(one[1])
    const paramName = decodeURIComponent(one[2])
    const desc = typeof body.paramDesc === 'string' ? body.paramDesc : ''
    const type = String(body.paramRawType ?? '')
    const value = typeof body.paramRawValue === 'string' ? body.paramRawValue : ''
    const error = !APPS.some(([name]) => name === systemName)
      ? `Unknown app: ${systemName}.`
      : validateName(paramName) ??
        validateDesc(desc) ??
        (!(PARAM_TYPES as readonly string[]).includes(type) ? `Type must be one of ${[...PARAM_TYPES].sort().join(', ')}.` : null) ??
        validateValue(type, value)
    if (error) fail(400, error)
    const row: Param = { systemName, paramName, paramDesc: desc, paramRawType: type, paramRawValue: value, lastModTm: isoDateTime(new Date()), lastModBy: s.user! }
    s.params = s.params.filter((p) => !(p.systemName === systemName && p.paramName === paramName)).concat(row)
    return row
  }
  fail(404, 'Not found')
}

// ---- External ACH Transfer (TransferService) ----

function accounts(s: State) {
  return [...s.accounts]
    .sort((a, b) => a.acctType.localeCompare(b.acctType) || a.acctNo.localeCompare(b.acctNo))
    .map((a) => ({ acctNo: a.acctNo, acctName: a.acctName, acctType: a.acctType, availBal: a.cents / 100 }))
}

function bankName(routing: string): string | null {
  return isValidRouting(routing) ? (BANKS[routing] ?? null) : null
}

type Checked = {
  from: Account; recvName: string; routingNo: string; acctNo: string; acctType: string; amount: number; fee: number
  effectiveDt: string; frequency: string; count: number; memo: string | null; bankName: string; ofacHit: boolean
}

function check(s: State, r: Body): Checked {
  const from = s.accounts.find((a) => a.acctNo === r.origAcctNo) ?? invalid('Choose one of your accounts to transfer from.')
  const recvName = typeof r.recvName === 'string' ? r.recvName.trim().replace(/\s+/g, ' ') : ''
  if (!/^[A-Za-z0-9 .,'&-]{1,22}$/.test(recvName)) invalid("Enter the recipient's name: up to 22 letters, numbers and spaces.")
  const routingNo = typeof r.routingNo === 'string' ? r.routingNo : ''
  const bank = bankName(routingNo) ?? invalid('Invalid routing number. Please check the 9-digit ABA number.')
  const acctNo = typeof r.acctNo === 'string' ? r.acctNo : ''
  if (!/^\d{4,17}$/.test(acctNo)) invalid("Enter the recipient's account number: 4 to 17 digits.")
  if (r.acctType !== 'CHECKING' && r.acctType !== 'SAVINGS') invalid("Choose Checking or Savings for the recipient's account.")
  const amount = toCents(r.amount)
  if (amount === null || amount <= 0 || amount > MAX_CENTS) invalid('Enter an amount between $0.01 and $99,999,999,999.99.')
  const effectiveDt = typeof r.effectiveDt === 'string' ? r.effectiveDt : ''
  if (!effectiveDt) invalid('Choose a transfer date.')
  if (effectiveDt < earliest()) invalid(`The earliest transfer date is ${earliest()}.`)
  if (!isBankingDay(effectiveDt)) invalid("Choose a banking day. Weekends and Federal Reserve holidays aren't available.")
  const frequency = String(r.frequency ?? '')
  if (!FREQUENCIES.includes(frequency)) invalid('Choose how often to send this transfer.')
  const requested = r.transferCount == null ? null : Number(r.transferCount)
  let count = 1
  if (frequency === 'ONETIME') {
    if (requested !== null && requested !== 1) invalid('A one-time transfer is sent once.')
  } else {
    if (requested === null || !Number.isInteger(requested) || requested < 2 || requested > 52) invalid('Choose how many transfers to send: 2 to 52.')
    count = requested!
  }
  const memo = typeof r.memo === 'string' ? r.memo.trim().toUpperCase() : ''
  if (!/^[A-Z0-9 .,&-]{0,10}$/.test(memo)) invalid('The memo can be up to 10 letters, numbers and spaces.')
  const feeParam = s.params.find((p) => p.systemName === 'ACH_ORIG' && p.paramName === 'EXTERNAL_XFER_FEE')
  const fee = toCents(feeParam?.paramRawValue ?? '0') ?? 0

  // Account verification (Plaid placeholder) always passes, as in PlaidAccountVerifier.
  checkLimits(s, from.acctType, amount!)
  if (from.cents < amount! + fee) fail(400, `Your available balance of ${money(from.cents)} isn't enough for this transfer.`, 'FUNDS')
  return {
    from, recvName: recvName.toUpperCase(), routingNo, acctNo, acctType: String(r.acctType), amount: amount!, fee,
    effectiveDt, frequency, count, memo: memo || null, bankName: bank, ofacHit: OFAC.some((n) => normalize(n) === normalize(recvName)),
  }
}

function checkLimits(s: State, acctType: string, amount: number) {
  for (const rule of LIMIT_RULES.filter((x) => x.acctType === acctType || x.acctType === 'ALL')) {
    const since = new Date()
    since.setDate(since.getDate() - (rule.dayNo - 1))
    const from = isoDate(since)
    const used = s.transfers
      .filter((t) => t.submitted.slice(0, 10) >= from && (rule.acctType === 'ALL' || t.fromAcctType === acctType))
      .reduce((sum, t) => sum + t.amount, 0)
    if (used + amount > rule.limit) {
      const period = rule.dayNo === 1 ? 'daily' : rule.dayNo === 7 ? 'weekly' : rule.dayNo === 30 ? 'monthly' : `${rule.dayNo}-day`
      fail(400, `This transfer exceeds your ${period} external limit of ${money(rule.limit)}. Please reduce the amount or contact support.`, 'LIMIT')
    }
  }
}

function submit(s: State, body: Body) {
  const c = check(s, body)
  const now = new Date()
  const prefix = `TRF-${isoDate(now).replace(/-/g, '')}-`
  const today = s.transfers.filter((t) => t.transferSerNo.startsWith(prefix)).length
  // Memo hold (MemoPostingEngine): an OFAC hit waits for review; otherwise the total comes off the available balance.
  const memoStatus = c.ofacHit ? 'REVIEW' : 'POSTED'
  if (!c.ofacHit) c.from.cents -= c.amount + c.fee
  const t: Transfer = {
    transferSerNo: prefix + pad(today + 1, 4), ofacStatus: c.ofacHit ? 'FLAGGED' : 'CLEARED', memoStatus,
    recvName: c.recvName, bankName: c.bankName, routingNo: c.routingNo, acctNo: c.acctNo, acctType: c.acctType,
    fromAcctNo: c.from.acctNo, fromAcctName: c.from.acctName, fromAcctType: c.from.acctType, amount: c.amount, fee: c.fee,
    effectiveDt: c.effectiveDt, frequency: c.frequency, transferCount: c.count, memo: c.memo, submitted: isoDateTime(now),
  }
  s.transfers.push(t)
  return receipt(t)
}

function receipt(t: Transfer) {
  return {
    transferSerNo: t.transferSerNo, processStatus: 'PENDING', ofacStatus: t.ofacStatus, memoStatus: t.memoStatus,
    recvName: t.recvName, bankName: t.bankName, routingNo: t.routingNo, acctLast4: last4(t.acctNo), acctType: t.acctType,
    fromAcctName: t.fromAcctName, fromAcctLast4: last4(t.fromAcctNo), amount: t.amount / 100, fee: t.fee / 100,
    total: (t.amount + t.fee) / 100, effectiveDt: t.effectiveDt, estimatedArrival: t.effectiveDt, frequency: t.frequency,
    transferCount: t.transferCount, memo: t.memo, submitted: t.submitted,
  }
}
