import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { formatDate } from '../member/rules'
import { todayET } from './today'

type Txn = {
  transfer_id: string; member_no: string; recipient_name: string; routing_number: string; amount: number; effective_dt: string
  process_status: string; ofac_status: string; window_name: string | null; created_at: string
}
type Page = { items: Txn[]; total: number; page: number; limit: number }
type Detail = Record<string, string | number | boolean | null>

const LIMIT = 10
const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
const dateTime = (s: string) => s.replace('T', ' ').slice(0, 19)

// ACHOriginatorTransfer.processStatus, in processing order (V18), for the Status filter.
const STATUSES = ['PENDING_BATCH', 'PENDING', 'READY_TO_POST', 'PROCESSED', 'REJECTED', 'CANCELLED']

// Labels for the columns a reader looks for first; every other column follows under its own name, so a new one shows up as is.
const LABELS: Record<string, string> = {
  transferSerNo: 'Transfer', memberNo: 'Member', recvName: 'Recipient', recvRoutingNo: 'Routing', recvAcctNo: 'Recipient Account',
  transferAmt: 'Amount', feeAmt: 'Fee', effectiveDt: 'Effective Date', processStatus: 'Processing Status', ofacStatus: 'OFAC Status',
  memoStatus: 'Hold', windowName: 'Window', fileID: 'ACH Origination File', origAcctNo: 'From Account', companyEntryDesc: 'Entry Description',
  createTm: 'Created',
}

export function detailRows(d: Detail): [string, string][] {
  const keys = [...Object.keys(LABELS).filter((k) => k in d), ...Object.keys(d).filter((k) => !(k in LABELS)).sort()]
  return keys.map((k) => {
    const v = d[k]
    const shown = v === null || v === '' ? '—' : k === 'transferAmt' || k === 'feeAmt' ? usd(Number(v)) : String(v).replace(/T(\d\d:\d\d:\d\d).*/, ' $1')
    return [LABELS[k] ?? k, shown]
  })
}

/**
 * Ba (2026-11-12): ACH origination transactions with an effective date from start to end (both today at first), and each one's
 * details. Search and view only; the ACH origination file is made on ACH Origination Processing.
 */
export default function AchOriginationTransactionPage() {
  const [start, setStart] = useState(todayET)
  const [end, setEnd] = useState(todayET)
  const [status, setStatus] = useState('')
  const [page, setPage] = useState(1)
  const [data, setData] = useState<Page | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const badRange = !start || !end || start > end

  useEffect(() => setPage(1), [start, end, status])

  const load = useCallback(async () => {
    if (badRange) return
    setError(null)
    try {
      setData(await api<Page>(`/v1/ach/origination/transactions?start=${start}&end=${end}${status ? `&status=${status}` : ''}&page=${page}&limit=${LIMIT}`))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    }
  }, [start, end, badRange, status, page])

  useEffect(() => {
    load()
  }, [load])

  async function select(id: string) {
    try {
      setDetail(await api<Detail>(`/v1/ach/origination/transactions/${encodeURIComponent(id)}`))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    }
  }

  const pages = data ? Math.ceil(data.total / LIMIT) : 0

  return (
    <>
      <h2>ACH Origination Transactions</h2>
      <div className="toolbar">
        <label>
          Start date
          <input type="date" value={start} onChange={(e) => setStart(e.target.value)} />
        </label>
        <label>
          End date
          <input type="date" value={end} onChange={(e) => setEnd(e.target.value)} />
        </label>
        <label>
          Status
          <select value={status} onChange={(e) => setStatus(e.target.value)}>
            <option value="">All</option>
            {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
          </select>
        </label>
      </div>
      <p className="hint">Search and view only. To create the ACH origination file, use ACH Origination Processing.</p>

      {badRange && <p className="page-error">Choose a start date on or before the end date.</p>}
      {error && <p className="page-error">{error}</p>}
      {!badRange && data?.total === 0 && (
        <p className="notice" role="status">No ACH origination transactions with an effective date in this range{status ? ` and status ${status}` : ''}.</p>
      )}
      {!badRange && data && data.total > 0 && (
        <>
          <table aria-label="ACH origination transactions">
            <thead>
              <tr>
                <th>Transfer</th><th>Member</th><th>Recipient</th><th>Routing</th><th>Amount</th><th>Effective Date</th><th>Status</th>
                <th>OFAC</th><th>Window</th><th>Created</th><th>Action</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((t) => (
                <tr key={t.transfer_id}>
                  <td className="code">{t.transfer_id}</td>
                  <td>{t.member_no}</td>
                  <td>{t.recipient_name}</td>
                  <td className="code">{t.routing_number}</td>
                  <td>{usd(t.amount)}</td>
                  <td>{formatDate(t.effective_dt)}</td>
                  <td>{t.process_status}</td>
                  <td>{t.ofac_status}</td>
                  <td>{t.window_name ?? '—'}</td>
                  <td>{dateTime(t.created_at)}</td>
                  <td className="row-actions"><button className="primary" onClick={() => select(t.transfer_id)}>Select</button></td>
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

      {detail && (
        <div className="overlay">
          <div className="modal wide" role="dialog" aria-label="Transaction details">
            <h2>Transaction details</h2>
            <dl className="details">
              {detailRows(detail).map(([label, value]) => <div key={label}><dt>{label}</dt><dd>{value}</dd></div>)}
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
