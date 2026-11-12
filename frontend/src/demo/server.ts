// In-browser stand-in for the Java API, used only by the GitHub Pages demo build (VITE_DEMO=1).
// It follows the server's rules and messages (TransferService, ParamValidator, MfaService, SecurityConfig, EodFileStore, EodFileJob,
// OriginationWindow, ReceivedEntryProcessor)
// on made-up data, and keeps its state in sessionStorage, so closing the tab resets it.
import { ApiError } from '../api'
import BACK_OFFICE from './backOffice.json'
import { buildNachaFile, type NachaBatch, type NachaEntry } from './nacha'
import { ingest, processEntries, sampleCore, RECEIVED, type CoreAccount, type Processing, type RecvEntry } from './receive'
import payrollSample from '../../../src/main/resources/fed-samples/sample1-payroll.ach?raw'
import retailSample from '../../../src/main/resources/fed-samples/sample2-retail.ach?raw'
import outOfBalanceSample from '../../../src/main/resources/fed-samples/sample3-out-of-balance.ach?raw'
import { validateDesc, validateName, validateValue, PARAM_TYPES } from '../params/validation'
import { isValidRouting } from '../member/rules'

type Role = 'MEMBER' | 'SYS_ADMIN' | 'ACH_ORIG_OPS' | 'ACH_RECV_OPS' | 'COMPLIANCE'
const USERS: Record<string, { password: string; role: Role }> = {
  member: { password: 'member123', role: 'MEMBER' },
  admin: { password: 'admin123', role: 'SYS_ADMIN' },
  achorig: { password: 'achorig123', role: 'ACH_ORIG_OPS' },
  achrecv: { password: 'achrecv123', role: 'ACH_RECV_OPS' },
  achcomp: { password: 'achcomp123', role: 'COMPLIANCE' },
}

const APPS: [string, string][] = [
  ['ACH_ORIG', 'ACH Origination UI'],
  ['ACH_ORIG_RETURN', 'ACH Origination Return UI'],
  ['ACH_ORIG_REVERSAL', 'ACH Origination Reversal UI'],
  ['ACH_ORIG_RESUB', 'ACH Origination Resubmission UI'],
  ['ACH_ORIG_NOC', 'ACH Origination NOC UI'],
  ['ACH_RCV', 'ACH Receiving UI'],
  ['ACH_RCV_RETURN', 'ACH Receiving Return UI'],
  ['ACH_RCV_REVERSAL', 'ACH Receiving Reversal UI'],
  ['ACH_RCV_NOC', 'ACH Receiving NOC UI'],
]

// The ABADirectory, OfacSdnName, BankingCalendar and ACHOrigLimitRule seed rows (V7, V8, V10, V3).
const BANKS: Record<string, string> = {
  '011000015': 'Federal Reserve Bank of Boston',
  '021000021': 'JPMorgan Chase Bank, N.A.',
  '026009593': 'Bank of America, N.A.',
  '051000033': "Sample Bank (from the design samples)",
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
const MAX_CENTS = 9_999_999_999_999 // The design's DECIMAL(13,2): 99,999,999,999.99
const FREQUENCIES = ['ONETIME', 'WEEKLY', 'BIWEEKLY', 'MONTHLY']
// Nacha gives these entry descriptions a meaning, so a member's memo can't be sent as one (EodFileStore).
const RESERVED_ENTRY_DESC = ['REVERSAL', 'RECLAIM', 'NONSETTLED', 'RETRY PYMT', 'REDEPCHECK', 'HCCLAIMPMT', 'AUTOENROLL']

type Account = { acctNo: string; acctName: string; acctType: string; cents: number }
type Param = { systemName: string; paramName: string; paramDesc: string; paramRawType: string; paramRawValue: string; lastModTm: string; lastModBy: string }
type Transfer = {
  transferSerNo: string; ofacStatus: string; memoStatus: string; recvName: string; bankName: string | null; routingNo: string
  acctNo: string; acctType: string; fromAcctNo: string; fromAcctName: string; fromAcctType: string; amount: number
  fee: number; effectiveDt: string; frequency: string; memo: string | null; submitted: string
  processStatus: string; firstSerNo: string | null; reqNo: number; remaining: number; ofacTm: string
  scheduleId: number | null; readyTm: string | null // the window that made it READY_TO_POST (V19)
}
type BatchRow = {
  batch_id: string; batch_no: number; company_name: string; std_entry_code: string; company_entry_desc: string
  effective_dt: string; entry_count: number; total_credit_amt: number
}
type AchFileRow = {
  fileID: string; fileName: string; fedStatus: string; fileHeaderDt: string; fileHeaderTm: string; totalCreditCount: number
  totalCreditAmt: number; sendTm: string | null; content: string; scheduleId: number | null; batches: BatchRow[]
}
// ACHOrigWindowRun and ACHRecvWindowRun (V19), with the screens' field names
type OrigRun = {
  window_id: string; window_time: string; business_date: string; extracted: number; account_rejected: number; limit_rejected: number
  ofac_flagged: number; hold_rejected: number; ready_to_post: number; run_by: string; started_at: string
}
type RecvRun = {
  execution_id: string; window_time: string; business_date: string; files: number; validated: number; entries: number; matched: number
  auto_corrected: number; manual_review: number; rejected: number; memo_posted: number; run_by: string; started_at: string
}
type PostingJob = {
  posting_job_id: string; business_date: string; total_records_posted: number; total_debit_volume: number; total_credit_volume: number
  posting_file_name: string; status: string
}
type RecvFile = {
  fileID: string; fileName: string; fileHeaderDt: string; fileHeaderTm: string; fedStatus: string; processStatus: string; modifier: string
}
type RecvRow = RecvEntry & Processing & { tranID: number; transferSerNo: string; transferVersion: number; fileID: string; batchID: string; fedStatus: string; sourceID: string; createTm: string }
type ExternalAccount = { recvAcctSerNo: string; routingNo: string; acctNo: string; acctType: string; acctOwner: string; acctStatus: string }
type LogRow = { logAppId: number; systemID: string | null; logLevel: string; logMesg: string; logExceptionData: string | null; logTm: string }
type State = {
  user: string | null
  logs: LogRow[]
  mfaVerified: boolean
  mfa: { code: string; expires: number; tries: number } | null
  accounts: Account[]
  params: Param[]
  transfers: Transfer[]
  files: AchFileRow[]
  origRuns: OrigRun[]
  nextTrace: number
  receivedFiles: RecvFile[]
  receivedEntries: RecvRow[]
  core: Record<string, CoreAccount> // the receivers' accounts (SimCoreAccount)
  externalAccounts: ExternalAccount[] // newest last
  inbound: string[] // the sample files waiting in fed/inbound for a receiving window
  recvRuns: RecvRun[]
  postingJobs: PostingJob[] // ACHRecvPostingJob
}

const KEY = 'ach-demo-state-7' // new name: state saved by an older demo has no schedules or window runs
const MEMBER_NO = 'M10982347'
// The ACHOrigSchedule and ACHRecvSchedule rows (V19)
const slot = (schedule_id: number, window_time: string, window_name: string) => ({ schedule_id, window_time, window_name })
const ORIG_SCHEDULE = [slot(1, '06:00', '6:00 AM window'), slot(2, '09:00', '9:00 AM window'), slot(3, '12:00', '12:00 PM window'), slot(4, '15:00', '3:00 PM window')]
const RECV_SCHEDULE = [...ORIG_SCHEDULE, slot(5, '18:00', '6:00 PM window')]
const PENDING_MICRO_DEPOSIT = 'ACTIVE_PENDING_MICRO_DEPOSIT' // TransferService: stored as PENDING_MICRO_DEP

function freshState(): State {
  const s: State = {
    user: null,
    // The design's sample AppLog row (V7)
    logs: [{
      logAppId: 1, systemID: 'ACH_RCV', logLevel: 'ERROR', logMesg: 'Failed to parse incoming ACH file HEADER record for file ID FILE_20261103_09',
      logExceptionData: 'java.lang.IllegalArgumentException: Invalid routing number length at line 1 char 23\n\tat com.ach.engine.FileParser.parseHeader(FileParser.java:42)',
      logTm: '2026-11-03T15:10:00',
    }],
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
      { systemName: 'ACH_ORIG', paramName: 'FED_IMMEDIATE_DEST', paramDesc: 'Routing number of the Federal Reserve office that receives our files', paramRawType: 'STRING', paramRawValue: '011000015', lastModTm: '2026-11-05T12:00:00', lastModBy: 'SYS_ADMIN' },
      { systemName: 'ACH_ORIG', paramName: 'ORIG_COMPANY_NAME', paramDesc: 'Company name in NACHA batch headers (16 characters at most)', paramRawType: 'STRING', paramRawValue: 'DEMO CU', lastModTm: '2026-11-05T12:00:00', lastModBy: 'SYS_ADMIN' },
      { systemName: 'ACH_RCV', paramName: 'CORE_POSTING_TIME', paramDesc: 'Time the end-of-day core posting file is generated (Eastern)', paramRawType: 'STRING', paramRawValue: '19:00', lastModTm: '2026-11-08T12:00:00', lastModBy: 'SYS_ADMIN' },
      { systemName: 'ACH_RCV', paramName: 'TRUSTED_COMPANY_IDS', paramDesc: 'Originator Company IDs whose credits are memo posted at once (comma-separated)', paramRawType: 'STRING', paramRawValue: '1900483920', lastModTm: '2026-11-08T12:00:00', lastModBy: 'SYS_ADMIN' },
      { systemName: 'ACH_RCV', paramName: 'MAX_ENTRY_AMOUNT', paramDesc: 'A received entry above this amount waits for manual review', paramRawType: 'DECIMAL', paramRawValue: '25000.00', lastModTm: '2026-11-08T12:00:00', lastModBy: 'SYS_ADMIN' },
      { systemName: 'ACH_ORIG', paramName: 'FED_SIM_FAIL_ATTEMPTS', paramDesc: 'Fed simulator: how many send attempts fail, to demo EX-02 (0 = none)', paramRawType: 'INTEGER', paramRawValue: '0', lastModTm: '2026-11-05T12:00:00', lastModBy: 'SYS_ADMIN' },
    ],
    transfers: [],
    files: [],
    origRuns: [],
    nextTrace: 1,
    // The sample ACHRecvFile and ACHRecvTransaction rows (V7)
    receivedFiles: [{ fileID: 'ACHFILE-88392', fileName: 'ACHFILE-88392.ach', fileHeaderDt: '2026-11-01', fileHeaderTm: '16:00:00', fedStatus: 'PROCESSED', processStatus: 'PROCESSED', modifier: 'A' }],
    receivedEntries: [{
      tranID: 1, transferSerNo: 'TRF-20261101-0089', transferVersion: 1, fileID: 'ACHFILE-88392', batchID: 'BATCH-001', creditFlag: 1, tranCode: '22',
      companyName: 'ACME CORP', companyID: '1234567890', companyDiscData: 'DIRECT DEP', stdEntryCode: 'PPD', companyEntryDesc: 'PAYROLL', descriptiveDt: '2026-11-01',
      effectiveDt: '2026-11-03', settlementDt: '2026-11-03', originatorID: '9000123456', amount: 3450.5, recvAcctID: '987654321012', recvName: 'JOHN DOE',
      transactionDiscData: 'S', traceNo: '051000031234567', fedStatus: 'SETTLED', sourceID: 'PAYROLL_SYS', createTm: '2026-11-01T16:00:00', batchNo: 1, ...RECEIVED,
    }, {
      // The sample reversal (V14)
      tranID: 2, transferSerNo: '', transferVersion: 1, fileID: 'ACHFILE-88392', batchID: 'BATCH-002', creditFlag: 0, tranCode: '27',
      companyName: 'ACME CORP', companyID: '1234567890', companyDiscData: 'DIRECT DEP', stdEntryCode: 'PPD', companyEntryDesc: 'REVERSAL', descriptiveDt: '2026-11-04',
      effectiveDt: '2026-11-05', settlementDt: '2026-11-05', originatorID: '9000123456', amount: 3450.5, recvAcctID: '987654321012', recvName: 'JOHN DOE',
      transactionDiscData: 'S', traceNo: '051000031234568', fedStatus: 'SETTLED', sourceID: 'PAYROLL_SYS', createTm: '2026-11-01T16:00:00', batchNo: 2, ...RECEIVED,
    }],
    core: sampleCore(),
    // The sample ACHOrigRecvAccount row (V7)
    externalAccounts: [{ recvAcctSerNo: 'REC-SRV-20261102-01', routingNo: '051000033', acctNo: '987654321012', acctType: 'CHECKING', acctOwner: 'JOHN DOE', acctStatus: 'ACTIVE' }],
    inbound: [],
    recvRuns: [],
    postingJobs: [],
  }
  seedToday(s)
  return s
}

/**
 * Sample transfers submitted yesterday from Savings for today, so the ACH Origination Processing screen has work on a
 * banking day. The money was held at Confirm, as for any transfer.
 */
function seedToday(s: State) {
  const today = isoDate(new Date())
  if (!isBankingDay(today)) return
  const savings = s.accounts.find((a) => a.acctType === 'SAVINGS')!
  const yesterday = isoDateTime(new Date(Date.now() - 86_400_000))
  const seeds: [string, number, string][] = [['JOHN DOE', 125_000, 'RENT'], ['JOHN DOE', 4_550, 'UTILITIES'], ['JOHN DOE', 30_000, 'TUITION']]
  for (const [recvName, amount, memo] of seeds) {
    savings.cents -= amount
    s.transfers.push({
      transferSerNo: `TRF-SEED-${pad(s.transfers.length + 1, 4)}`, ofacStatus: 'OFAC_PASSED', memoStatus: 'POSTED', recvName, bankName: 'Sample Bank (from the design samples)',
      routingNo: '051000033', acctNo: '987654321012', acctType: 'CHECKING', fromAcctNo: savings.acctNo, fromAcctName: savings.acctName,
      fromAcctType: savings.acctType, amount, fee: 0, effectiveDt: today, frequency: 'ONETIME', memo, submitted: yesterday,
      processStatus: 'PENDING_BATCH', firstSerNo: null, reqNo: 1, remaining: 0, ofacTm: yesterday, scheduleId: null, readyTm: null,
    })
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

/** AppLogRepository.log, without the database fallback. */
function log(s: State, systemID: string, logLevel: string, logMesg: string) {
  const now = new Date()
  s.logs.push({
    logAppId: s.logs.length + 1, systemID, logLevel, logMesg: logMesg.slice(0, 1000), logExceptionData: null,
    logTm: `${isoDateTime(now)}.${pad(now.getMilliseconds(), 3)}`,
  })
}

function isBankingDay(iso: string): boolean {
  const day = new Date(`${iso}T12:00:00`).getDay()
  return day !== 0 && day !== 6 && !HOLIDAYS.includes(iso)
}

function nextBankingDay(iso: string): string {
  const d = new Date(`${iso}T12:00:00`)
  do d.setDate(d.getDate() + 1)
  while (!isBankingDay(isoDate(d)))
  return isoDate(d)
}

const earliest = () => nextBankingDay(isoDate(new Date()))

/** TransferDates.occurrence: payment `index` of a series, counted from the first date, so Jan 31 → Feb 28 → Mar 31. */
function occurrence(first: string, frequency: string, index: number): string {
  const d = new Date(`${first}T12:00:00`)
  if (frequency === 'MONTHLY') {
    const day = d.getDate()
    d.setDate(1)
    d.setMonth(d.getMonth() + index)
    d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()))
  } else {
    d.setDate(d.getDate() + index * (frequency === 'WEEKLY' ? 7 : 14))
  }
  const date = isoDate(d)
  return isBankingDay(date) ? date : nextBankingDay(date)
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

const systemOf = (role: Role) => (role === 'ACH_RECV_OPS' ? 'ACH_RCV' : role === 'SYS_ADMIN' ? 'ACH_SYS' : 'ACH_ORIG')

export async function demoLogin(username: string, password: string): Promise<void> {
  const user = USERS[username]
  const s = load()
  if (!user || user.password !== password) {
    log(s, 'ACH_SYS', 'WARN', `Failed sign-in for user '${user ? username : '(unknown)'}'`)
    save(s)
    fail(401, 'Incorrect username or password.')
  }
  log(s, systemOf(user.role), 'INFO', `User '${username}' signed in`)
  save({ ...s, user: username, mfaVerified: false, mfa: null })
}

export async function demoLogout(): Promise<void> {
  const s = load()
  if (s.user) log(s, systemOf(USERS[s.user].role), 'INFO', `User '${s.user}' signed out`)
  save({ ...s, user: null, mfaVerified: false, mfa: null })
}

// ---- the API ----

type Body = Record<string, unknown>

export async function demoApi<T>(path: string, init: RequestInit = {}): Promise<T> {
  const method = init.method ?? 'GET'
  const body: Body = init.body ? JSON.parse(String(init.body)) : {}
  const s = load()
  try {
    const result = route(s, method, path, body)
    save(s)
    return result as T
  } catch (e) {
    const fresh = load() // a failed call changes nothing, except for its log row
    if (e instanceof ApiError && e.status === 403) log(fresh, 'ACH_SYS', 'WARN', `Access denied: user '${s.user}' attempted ${method} /api${path}`)
    if (e instanceof ApiError && e.kind && path.startsWith('/transfers')) log(fresh, 'ACH_ORIG', 'WARN', `Transfer refused for user '${s.user}' on /api${path}: ${e.kind}`)
    save(fresh)
    throw e
  }
}

/** AppLogController */
function logs(s: State, path: string) {
  const one = /^\/v1\/admin\/logs\/(\d+)$/.exec(path)
  if (one) return s.logs.find((l) => l.logAppId === Number(one[1])) ?? fail(404, 'No such log entry.')
  const q = new URLSearchParams(path.split('?')[1])
  const page = Number(q.get('page') ?? 1)
  const limit = Number(q.get('limit') ?? 20)
  const level = q.get('level') ?? 'ALL'
  const systemID = q.get('systemID')
  if (systemID && systemID !== 'ACH_SYS' && !APPS.some(([name]) => name === systemID)) fail(400, 'Unknown app.')
  const search = (q.get('search') ?? '').toLowerCase()
  const from = q.get('from')
  const to = q.get('to')
  if (from && to && from > to) fail(400, "The From date can't be after the To date.")
  const rows = s.logs
    .filter((l) => (!systemID || l.systemID === systemID) && (level === 'ALL' || l.logLevel === level) && l.logMesg.toLowerCase().includes(search)
      && (!from || l.logTm.slice(0, 10) >= from) && (!to || l.logTm.slice(0, 10) <= to))
    .sort((a, b) => b.logTm.localeCompare(a.logTm) || b.logAppId - a.logAppId)
  return { items: rows.slice((page - 1) * limit, page * limit), total: rows.length, page, limit }
}

function route(s: State, method: string, path: string, body: Body): unknown {
  if (!s.user) fail(401, 'Please sign in.')
  const role = USERS[s.user].role
  const denied = () => fail(403, "You don't have permission to do this.")

  if (path === '/me') return { username: s.user, roles: [role], mfaVerified: s.mfaVerified, memberNo: role === 'MEMBER' ? MEMBER_NO : null }

  if (path.startsWith('/v1/admin/logs')) {
    if (role !== 'SYS_ADMIN') denied()
    return logs(s, path)
  }
  if (path.startsWith('/params')) {
    if (role !== 'SYS_ADMIN') denied()
    return params(s, method, path, body)
  }
  if (path.startsWith('/eod')) {
    if (role !== 'ACH_ORIG_OPS') denied()
    return eod(s, method, path, body)
  }
  if (path.startsWith('/v1/ach/processing/')) {
    if (role !== 'ACH_ORIG_OPS') denied()
    return processing(s, method, path.slice('/v1/ach/processing'.length), body)
  }
  if (path.startsWith('/v1/ach/receiving')) {
    if (role !== 'ACH_RECV_OPS') denied()
    return receiving(s, method, path.slice('/v1/ach/receiving'.length), body)
  }
  if (path.startsWith('/v1/compliance/ofac/')) {
    if (role !== 'COMPLIANCE') denied()
    if (path === '/v1/compliance/ofac/flagged' && method === 'GET') return flagged(s)
    if (path === '/v1/compliance/ofac/resolve' && method === 'PATCH') return resolve(s, body)
    fail(404, 'Not found')
  }
  if (path.startsWith('/v1/ach/back-office/')) {
    const key = path.slice('/v1/ach/back-office/'.length)
    if (role !== (key.startsWith('origination/') ? 'ACH_ORIG_OPS' : 'ACH_RECV_OPS')) denied()
    return BACK_OFFICE[key as keyof typeof BACK_OFFICE]
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
  const holder = /^\/v1\/holders\/([^/]+)\/external-accounts$/.exec(path)
  if (holder) {
    if (decodeURIComponent(holder[1]) !== MEMBER_NO) denied()
    return method === 'POST' ? addExternalAccount(s, body) : externalAccounts(s)
  }
  const routing = /^\/routing\/(.*)$/.exec(path)
  if (routing) {
    const routingNo = decodeURIComponent(routing[1])
    const name = bankName(routingNo)
    return name ? { routingNo, bankName: name } : fail(400, 'Invalid routing number. Please check the 9-digit ABA number.')
  }
  if (path === '/transfers/preview' && method === 'POST') {
    const c = check(s, body)
    return { bankName: c.bankName, amount: c.amount / 100, fee: c.fee / 100, total: (c.amount + c.fee) / 100, effectiveDt: c.effectiveDt, estimatedArrival: nextBankingDay(c.effectiveDt), underReview: c.ofacHit }
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
    log(s, systemName, 'INFO', `User '${s.user}' saved parameter ${systemName}/${paramName} = ${value}`)
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
  from: Account; to: ExternalAccount; recvName: string; routingNo: string; acctNo: string; acctType: string; amount: number; fee: number
  effectiveDt: string; frequency: string; count: number; memo: string | null; bankName: string | null; ofacHit: boolean
}

function check(s: State, r: Body): Checked {
  const from = s.accounts.find((a) => a.acctNo === r.origAcctNo) ?? invalid('Choose one of your accounts to transfer from.')
  // The recipient: one of the member's registered external bank accounts, active or pending micro-deposits
  const to = s.externalAccounts.find((a) => a.recvAcctSerNo === r.externalAccountId && (a.acctStatus === 'ACTIVE' || a.acctStatus === PENDING_MICRO_DEPOSIT))
    ?? invalid('Choose one of your external bank accounts to transfer to.')
  // The check digit is required (the EOD job rejects a file with a bad one); a bank missing from the directory passes for now
  if (!isValidRouting(to.routingNo)) invalid('Invalid routing number. Please check the 9-digit ABA number.')
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
    from, to, recvName: to.acctOwner, routingNo: to.routingNo, acctNo: to.acctNo, acctType: to.acctType, amount: amount!, fee,
    effectiveDt, frequency, count, memo: memo || null, bankName: bankName(to.routingNo), ofacHit: OFAC.some((n) => normalize(n) === normalize(to.acctOwner)),
  }
}

function checkLimits(s: State, acctType: string, amount: number) {
  for (const rule of LIMIT_RULES.filter((x) => x.acctType === acctType || x.acctType === 'ALL')) {
    const since = new Date()
    since.setDate(since.getDate() - (rule.dayNo - 1))
    const from = isoDate(since)
    const used = s.transfers
      .filter((t) => t.processStatus !== 'REJECTED' && t.submitted.slice(0, 10) >= from && (rule.acctType === 'ALL' || t.fromAcctType === acctType))
      .reduce((sum, t) => sum + t.amount, 0)
    if (used + amount > rule.limit) {
      const period = rule.dayNo === 1 ? 'daily' : rule.dayNo === 7 ? 'weekly' : rule.dayNo === 30 ? 'monthly' : `${rule.dayNo}-day`
      fail(400, `This transfer exceeds your ${period} external limit of ${money(rule.limit)}. Please reduce the amount or contact support.`, 'LIMIT')
    }
  }
}

function nextSerNo(s: State): string {
  const prefix = `TRF-${isoDate(new Date()).replace(/-/g, '')}-`
  return prefix + pad(s.transfers.filter((t) => t.transferSerNo.startsWith(prefix)).length + 1, 4)
}

function submit(s: State, body: Body) {
  const c = check(s, body)
  // Memo hold (MemoPostingEngine): an OFAC hit waits for review; otherwise the total comes off the available balance.
  const memoStatus = c.ofacHit ? 'REVIEW' : 'POSTED'
  if (!c.ofacHit) c.from.cents -= c.amount + c.fee
  const t: Transfer = {
    transferSerNo: nextSerNo(s), ofacStatus: c.ofacHit ? 'FLAGGED' : 'OFAC_PASSED', memoStatus,
    processStatus: c.ofacHit ? 'PENDING' : 'PENDING_BATCH', firstSerNo: null, reqNo: 1, remaining: c.count - 1,
    recvName: c.recvName, bankName: c.bankName, routingNo: c.routingNo, acctNo: c.acctNo, acctType: c.acctType,
    fromAcctNo: c.from.acctNo, fromAcctName: c.from.acctName, fromAcctType: c.from.acctType, amount: c.amount, fee: c.fee,
    effectiveDt: c.effectiveDt, frequency: c.frequency, memo: c.memo, submitted: isoDateTime(new Date()), ofacTm: isoDateTime(new Date()),
    scheduleId: null, readyTm: null,
  }
  s.transfers.push(t)
  // TransferService.activate: an account still pending micro-deposits becomes ACTIVE; the transfer's account check has just verified it
  c.to.acctStatus = 'ACTIVE'
  log(s, 'ACH_ORIG', 'INFO', `Member M10982347 submitted transfer ${t.transferSerNo} for ${((c.amount + c.fee) / 100).toFixed(2)}, memo status ${memoStatus}`)
  return receipt(t)
}

function nextRecvSerNo(s: State): string {
  const prefix = `REC-${isoDate(new Date()).replace(/-/g, '')}-`
  return prefix + pad(s.externalAccounts.filter((a) => a.recvAcctSerNo.startsWith(prefix)).length + 1, 4)
}

/** TransferService.externalAccounts: newest first, active and pending ones. */
function externalAccounts(s: State) {
  return [...s.externalAccounts].reverse().map((a) => ({
    externalAccountId: a.recvAcctSerNo, bankName: bankName(a.routingNo), maskedAccountNumber: `••••${last4(a.acctNo)}`,
    accountType: a.acctType, status: a.acctStatus,
  }))
}

/** TransferService.addExternalAccount */
function addExternalAccount(s: State, r: Body) {
  const owner = String(r.accountHolderName ?? '').trim().replace(/\s+/g, ' ')
  if (!/^[A-Za-z0-9 .,'&-]{1,22}$/.test(owner)) fail(400, "Enter the account holder's name: up to 22 letters, numbers and spaces.")
  if (!String(r.bankName ?? '').trim()) fail(400, 'Enter the bank name.')
  const routingNo = String(r.routingNumber ?? '')
  if (!/^\d{9}$/.test(routingNo)) fail(400, 'Enter all 9 digits of the routing number.') // for now any 9 digits pass
  const acctNo = String(r.accountNumber ?? '')
  if (!/^\d{4,17}$/.test(acctNo)) fail(400, 'Enter the account number: 4 to 17 digits.')
  const acctType = String(r.accountType ?? '')
  if (acctType !== 'CHECKING' && acctType !== 'SAVINGS') fail(400, 'Choose Checking or Savings.')
  if (s.externalAccounts.some((a) => a.routingNo === routingNo && a.acctNo === acctNo && a.acctType === acctType)) {
    fail(400, 'This account is already in your external bank accounts.')
  }
  const added = { recvAcctSerNo: nextRecvSerNo(s), routingNo, acctNo, acctType, acctOwner: owner.toUpperCase(), acctStatus: PENDING_MICRO_DEPOSIT }
  s.externalAccounts.push(added)
  log(s, 'ACH_ORIG', 'INFO', `Member ${MEMBER_NO} added external account ${added.recvAcctSerNo} ending ${last4(acctNo)}, pending micro-deposit verification`)
  return { externalAccountId: added.recvAcctSerNo, status: PENDING_MICRO_DEPOSIT }
}

function receipt(t: Transfer) {
  return {
    transferSerNo: t.transferSerNo, processStatus: t.processStatus, ofacStatus: t.ofacStatus, memoStatus: t.memoStatus,
    recvName: t.recvName, bankName: t.bankName, routingNo: t.routingNo, acctLast4: last4(t.acctNo), acctType: t.acctType,
    fromAcctName: t.fromAcctName, fromAcctLast4: last4(t.fromAcctNo), amount: t.amount / 100, fee: t.fee / 100,
    total: (t.amount + t.fee) / 100, effectiveDt: t.effectiveDt, estimatedArrival: nextBankingDay(t.effectiveDt), frequency: t.frequency,
    transferCount: t.reqNo + t.remaining, memo: t.memo, submitted: t.submitted,
  }
}

// ---- ACH Origination File (EodController, EodFileJob, EodFileStore, FedSimulator) ----

// ---- ACH Receiving (ReceivingController, ReceivingEngine, FedInboundSimulator) ----

const RDFI = '123456780' // ACH_RCV/RDFI_ROUTING_NO
const SAMPLES: Record<string, string> = { PAYROLL: payrollSample, RETAIL: retailSample, OUT_OF_BALANCE: outOfBalanceSample }

function receiving(s: State, method: string, path: string, body: Body) {
  if (path === '/files' && method === 'GET') {
    return s.receivedFiles.map((f) => {
      const entries = s.receivedEntries.filter((e) => e.fileID === f.fileID)
      const total = (credit: number) => entries.filter((e) => e.creditFlag === credit).reduce((sum, e) => sum + Math.round(e.amount * 100), 0) / 100
      return { ...f, entryCount: entries.length, totalCreditAmt: total(1), totalDebitAmt: total(0) }
    }).sort((a, b) => b.fileHeaderDt.localeCompare(a.fileHeaderDt) || b.fileID.localeCompare(a.fileID))
  }
  if (path === '/files/receive' && method === 'POST') { // the Fed delivers it to fed/inbound; a window processes it
    const sample = String(body.sample ?? '')
    if (!SAMPLES[sample] && sample !== 'TAMPERED') fail(400, 'Choose a file to receive.')
    const fileName = `${sample}.ach`
    if (!s.inbound.includes(sample)) s.inbound = [...s.inbound, sample].sort()
    log(s, 'ACH_RCV', 'INFO', `User '${s.user}' received sample ${sample}: ${fileName} is waiting for the next processing window`)
    return { fileName, message: `${fileName} is in fed/inbound, waiting for the next processing window.` }
  }
  if (path === '/windows' && method === 'GET') {
    const today = isoDate(new Date())
    return {
      business_date: today, schedules: RECV_SCHEDULE, waiting_files: s.inbound.map((x) => `${x}.ach`),
      runs: s.recvRuns.filter((r) => r.business_date === today).map(({ business_date: _d, ...rest }) => rest),
      posting_file: s.postingJobs.find((j) => j.business_date === today) ?? null,
    }
  }
  const trigger = /^\/trigger\/(\d{4})$/.exec(path)
  if (trigger && method === 'POST') {
    const w = RECV_SCHEDULE.find((x) => x.window_time.replace(':', '') === trigger[1])
      ?? fail(400, `Unknown processing window ${trigger[1]}; use one of [${RECV_SCHEDULE.map((x) => x.window_time.replace(':', '')).join(', ')}].`)
    log(s, 'ACH_RCV', 'INFO', `User '${s.user}' triggered processing window ${w.window_time}`)
    return runRecvWindow(s, w.window_time, s.user!)
  }
  if (path === '/generate-posting-file' && method === 'POST') {
    if (typeof body.business_date !== 'string') fail(400, 'business_date is required.')
    return postingFile(s, body.business_date as string)
  }
  if (path.startsWith('/transactions?') && method === 'GET') {
    const q = new URLSearchParams(path.split('?')[1])
    const page = Number(q.get('page') ?? 1)
    const limit = Number(q.get('limit') ?? 10)
    const status = q.get('status')
    const rows = s.receivedEntries.filter((e) => e.fileID === q.get('fileID') && (!status || e.processStatus === status)).sort((a, b) => a.traceNo.localeCompare(b.traceNo) || a.tranID - b.tranID)
    return { items: rows.slice((page - 1) * limit, page * limit), total: rows.length, page, limit }
  }
  const m = /^\/transactions\/(\d+)$/.exec(path)
  const row = m && s.receivedEntries.find((e) => e.tranID === Number(m[1]))
  if (!row) fail(404, 'No such transaction.')
  return row
}

function receiveSample(s: State, sample: string) {
  const today = isoDate(new Date())
  const prefix = `ACHFILE-${today.replace(/-/g, '')}-`
  const fileID = `${prefix}${pad(s.receivedFiles.filter((f) => f.fileID.startsWith(prefix)).length + 1)}`
  const fileName = `${sample}.ach`
  const row: RecvFile = { fileID, fileName, fileHeaderDt: today, fileHeaderTm: '06:00:00', fedStatus: 'RECEIVED', processStatus: 'REJECTED_SIG_FAIL', modifier: 'A' }
  s.receivedFiles.push(row)
  if (sample === 'TAMPERED') return { fileID, fileName, processStatus: row.processStatus, message: `Security Operations alert: ${fileName} failed signature verification and was quarantined.` }
  const text = SAMPLES[sample] ?? fail(400, 'Choose a file to receive.')
  const r = ingest(text, RDFI)
  row.fileHeaderDt = r.fileHeaderDt ?? today
  row.fileHeaderTm = r.fileHeaderTm ?? row.fileHeaderTm
  row.modifier = r.modifier ?? row.modifier
  const seen = s.receivedFiles.some((f) => f !== row && f.fileHeaderDt === row.fileHeaderDt && f.fileHeaderTm === row.fileHeaderTm && f.modifier === row.modifier)
  row.processStatus = seen ? 'DUPLICATE' : r.status
  if (row.processStatus === 'VALIDATED') {
    const day = today.replace(/-/g, '')
    const serials = s.receivedEntries.filter((e) => e.transferSerNo.startsWith(`TRF-${day}-`)).length
    s.receivedEntries.push(...r.entries.map((e, i) => ({
      ...e, tranID: s.receivedEntries.length + i + 1, transferSerNo: `TRF-${day}-${pad(serials + i + 1, 4)}`, transferVersion: 1, fileID,
      batchID: `${fileID}-B${pad(e.batchNo, 3)}`, fedStatus: 'SETTLED', sourceID: 'FED_ACH_INBOUND', createTm: isoDateTime(new Date()), ...RECEIVED,
    })))
    // Ba's blueprint, steps 4-6, as the window that picks the file up runs them
    const rcv = (name: string) => s.params.find((p) => p.systemName === 'ACH_RCV' && p.paramName === name)?.paramRawValue ?? ''
    const n = processEntries(s.receivedEntries.filter((e) => e.fileID === fileID), s.core,
      rcv('TRUSTED_COMPANY_IDS').split(',').map((x) => x.trim()).filter(Boolean), toCents(rcv('MAX_ENTRY_AMOUNT')), isoDateTime(new Date()))
    log(s, 'ACH_RCV', 'INFO', `${fileID} processed: ${n.matched} matched, ${n.corrected} auto-corrected, ${n.review} for manual review, ${n.rejected} rejected, ${n.posted} memo posted`)
    return { fileID, fileName, processStatus: row.processStatus, message: r.message, counts: { entries: r.entries.length, ...n } }
  }
  return { fileID, fileName, processStatus: row.processStatus, message: r.message, counts: null }
}

/** ReceivingWindow.run: picks up every file waiting in fed/inbound (steps 2-3), runs steps 4-6, and records the run. */
function runRecvWindow(s: State, windowTime: string, runBy: string) {
  const now = new Date()
  const today = isoDate(now)
  const run: RecvRun = {
    execution_id: `EXEC-REC-${today.replace(/-/g, '')}-${windowTime.replace(':', '')}`, window_time: windowTime, business_date: today, files: 0,
    validated: 0, entries: 0, matched: 0, auto_corrected: 0, manual_review: 0, rejected: 0, memo_posted: 0, run_by: runBy, started_at: isoDateTime(now),
  }
  log(s, 'ACH_RCV', 'INFO', `${run.execution_id}: processing window ${windowTime} started`)
  for (const sample of s.inbound) {
    const r = receiveSample(s, sample)
    run.files++
    if (r.processStatus !== 'VALIDATED') log(s, 'ACH_RCV', 'WARN', `${r.fileID} ${r.processStatus}: ${r.message}`)
    if (!r.counts) continue
    run.validated++
    run.entries += r.counts.entries
    run.matched += r.counts.matched
    run.auto_corrected += r.counts.corrected
    run.manual_review += r.counts.review
    run.rejected += r.counts.rejected
    run.memo_posted += r.counts.posted
  }
  s.inbound = []
  s.recvRuns.push(run)
  log(s, 'ACH_RCV', 'INFO', `${run.execution_id}: ${run.files} file(s) processed`)
  return { execution_id: run.execution_id, status: 'COMPLETED', window_time: windowTime, started_at: run.started_at }
}

/** CorePostingFileJob.generate (step 7): posts the day's entries to the core, once a day. */
function postingFile(s: State, businessDate: string): PostingJob {
  const done = s.postingJobs.find((j) => j.business_date === businessDate)
  if (done) return done
  const ymd = businessDate.replace(/-/g, '')
  let count = 0, debits = 0, credits = 0
  for (const e of s.receivedEntries) {
    if (!['RECEIVED', 'AUTO_CORRECTED', 'MEMO_POSTED'].includes(e.processStatus) || !e.coreTranCode || (e.settlementDt ?? e.effectiveDt) > businessDate) continue
    if (e.coreTranCode !== 'PRENOTE') {
      const cents = Math.round(e.amount * 100)
      const acct = s.core[e.correctedAcctNo ?? e.recvAcctID]
      if (acct && e.processStatus !== 'MEMO_POSTED') acct.cents += e.creditFlag === 1 ? cents : -cents // a memo post already moved it
      if (e.creditFlag === 1) credits += cents
      else debits += cents
      count++
    }
    Object.assign(e, { processStatus: 'POSTED', postedTm: isoDateTime(new Date()) })
  }
  const job: PostingJob = {
    posting_job_id: `JOB-POST-${ymd}-EOD`, business_date: businessDate, total_records_posted: count, total_debit_volume: debits / 100,
    total_credit_volume: credits / 100, posting_file_name: `CORE_ACH_POSTING_${ymd}.DAT`, status: 'READY_FOR_CORE_INGESTION',
  }
  s.postingJobs.push(job)
  log(s, 'ACH_RCV', 'INFO', `${job.posting_job_id}: ${job.posting_file_name} posted ${count} entries, debits ${job.total_debit_volume}, credits ${job.total_credit_volume}`)
  return job
}

const param = (s: State, name: string) => s.params.find((p) => p.systemName === 'ACH_ORIG' && p.paramName === name)?.paramRawValue ?? ''

// ---- ACH Origination Processing (OriginationProcessingController) ----

function processing(s: State, method: string, path: string, body: Body) {
  const today = isoDate(new Date())
  const scheduleId = () => Number(new URLSearchParams(path.split('?')[1]).get('schedule_id'))
  if (path === '/trigger-window' && method === 'POST') {
    const time = String(body.window_time ?? '')
    if (!/^\d{2}:\d{2}$/.test(time)) fail(400, 'window_time must be a time like 12:00.')
    log(s, 'ACH_ORIG', 'INFO', `User '${s.user}' triggered origination window ${time}`)
    const r = runWindow(s, today, time, s.user!)
    const rejected = r.account_rejected + r.limit_rejected + r.hold_rejected
    return {
      window_id: r.window_id, execution_status: 'COMPLETED', estimated_completion_seconds: 0,
      message: `ACH processing window ${time}: ${r.extracted} due, ${r.ready_to_post} ready to post, ${r.ofac_flagged} flagged, ${rejected} rejected.`,
    }
  }
  if (path === '/windows' && method === 'GET') {
    const runs = s.origRuns.filter((r) => r.business_date === today)
    return {
      business_date: today,
      schedules: ORIG_SCHEDULE.map((w) => {
        const r = runs.filter((x) => x.window_time === w.window_time).pop()
        return { ...w, last_run: r ? (({ window_time: _t, business_date: _d, ...rest }) => rest)(r) : null }
      }),
      waiting_for_compliance: s.transfers.filter((t) => t.ofacStatus === 'FLAGGED').length,
    }
  }
  if (path.startsWith('/ready?') && method === 'GET') {
    const id = scheduleId()
    return s.transfers.filter((t) => t.processStatus === 'READY_TO_POST' && t.scheduleId === id && t.effectiveDt <= today)
      .sort((a, b) => a.transferSerNo.localeCompare(b.transferSerNo))
      .map((t) => ({ transfer_id: t.transferSerNo, recipient_name: t.recvName, routing_number: t.routingNo, amount: t.amount / 100, effective_dt: t.effectiveDt, ready_at: t.readyTm }))
  }
  if (path === '/send-to-fed' && method === 'POST') {
    const w = ORIG_SCHEDULE.find((x) => x.schedule_id === body.schedule_id) ?? fail(400, 'Choose a schedule.')
    const result = send(s, today, false, w.schedule_id)
    log(s, 'ACH_ORIG', result.fedStatus === 'ERROR' ? 'ERROR' : 'INFO', `User '${s.user}' sent the ${w.window_time} schedule to the Fed: ${result.message}`)
    return result
  }
  if (path.startsWith('/files?') && method === 'GET') {
    const id = scheduleId()
    return s.files.filter((f) => f.scheduleId === id && f.fileHeaderDt === today).map((f) => ({
      file_id: f.fileID, file_name: f.fileName, schedule_id: f.scheduleId, file_header_dt: f.fileHeaderDt, file_header_tm: f.fileHeaderTm,
      total_credit_count: f.totalCreditCount, total_credit_amt: f.totalCreditAmt, fed_status: f.fedStatus, send_tm: f.sendTm, batches: f.batches,
    }))
  }
  fail(404, 'Not found')
}

function eod(s: State, method: string, path: string, body: Body) {
  if (path === '/eod/status' && method === 'GET') return eodStatus(s)
  if (path === '/eod/run' && method === 'POST') {
    const date = typeof body.businessDate === 'string' ? body.businessDate : ''
    if (!date) fail(400, 'Choose a business date.')
    const result = runEod(s, date)
    log(s, 'ACH_ORIG', result.fedStatus === 'ERROR' ? 'ERROR' : 'INFO', `User '${s.user}' ran the EOD file for ${date}: ${result.message}`)
    return result
  }
  const m = /^\/eod\/files\/([^/]+)\/(resend|content)$/.exec(path)
  const file = m && s.files.find((f) => f.fileID === decodeURIComponent(m[1]))
  if (!file) fail(404, 'No such file.')
  if (m![2] === 'content' && method === 'GET') return { fileName: file.fileName, content: file.content }
  if (m![2] === 'resend' && method === 'POST') {
    if (file.fedStatus !== 'ERROR') fail(400, 'Only a file in ERROR can be resent.')
    const fedStatus = transmit(s, file)
    log(s, 'ACH_ORIG', fedStatus === 'ERROR' ? 'ERROR' : 'INFO', `User '${s.user}' resent ${file.fileID}: ${fedStatus}`)
    return { fedStatus }
  }
  fail(404, 'Not found')
}

function eodStatus(s: State) {
  const item = (t: Transfer, reason: string | null) => ({ transferSerNo: t.transferSerNo, recvName: t.recvName, amount: t.amount / 100, effectiveDt: t.effectiveDt, reason })
  const byDate = [...s.transfers].sort((a, b) => a.effectiveDt.localeCompare(b.effectiveDt) || a.transferSerNo.localeCompare(b.transferSerNo))
  // Waiting for a window, or made ready to post by one (Ba's blueprint, steps 1-7)
  const pending = byDate.filter((t) => ['PENDING_BATCH', 'READY_TO_POST'].includes(t.processStatus) && ['OFAC_PASSED', 'CLEARED'].includes(t.ofacStatus))
    .map((t) => item(t, null))
  const today = isoDate(new Date())
  const next = pending[0]?.effectiveDt ?? today
  return {
    cutoff: param(s, 'FED_FILE_CUTOFF_TIME'),
    nextBusinessDate: next < today ? today : next,
    pending,
    leftOut: [
      ...byDate.filter((t) => t.processStatus === 'PENDING' && t.ofacStatus === 'FLAGGED').map((t) => item(t, 'OFAC review')),
      ...byDate.filter((t) => t.processStatus === 'REJECTED' && t.ofacStatus === 'BLOCKED').map((t) => item(t, 'OFAC blocked')),
      ...byDate.filter((t) => t.processStatus === 'REJECTED' && t.ofacStatus !== 'BLOCKED').map((t) => item(t, 'Failed the account, limit or funds check')),
    ],
    files: [...s.files].reverse().map(({ content: _content, ...row }) => row),
  }
}

/** Each payment of a series is its own row, linked to the first (TransferService.scheduleNextPayment). */
function scheduleNext(s: State, t: Transfer) {
  if (t.remaining <= 0) return
  const firstSerNo = t.firstSerNo ?? t.transferSerNo
  const first = s.transfers.find((x) => x.transferSerNo === firstSerNo)!
  s.transfers.push({
    ...t, transferSerNo: nextSerNo(s), firstSerNo, reqNo: t.reqNo + 1, remaining: t.remaining - 1, memoStatus: 'PENDING', ofacStatus: 'OFAC_PASSED',
    processStatus: 'PENDING_BATCH', effectiveDt: occurrence(first.effectiveDt, t.frequency, t.reqNo), submitted: isoDateTime(new Date()),
    scheduleId: null, readyTm: null,
  })
}

/**
 * OriginationWindow (Ba's blueprint, steps 1-7) for every transfer due by the business date: funds for a payment not yet held,
 * OFAC (skipped once cleared), the hold, then READY_TO_POST. The demo's accounts are always active and its limits can't be
 * changed, so the account and limit re-checks always pass.
 */
function runWindow(s: State, businessDate: string, windowTime: string, runBy: string): OrigRun {
  const started = isoDateTime(new Date())
  const run: OrigRun = {
    window_id: `WIN-${businessDate.replace(/-/g, '')}-${windowTime.replace(':', '')}`, window_time: windowTime, business_date: businessDate,
    extracted: 0, account_rejected: 0, limit_rejected: 0, ofac_flagged: 0, hold_rejected: 0, ready_to_post: 0, run_by: runBy, started_at: started,
  }
  const due = s.transfers.filter((t) => t.processStatus === 'PENDING_BATCH' && t.effectiveDt <= businessDate)
    .sort((a, b) => a.transferSerNo.localeCompare(b.transferSerNo))
  run.extracted = due.length
  for (const t of due) {
    const from = s.accounts.find((a) => a.acctNo === t.fromAcctNo)!
    if (t.memoStatus !== 'POSTED' && from.cents < t.amount + t.fee) {
      reject(s, t, 'INSUFFICIENT_FUNDS')
      run.account_rejected++
      continue
    }
    if (t.ofacStatus !== 'CLEARED') {
      t.ofacTm = isoDateTime(new Date())
      if (OFAC.some((n) => normalize(n) === normalize(t.recvName))) {
        t.ofacStatus = 'FLAGGED'
        t.processStatus = 'PENDING'
        log(s, 'ACH_ORIG', 'WARN', `Compliance alert: ${t.transferSerNo} is flagged by OFAC and waits for review`)
        run.ofac_flagged++
        continue
      }
      t.ofacStatus = 'OFAC_PASSED'
    }
    if (t.memoStatus !== 'POSTED') { // a later payment of a series (E4); the first was held at Confirm
      from.cents -= t.amount + t.fee
      t.memoStatus = 'POSTED'
    }
    t.processStatus = 'READY_TO_POST'
    t.scheduleId = ORIG_SCHEDULE.find((w) => w.window_time === windowTime)?.schedule_id ?? null
    t.readyTm = isoDateTime(new Date())
    run.ready_to_post++
  }
  s.origRuns.push(run)
  return run
}

/** OriginationWindow.reject: gives the hold back and schedules the next payment of a series. */
function reject(s: State, t: Transfer, reason: string) {
  t.processStatus = 'REJECTED'
  if (t.memoStatus === 'POSTED') {
    s.accounts.find((a) => a.acctNo === t.fromAcctNo)!.cents += t.amount + t.fee
    t.memoStatus = 'RELEASED'
  }
  log(s, 'ACH_ORIG', 'WARN', `Origination window: ${t.transferSerNo} rejected, ${reason}`)
  scheduleNext(s, t)
}

// ---- OFAC Review (OriginationProcessingController, OriginationWindow.resolve) ----

function flagged(s: State) {
  return s.transfers.filter((t) => t.ofacStatus === 'FLAGGED')
    .sort((a, b) => a.ofacTm.localeCompare(b.ofacTm) || a.transferSerNo.localeCompare(b.transferSerNo))
    .map((t) => ({ transfer_id: t.transferSerNo, member_no: MEMBER_NO, recipient_name: t.recvName, amount: t.amount / 100, effective_dt: t.effectiveDt, flagged_at: t.ofacTm }))
}

function resolve(s: State, body: Body) {
  const id = String(body.transfer_id ?? '')
  const t = s.transfers.find((x) => x.transferSerNo === id) ?? fail(400, `No such transfer ${id}.`)
  if (t.ofacStatus !== 'FLAGGED') fail(400, `${id} isn't waiting for OFAC review (ofacStatus ${t.ofacStatus}).`)
  if (body.resolution === 'CLEARED_FALSE_POSITIVE') {
    t.ofacStatus = 'CLEARED'
    t.processStatus = 'PENDING_BATCH' // goes on at the next window
  } else if (body.resolution === 'BLOCKED_CONFIRMED_MATCH') {
    t.ofacStatus = 'BLOCKED'
    reject(s, t, 'OFAC_BLOCKED')
  } else {
    fail(400, 'resolution must be CLEARED_FALSE_POSITIVE or BLOCKED_CONFIRMED_MATCH.')
  }
  t.ofacTm = isoDateTime(new Date())
  log(s, 'ACH_ORIG', 'INFO', `Compliance officer '${s.user}' ${t.ofacStatus.toLowerCase()} ${id}`)
  return { transfer_id: id, previous_status: 'FLAGGED', current_status: t.ofacStatus, updated_at: t.ofacTm }
}

/** The 5 PM file: one last window, then everything ready (EodFileJob.run). */
const runEod = (s: State, businessDate: string) => send(s, businessDate, true, null)

/** EodFileJob.send: the 5 PM file (sweep), or Send to the Fed for one schedule's ready transactions. */
function send(s: State, businessDate: string, sweep: boolean, scheduleId: number | null) {
  if (!isBankingDay(businessDate)) return { fileID: null, fedStatus: null, message: `${businessDate} isn't a banking day, so no file was built.` }
  if (sweep) runWindow(s, businessDate, 'EOD', 'EOD_BATCH')
  const due = s.transfers
    .filter((t) => t.processStatus === 'READY_TO_POST' && t.effectiveDt <= businessDate && (scheduleId === null || t.scheduleId === scheduleId))
    .sort((a, b) => a.transferSerNo.localeCompare(b.transferSerNo))
  if (due.length === 0) return { fileID: null, fedStatus: null, message: `Nothing is due for ${businessDate}.` }

  const entryDesc = (t: Transfer) => (t.memo === null || RESERVED_ENTRY_DESC.includes(t.memo) ? 'TRANSFER' : t.memo)
  const groups = new Map<string, Transfer[]>() // E5: one effective date and one entry description per batch
  for (const t of due) {
    const key = `${t.effectiveDt}|${entryDesc(t)}]`
    groups.set(key, [...(groups.get(key) ?? []), t])
  }
  const batches: NachaBatch[] = [...groups.keys()].sort().map((key, i) => {
    const items = groups.get(key)!
    const entries: NachaEntry[] = items.map((t) => ({
      tranCode: t.acctType === 'SAVINGS' ? '32' : '22', routing: t.routingNo, account: t.acctNo, cents: t.amount,
      individualId: t.transferSerNo.replace(/-/g, ''), name: t.recvName, discData: t.frequency === 'ONETIME' ? 'S' : 'R',
      trace: `12345678${pad(s.nextTrace++, 7)}`,
    }))
    return {
      companyName: param(s, 'ORIG_COMPANY_NAME'), companyId: '9000000001', secCode: 'WEB', entryDesc: entryDesc(items[0]),
      effectiveDate: items[0].effectiveDt.replace(/-/g, '').slice(2), odfi: '12345678', batchNumber: i + 1, entries,
    }
  })

  const now = new Date()
  const today = isoDate(now)
  const seq = s.files.filter((f) => f.fileHeaderDt === today).length + 1
  const fileID = `FILE_${today.replace(/-/g, '')}_${pad(seq, 2)}`
  const content = buildNachaFile({
    immediateDestination: param(s, 'FED_IMMEDIATE_DEST'), immediateOrigin: '123456780', originName: 'DEMO CREDIT UNION',
    fileCreationDate: today.replace(/-/g, '').slice(2), fileCreationTime: pad(now.getHours()) + pad(now.getMinutes()),
    fileIdModifier: 'ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'[seq - 1], batches,
  })
  const file: AchFileRow = {
    fileID, fileName: `${fileID}.ach`, fedStatus: 'QUEUED', fileHeaderDt: today, fileHeaderTm: `${pad(now.getHours())}:${pad(now.getMinutes())}:00`,
    totalCreditCount: due.length, totalCreditAmt: due.reduce((sum, t) => sum + t.amount, 0) / 100, sendTm: null, content, scheduleId,
    batches: batches.map((b) => ({
      batch_id: `${fileID}-B${pad(b.batchNumber, 3)}`, batch_no: b.batchNumber, company_name: b.companyName, std_entry_code: b.secCode,
      company_entry_desc: b.entryDesc, effective_dt: `20${b.effectiveDate.slice(0, 2)}-${b.effectiveDate.slice(2, 4)}-${b.effectiveDate.slice(4)}`,
      entry_count: b.entries.length, total_credit_amt: b.entries.reduce((sum, e) => sum + e.cents, 0) / 100,
    })),
  }
  s.files.push(file)
  for (const t of due) {
    t.processStatus = 'PROCESSED'
    scheduleNext(s, t)
  }
  const fedStatus = transmit(s, file)
  return { fileID, fedStatus, message: `${fileID}: ${fedStatus}` }
}

/** EX-02: 3 attempts. The demo doesn't wait between them. */
function transmit(s: State, file: AchFileRow): string {
  const failures = Math.min(Number(param(s, 'FED_SIM_FAIL_ATTEMPTS')), 3)
  for (let attempt = 1; attempt <= failures; attempt++) log(s, 'ACH_ORIG', 'WARN', `Fed send of ${file.fileID} failed on attempt ${attempt}`)
  file.fedStatus = failures === 3 ? 'ERROR' : 'SENT'
  if (file.fedStatus === 'SENT') {
    file.sendTm = isoDateTime(new Date())
    log(s, 'ACH_ORIG', 'INFO', `Fed send of ${file.fileID} succeeded on attempt ${failures + 1}`)
  } else {
    log(s, 'ACH_ORIG', 'FATAL', `Operations alert: ${file.fileID} could not be sent to the Fed after 3 attempts`)
  }
  return file.fedStatus
}
