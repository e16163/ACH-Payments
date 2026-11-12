// The ingestion rules of ReceivingEngine and AchFileValidator in TypeScript, for the online demo only. server.test.ts
// holds it to the same sample files and totals as ReceivingEngineIntegrationTest.
export type RecvEntry = {
  creditFlag: number; tranCode: string; companyName: string; companyID: string; companyDiscData: string; stdEntryCode: string
  companyEntryDesc: string; descriptiveDt: string; effectiveDt: string; settlementDt: string; originatorID: string; amount: number
  recvAcctID: string; recvName: string; transactionDiscData: string; traceNo: string; batchNo: number
}
export type Ingested = {
  status: 'VALIDATED' | 'OUT_OF_BALANCE' | 'REJECTED'; fileHeaderDt: string | null; fileHeaderTm: string | null; modifier: string | null
  entries: RecvEntry[]; message: string
}

const HASH_MOD = 10_000_000_000
const field = (l: string, from: number, to: number) => l.slice(from - 1, to).trim()
const yymmdd = (s: string) => `20${s.slice(0, 2)}-${s.slice(2, 4)}-${s.slice(4, 6)}`
const isDebit = (code: string) => code[1] >= '6'
const rejected = (message: string): Ingested => ({ status: 'REJECTED', fileHeaderDt: null, fileHeaderTm: null, modifier: null, entries: [], message })

export function ingest(text: string, rdfi: string): Ingested {
  const lines = text.trimEnd().split('\n')
  if (lines.some((l) => l.length !== 94)) return rejected('A record is not 94 characters.')
  const header = lines[0]
  if (header[0] !== '1') return rejected('The file has no File Header.')
  if (field(header, 4, 13) !== rdfi) return rejected(`The file is addressed to ${field(header, 4, 13)}, not to our routing number ${rdfi}`)
  if (lines.length % 10 !== 0) return rejected(`The file has ${lines.length} records, not a multiple of 10`)

  const entries: RecvEntry[] = []
  const problems: string[] = []
  const compare = (what: string, stated: number, actual: number) => {
    if (stated !== actual) problems.push(`${what} says ${stated} but the entries add up to ${actual}`)
  }
  let batch = ''
  let count = 0, hash = 0, debits = 0, credits = 0, batches = 0, fileCount = 0, fileHash = 0, fileDebits = 0, fileCredits = 0
  for (const l of lines.slice(1)) {
    if (l === '9'.repeat(94)) continue
    if (l[0] === '5') {
      batch = l; batches++; count = 0; hash = 0; debits = 0; credits = 0
    } else if (l[0] === '6') {
      const cents = Number(l.slice(29, 39))
      const effectiveDt = yymmdd(field(batch, 70, 75))
      if (isDebit(l.slice(1, 3))) debits += cents
      else credits += cents
      count++
      hash += Number(l.slice(3, 11))
      entries.push({
        creditFlag: isDebit(l.slice(1, 3)) ? 0 : 1, tranCode: l.slice(1, 3), companyName: field(batch, 5, 20), companyID: field(batch, 41, 50),
        companyDiscData: field(batch, 21, 40), stdEntryCode: field(batch, 51, 53), companyEntryDesc: field(batch, 54, 63),
        descriptiveDt: yymmdd(field(batch, 64, 69)), effectiveDt, settlementDt: effectiveDt, originatorID: field(batch, 41, 50), amount: cents / 100,
        recvAcctID: field(l, 13, 29), recvName: field(l, 55, 76), transactionDiscData: field(l, 77, 78), traceNo: field(l, 80, 94), batchNo: batches,
      })
    } else if (l[0] === '8') {
      const where = `Batch ${Number(l.slice(87, 94))}: `
      compare(`${where}entry/addenda count`, Number(l.slice(4, 10)), count)
      compare(`${where}entry hash`, Number(l.slice(10, 20)), hash % HASH_MOD)
      compare(`${where}total debits`, Number(l.slice(20, 32)), debits)
      compare(`${where}total credits`, Number(l.slice(32, 44)), credits)
      fileCount += count; fileHash += hash; fileDebits += debits; fileCredits += credits
    } else if (l[0] === '9') {
      compare('File Control batch count', Number(l.slice(1, 7)), batches)
      compare('File Control entry/addenda count', Number(l.slice(13, 21)), fileCount)
      compare('File Control entry hash', Number(l.slice(21, 31)), fileHash % HASH_MOD)
      compare('File Control total debits', Number(l.slice(31, 43)), fileDebits)
      compare('File Control total credits', Number(l.slice(43, 55)), fileCredits)
    }
  }
  const parsed = {
    fileHeaderDt: yymmdd(header.slice(23, 29)), fileHeaderTm: `${header.slice(29, 31)}:${header.slice(31, 33)}:00`, modifier: header[33],
  }
  return problems.length
    ? { status: 'OUT_OF_BALANCE', ...parsed, entries: [], message: problems.join('; ') }
    : { status: 'VALIDATED', ...parsed, entries, message: 'The file was validated and saved.' }
}

// ---- Ba's blueprint, Receiving steps 4-6 (ReceivedEntryProcessor) ----

export type Processing = {
  processStatus: string; coreTranCode: string | null; correctedAcctNo: string | null; processReason: string | null
  returnCode: string | null; memoPostedTm: string | null; postedTm: string | null
}
export type CoreAccount = { status: string; cents: number }

export const RECEIVED: Processing = {
  processStatus: 'RECEIVED', coreTranCode: null, correctedAcctNo: null, processReason: null, returnCode: null, memoPostedTm: null, postedTm: null,
}

const CORE_TRAN_CODES: Record<string, string> = {
  '22': 'DDA_CR', '27': 'DDA_DR', '32': 'SAV_CR', '37': 'SAV_DR', '23': 'PRENOTE', '28': 'PRENOTE', '33': 'PRENOTE', '38': 'PRENOTE',
}
// The V17 seed: an alias (ACHRecvAcctSubs), a stop payment and a blocked originator
const ALIASES: Record<string, string> = { '987654328019': '555000328019' }
const STOPS = [{ acctNo: '987654323019', companyID: '2987654321' }]
const BLOCKS = [{ acctNo: '987654322019', companyID: '2987654321' }]

/** The V17 SimCoreAccount rows for the sample files' receivers, each set up to show one rule. */
export function sampleCore(): Record<string, CoreAccount> {
  const core: Record<string, CoreAccount> = {}
  const add = (acctNo: string, status = 'ACTIVE') => (core[acctNo] = { status, cents: 200_000 })
  for (let i = 1; i <= 5; i++) add(`98765432${i}019`)
  add('987654326019', 'CLOSED')
  add('987654327019', 'FROZEN')
  add('0987654329019') // zero padding
  add('555000328019') // the alias's account
  for (const group of [4, 5, 6, 7]) for (let i = 1; i <= 10; i++) add(`987654${group}${String(i).padStart(2, '0')}019`)
  core['987654410019'].cents = 500 // $5.00, for R01
  return core
}

const stripZeros = (acct: string) => acct.replace(/^0+/, '')

type Row = RecvEntry & Processing
type Return = [code: string, reason: string]

function reject(e: Row, [code, reason]: Return) {
  Object.assign(e, { processStatus: 'REJECTED', returnCode: code, processReason: reason })
}

function returnFor(e: Row, core: Record<string, CoreAccount>): Return | null {
  if (e.processStatus === 'MANUAL_REVIEW') return e.processReason === 'ACCOUNT_NOT_FOUND' ? ['R03', 'NO_ACCOUNT_UNABLE_TO_LOCATE'] : null
  const acctNo = e.correctedAcctNo ?? e.recvAcctID
  const acct = core[acctNo]
  if (acct.status === 'CLOSED') return ['R02', 'ACCOUNT_CLOSED']
  if (acct.status === 'FROZEN') return ['R16', 'ACCOUNT_FROZEN']
  if (acct.status === 'DECEASED') return ['R15', 'BENEFICIARY_DECEASED']
  if (e.creditFlag === 1) return null
  if (STOPS.some((x) => x.acctNo === acctNo && x.companyID === e.companyID)) return ['R08', 'PAYMENT_STOPPED']
  if (BLOCKS.some((x) => x.acctNo === acctNo && x.companyID === e.companyID)) {
    return ['CCD', 'CTX'].includes(e.stdEntryCode) ? ['R29', 'CORPORATE_NOT_AUTHORIZED'] : ['R10', 'CUSTOMER_ADVISES_NOT_AUTHORIZED']
  }
  return acct.cents < Math.round(e.amount * 100) ? ['R01', 'INSUFFICIENT_FUNDS'] : null
}

/** Steps 4-6 on one file's entries, in three passes as the server runs them. Returns the counts the server logs. */
export function processEntries(rows: Row[], core: Record<string, CoreAccount>, earlyPosting: (e: Row) => boolean, maxCents: number | null, now: string) {
  let matched = 0, corrected = 0, review = 0, rejected = 0, posted = 0
  // Step 4: transaction code, then the account: exact, a known alias, or zero padding
  for (const e of rows) {
    const code = CORE_TRAN_CODES[e.tranCode]
    if (!code) {
      Object.assign(e, { processStatus: 'MANUAL_REVIEW', processReason: 'UNSUPPORTED_TRAN_CODE' })
      review++
      continue
    }
    e.coreTranCode = code
    if (core[e.recvAcctID]) {
      matched++
      continue
    }
    const alias = ALIASES[e.recvAcctID]
    const padded = alias ? undefined : Object.keys(core).filter((a) => stripZeros(a) === stripZeros(e.recvAcctID))
    if (alias || padded?.length === 1) {
      Object.assign(e, { processStatus: 'AUTO_CORRECTED', correctedAcctNo: alias ?? padded![0], processReason: alias ? 'KNOWN_ALIAS' : 'ZERO_PADDING' })
      corrected++
    } else {
      Object.assign(e, { processStatus: 'MANUAL_REVIEW', processReason: 'ACCOUNT_NOT_FOUND' })
      review++
    }
  }
  // Step 5: return codes; an entry over MAX_ENTRY_AMOUNT waits for manual review
  for (const e of rows) {
    const r = returnFor(e, core)
    if (r) {
      reject(e, r)
      rejected++
    } else if (maxCents !== null && Math.round(e.amount * 100) > maxCents && e.processStatus !== 'MANUAL_REVIEW') {
      Object.assign(e, { processStatus: 'MANUAL_REVIEW', processReason: 'OVER_LIMIT' })
    }
  }
  // Step 6: debits are held; credits are made available only when a business rule sets earlyPosting
  for (const e of rows) {
    if (!['RECEIVED', 'AUTO_CORRECTED'].includes(e.processStatus) || e.coreTranCode === 'PRENOTE') continue
    const credit = e.creditFlag === 1
    if (credit && !earlyPosting(e)) continue
    const acct = core[e.correctedAcctNo ?? e.recvAcctID]
    const cents = Math.round(e.amount * 100)
    if (!credit && acct.cents < cents) {
      reject(e, ['R01', 'INSUFFICIENT_FUNDS']) // spent since step 5
      continue
    }
    acct.cents += credit ? cents : -cents
    Object.assign(e, { processStatus: 'MEMO_POSTED', memoPostedTm: now })
    posted++
  }
  return { matched, corrected, review, rejected, posted }
}
