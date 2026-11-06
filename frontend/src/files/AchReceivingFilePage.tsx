import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { formatDate } from '../member/rules'

export type ReceivedFile = {
  fileID: string; fileName: string; fileHeaderDt: string; fileHeaderTm: string; fedStatus: string; processStatus: string
  entryCount: number; totalCreditAmt: number; totalDebitAmt: number
}
type Result = { fileID: string; fileName: string; processStatus: string; message: string }

const SAMPLES = [
  ['PAYROLL', 'Sample 1: payroll (valid)'],
  ['RETAIL', 'Sample 2: retail (valid)'],
  ['OUT_OF_BALANCE', 'Sample 1 with a wrong batch total'],
  ['TAMPERED', 'Sample 1 changed in transit'],
]
const FAILED: Record<string, string> = {
  REJECTED_SIG_FAIL: 'Security Operations alert: signature verification failed, and the file was quarantined.',
  OUT_OF_BALANCE: 'Operations alert: control totals did not match. The file was quarantined and a rejection notice was sent to the Fed.',
  DUPLICATE: 'Operations alert: this file was already received, so it was not processed.',
  REJECTED: 'Operations alert: the file is not a valid ACH file for this bank, and it was quarantined.',
}

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' })

/** UC-ACH-RCV-01: the files the Fed sends us each morning, by day, newest first. */
export default function AchReceivingFilePage({ transactionsRoute }: { transactionsRoute: string }) {
  const [files, setFiles] = useState<ReceivedFile[] | null>(null)
  const [sample, setSample] = useState(SAMPLES[0][0])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(() => api<ReceivedFile[]>('/v1/ach/receiving/files').then(setFiles), [])

  useEffect(() => {
    load().catch((e) => setError(String(e.message ?? e)))
  }, [load])

  async function receive() {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const results = await api<Result[]>('/v1/ach/receiving/files/receive', { method: 'POST', body: JSON.stringify({ sample }) })
      setNotice(results.map((r) => `${r.fileID}: ${r.processStatus}`).join(', ') || 'No file was processed.')
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  if (!files) return error ? <p className="page-error">{error}</p> : null
  const alerts = files.filter((f) => FAILED[f.processStatus])

  return (
    <>
      <h2>ACH Receiving File</h2>
      <p className="hint">Files from the Fed are picked up by themselves at 6:00 AM (Eastern) on each banking day.</p>

      {alerts.map((f) => <p className="page-error" key={f.fileID}>{f.fileID}: {FAILED[f.processStatus]}</p>)}
      {error && <p className="page-error">{error}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}

      <div className="toolbar">
        <label>
          Simulate a file from the Fed
          <select value={sample} onChange={(e) => setSample(e.target.value)}>
            {SAMPLES.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
          </select>
        </label>
        <button className="primary" disabled={busy} onClick={receive}>Receive file</button>
      </div>

      <h3 className="section">Files</h3>
      {files.length === 0 ? <p className="empty">No files yet.</p> : (
        <table aria-label="Files">
          <thead>
            <tr><th>File</th><th>Date</th><th>Time</th><th>Status</th><th>Entries</th><th>Credits</th><th>Debits</th><th /></tr>
          </thead>
          <tbody>
            {files.map((f) => (
              <tr key={f.fileID}>
                <td className="code">{f.fileID}</td>
                <td>{formatDate(f.fileHeaderDt)}</td>
                <td>{f.fileHeaderTm.slice(0, 5)}</td>
                <td>{f.processStatus}</td>
                <td>{f.entryCount}</td>
                <td>{usd(f.totalCreditAmt)}</td>
                <td>{usd(f.totalDebitAmt)}</td>
                <td className="row-actions"><a className="button" href={`${transactionsRoute}/${encodeURIComponent(f.fileID)}`}>View transactions</a></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
