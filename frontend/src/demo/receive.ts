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
