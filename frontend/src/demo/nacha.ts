// NACHA records in TypeScript, for the online demo only. nacha.test.ts holds it to the same golden file as the
// server's NachaFileAssembler + AchFileWriter (src/test/resources/nacha).
export type NachaEntry = {
  tranCode: string; routing: string; account: string; cents: number; individualId: string; name: string; discData: string; trace: string
}
export type NachaBatch = {
  companyName: string; companyId: string; secCode: string; entryDesc: string; effectiveDate: string; odfi: string
  batchNumber: number; entries: NachaEntry[]
}
export type NachaFile = {
  immediateDestination: string; immediateOrigin: string; originName: string; fileCreationDate: string
  fileCreationTime: string; fileIdModifier: string; batches: NachaBatch[]
}

const text = (s: string, n: number) => s.slice(0, n).padEnd(n)
const num = (v: number, n: number) => String(v).padStart(n, '0')
const blank = (n: number) => ' '.repeat(n)
const HASH_MOD = 10_000_000_000 // the entry hash keeps its last 10 digits

export function buildNachaFile(f: NachaFile): string {
  const lines = [
    `101 ${f.immediateDestination} ${f.immediateOrigin}${f.fileCreationDate}${f.fileCreationTime}${f.fileIdModifier}094101` +
      text('FEDERAL RESERVE BANK', 23) + text(f.originName, 23) + blank(8),
  ]
  let fileHash = 0
  let fileCredits = 0
  let fileEntries = 0
  for (const b of f.batches) {
    lines.push(`5220${text(b.companyName, 16)}${blank(20)}${b.companyId}${b.secCode}${text(b.entryDesc, 10)}${blank(6)}` +
      `${b.effectiveDate}${blank(3)}1${b.odfi}${num(b.batchNumber, 7)}`)
    let hash = 0
    let credits = 0
    for (const e of b.entries) {
      hash += Number(e.routing.slice(0, 8))
      credits += e.cents
      lines.push(`6${e.tranCode}${e.routing}${text(e.account, 17)}${num(e.cents, 10)}${text(e.individualId, 15)}` +
        `${text(e.name, 22)}${text(e.discData, 2)}0${e.trace}`)
    }
    lines.push(`8220${num(b.entries.length, 6)}${num(hash % HASH_MOD, 10)}${num(0, 12)}${num(credits, 12)}${b.companyId}` +
      `${blank(25)}${b.odfi}${num(b.batchNumber, 7)}`)
    fileHash += hash
    fileCredits += credits
    fileEntries += b.entries.length
  }
  const blocks = Math.ceil((lines.length + 1) / 10)
  lines.push(`9${num(f.batches.length, 6)}${num(blocks, 6)}${num(fileEntries, 8)}${num(fileHash % HASH_MOD, 10)}` +
    `${num(0, 12)}${num(fileCredits, 12)}${blank(39)}`)
  while (lines.length % 10 !== 0) lines.push('9'.repeat(94))
  return `${lines.join('\n')}\n`
}
