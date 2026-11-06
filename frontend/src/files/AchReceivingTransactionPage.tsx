import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import type { ReceivedFile } from './AchReceivingFilePage'

type Overview = {
  tranID: number; amount: number; creditFlag: number; originatorID: string; companyName: string; recvName: string
  createTm: string; settlementDt: string | null; traceNo: string
}
type Page = { items: Overview[]; total: number; page: number; limit: number }
type Detail = Record<string, string | number | null>

const LIMIT = 10
const RETRY_MS = 3000
const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
const dateTime = (s: string) => s.replace('T', ' ').slice(0, 19)
const creditDebit = (flag: number) => (flag === 1 ? 'CREDIT' : 'DEBIT')

// The design's 24 ACHRecvTransaction fields, in the design's order.
const FIELDS: [string, string][] = [
  ['tranID', 'Tran ID'], ['transferSerNo', 'Transfer Serial No'], ['transferVersion', 'Transfer Version'], ['fileID', 'File ID'],
  ['batchID', 'Batch ID'], ['creditFlag', 'Cr/Db Indicator'], ['tranCode', 'Tran Code'], ['companyName', 'Company Name'],
  ['companyID', 'Company ID'], ['companyDiscData', 'Company Discretionary Data'], ['stdEntryCode', 'SEC Code'],
  ['companyEntryDesc', 'Company Entry Description'], ['descriptiveDt', 'Descriptive Date'], ['effectiveDt', 'Effective Date'],
  ['settlementDt', 'Settlement Date'], ['originatorID', 'Originator ID'], ['amount', 'Amount'], ['recvAcctID', 'Receiver Account'],
  ['recvName', 'Recipient Name'], ['transactionDiscData', 'Transaction Discretionary Data'], ['traceNo', 'Trace No'],
  ['fedStatus', 'Fed Status'], ['sourceID', 'Source ID'], ['createTm', 'Received Date'],
]

function fieldValue(name: string, d: Detail): string {
  const v = d[name]
  if (v === null || v === '') return '—'
  if (name === 'amount') return usd(Number(v))
  if (name === 'creditFlag') return creditDebit(Number(v))
  if (name === 'createTm') return dateTime(String(v))
  return String(v)
}

/** UC-ACH-RCV-02 phase 2: the ACH Inbound Overview, for the file named in the address. */
export default function AchReceivingTransactionPage({ route, fileID }: { route: string; fileID: string }) {
  const [files, setFiles] = useState<ReceivedFile[] | null>(null)
  const [page, setPage] = useState(1)
  const [data, setData] = useState<Page | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    api<ReceivedFile[]>('/v1/ach/receiving/files').then(setFiles).catch(() => setFailed(true))
  }, [])

  const selected = fileID || files?.find((f) => f.entryCount > 0)?.fileID || files?.[0]?.fileID || ''

  useEffect(() => setPage(1), [selected])

  const load = useCallback(async () => {
    if (!selected) return
    setFailed(false)
    try {
      setData(await api<Page>(`/v1/ach/receiving/transactions?fileID=${encodeURIComponent(selected)}&page=${page}&limit=${LIMIT}&sortBy=traceNo&sortDir=ASC`))
    } catch {
      setFailed(true)
    }
  }, [selected, page])

  useEffect(() => {
    load()
  }, [load])

  // EX-02: keeps retrying while the warning shows; the page stays usable and Refresh tries at once.
  useEffect(() => {
    if (!failed || !selected) return
    const timer = setTimeout(load, RETRY_MS)
    return () => clearTimeout(timer)
  }, [failed, selected, load])

  async function select(tranID: number) {
    try {
      setDetail(await api<Detail>(`/v1/ach/receiving/transactions/${tranID}`))
    } catch {
      setFailed(true)
    }
  }

  const pages = data ? Math.ceil(data.total / LIMIT) : 0

  return (
    <>
      <h2>ACH Inbound Overview</h2>
      {files && (
        <div className="toolbar">
          <label>
            File
            <select value={selected} onChange={(e) => { window.location.hash = `${route}/${encodeURIComponent(e.target.value)}` }}>
              {files.map((f) => <option key={f.fileID} value={f.fileID}>{f.fileID} ({f.processStatus})</option>)}
            </select>
          </label>
        </div>
      )}

      {(data?.total === 0 || files?.length === 0) && <p className="notice" role="status">No receiving transactions found in this file batch.</p>}
      {data && data.total > 0 && (
        <>
          <table aria-label="Receiving transactions">
            <thead>
              <tr>
                <th>Transaction Amount</th><th>Cr/Db Indicator</th><th>Originator Name</th><th>Company Name</th><th>Recipient Name</th>
                <th>Received Date</th><th>Settlement Date</th><th>Trace No</th><th>Action</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((t) => (
                <tr key={t.tranID}>
                  <td>{usd(t.amount)}</td>
                  <td><span className={`badge ${t.creditFlag === 1 ? 'credit' : 'debit'}`}>{creditDebit(t.creditFlag)}</span></td>
                  <td>{t.originatorID || t.companyName}</td>
                  <td>{t.companyName.slice(0, 16)}</td>
                  <td>{t.recvName.slice(0, 22)}</td>
                  <td>{dateTime(t.createTm)}</td>
                  <td>{t.settlementDt ?? '—'}</td>
                  <td className="code">{t.traceNo}</td>
                  <td className="row-actions"><button className="primary" onClick={() => select(t.tranID)}>Select</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pager">
            <button disabled={page <= 1} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page} of {pages} ({data.total} transactions)</span>
            <button disabled={page >= pages} onClick={() => setPage(page + 1)}>Next</button>
          </div>
        </>
      )}

      {failed && (
        <div className="toast warn" role="status">
          Error loading transactions. Retrying... <button onClick={load}>Refresh</button>
        </div>
      )}

      {detail && (
        <div className="overlay">
          <div className="modal wide" role="dialog" aria-label="Transaction details">
            <h2>Transaction details</h2>
            <dl className="details">
              {FIELDS.map(([name, label]) => (
                <div key={name}><dt>{label}</dt><dd>{fieldValue(name, detail)}</dd></div>
              ))}
            </dl>
            <div className="actions">
              <button className="primary" onClick={() => setDetail(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
