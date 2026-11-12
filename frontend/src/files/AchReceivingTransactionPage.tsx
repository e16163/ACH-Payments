import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'
import { todayET } from './today'

type Overview = {
  tranID: number; amount: number; creditFlag: number; originatorID: string; companyName: string; recvName: string
  createTm: string; effectiveDt: string; settlementDt: string | null; traceNo: string; processStatus: string; returnCode: string | null
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

// Ba's blueprint, Receiving steps 4-7: what processing did to the entry.
const PROCESSING_FIELDS: [string, string][] = [
  ['processStatus', 'Processing Status'], ['coreTranCode', 'Core Tran Code'], ['correctedAcctNo', 'Corrected Account'],
  ['processReason', 'Reason'], ['returnCode', 'Return Code'], ['memoPostedTm', 'Memo Posted'], ['postedTm', 'Posted'],
]

// The blueprint's statuses, in processing order, for the Status filter.
const STATUSES = ['RECEIVED', 'AUTO_CORRECTED', 'MANUAL_REVIEW', 'REJECTED', 'MEMO_POSTED', 'POSTED']

function fieldValue(name: string, d: Detail): string {
  const v = d[name]
  if (v === null || v === '') return '—'
  if (name === 'amount') return usd(Number(v))
  if (name === 'creditFlag') return creditDebit(Number(v))
  if (name === 'createTm' || name === 'memoPostedTm' || name === 'postedTm') return dateTime(String(v))
  return String(v)
}

/**
 * UC-ACH-RCV-02 phase 2, and Ba (2026-11-12): search received transactions by effective date, start to end (both today at first).
 * View transactions on the processing screen opens it for one file instead (the file named in the address).
 */
export default function AchReceivingTransactionPage({ route, fileID }: { route: string; fileID: string }) {
  const [start, setStart] = useState(todayET)
  const [end, setEnd] = useState(todayET)
  const [page, setPage] = useState(1)
  const [status, setStatus] = useState('')
  const [data, setData] = useState<Page | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [failed, setFailed] = useState(false)
  const badRange = !fileID && (!start || !end || start > end)

  useEffect(() => setPage(1), [fileID, start, end, status])

  const load = useCallback(async () => {
    if (badRange) return
    setFailed(false)
    const filter = fileID ? `fileID=${encodeURIComponent(fileID)}` : `start=${start}&end=${end}`
    try {
      setData(await api<Page>(`/v1/ach/receiving/transactions?${filter}${status ? `&status=${status}` : ''}&page=${page}&limit=${LIMIT}&sortBy=traceNo&sortDir=ASC`))
    } catch {
      setFailed(true)
    }
  }, [fileID, start, end, badRange, status, page])

  useEffect(() => {
    load()
  }, [load])

  // EX-02: keeps retrying while the warning shows; the page stays usable and Refresh tries at once.
  useEffect(() => {
    if (!failed) return
    const timer = setTimeout(load, RETRY_MS)
    return () => clearTimeout(timer)
  }, [failed, load])

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
      <h2>ACH Receiving Transactions</h2>
      <div className="toolbar">
        {fileID ? (
          <span className="hint">File {fileID}. <a href={route}>Search by effective date instead</a></span>
        ) : (
          <>
            <label>
              Start date
              <input type="date" value={start} onChange={(e) => setStart(e.target.value)} />
            </label>
            <label>
              End date
              <input type="date" value={end} onChange={(e) => setEnd(e.target.value)} />
            </label>
          </>
        )}
        <label>
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            {STATUSES.map((s) => <option key={s} value={s}>{s === 'MANUAL_REVIEW' ? 'Manual review' : s}</option>)}
          </select>
        </label>
      </div>
      <p className="hint">Search and view only. Files are processed on ACH Receiving Processing.</p>

      {badRange && <p className="page-error">Choose a start date on or before the end date.</p>}
      {!badRange && data?.total === 0 && (
        <p className="notice" role="status">
          {fileID ? 'No receiving transactions in this file' : 'No receiving transactions with an effective date in this range'}
          {status ? ` with status ${status}` : ''}.
        </p>
      )}
      {!badRange && data && data.total > 0 && (
        <>
          <table aria-label="Receiving transactions">
            <thead>
              <tr>
                <th>Transaction Amount</th><th>Cr/Db Indicator</th><th>Originator Name</th><th>Company Name</th><th>Recipient Name</th>
                <th>Received Date</th><th>Effective Date</th><th>Settlement Date</th><th>Trace No</th><th>Status</th><th>Action</th>
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
                  <td>{t.effectiveDt}</td>
                  <td>{t.settlementDt ?? '—'}</td>
                  <td className="code">{t.traceNo}</td>
                  <td>{t.processStatus}{t.returnCode ? ` ${t.returnCode}` : ''}</td>
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
            <h3>Processing</h3>
            <dl className="details">
              {PROCESSING_FIELDS.map(([name, label]) => (
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
