import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { formatDate } from '../member/rules'

type Item = { transferSerNo: string; recvName: string; amount: number; effectiveDt: string; reason: string | null }
type FileRow = {
  fileID: string; fileName: string; fedStatus: string; fileHeaderDt: string; totalCreditCount: number
  totalCreditAmt: number; sendTm: string | null
}
type Status = { cutoff: string; nextBusinessDate: string; pending: Item[]; leftOut: Item[]; files: FileRow[] }
type RunResult = { fileID: string | null; fedStatus: string | null; message: string }
type FileContent = { fileName: string; content: string }

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' })

function saveFile(f: FileContent) {
  const url = URL.createObjectURL(new Blob([f.content], { type: 'text/plain' }))
  const a = document.createElement('a')
  a.href = url
  a.download = f.fileName
  a.click()
  URL.revokeObjectURL(url)
}

function ItemTable({ items, caption }: { items: Item[]; caption: string }) {
  return (
    <table aria-label={caption}>
      <thead>
        <tr><th>Transfer</th><th>Recipient</th><th>Amount</th><th>Date</th>{caption === 'Left out' && <th>Reason</th>}</tr>
      </thead>
      <tbody>
        {items.map((i) => (
          <tr key={i.transferSerNo}>
            <td className="code">{i.transferSerNo}</td>
            <td>{i.recvName}</td>
            <td>{usd(i.amount)}</td>
            <td>{formatDate(i.effectiveDt)}</td>
            {caption === 'Left out' && <td>{i.reason}</td>}
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/** UC-ACH-EOD-01 E3: the 5 PM file, and what an operator does when it needs a person. */
export default function AchFilesPage() {
  const [status, setStatus] = useState<Status | null>(null)
  const [businessDate, setBusinessDate] = useState('')
  const [viewing, setViewing] = useState<FileContent | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    const s = await api<Status>('/eod/status')
    setStatus(s)
    setBusinessDate(s.nextBusinessDate)
  }, [])

  useEffect(() => {
    load().catch((e) => setError(String(e.message ?? e)))
  }, [load])

  async function act(action: () => Promise<string>) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      setNotice(await action())
      await load()
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const run = () => act(async () => {
    const r = await api<RunResult>('/eod/run', { method: 'POST', body: JSON.stringify({ businessDate }) })
    return r.message
  })

  const resend = (fileID: string) => act(async () => {
    const r = await api<{ fedStatus: string }>(`/eod/files/${encodeURIComponent(fileID)}/resend`, { method: 'POST' })
    return `${fileID}: ${r.fedStatus}`
  })

  const content = (fileID: string) => api<FileContent>(`/eod/files/${encodeURIComponent(fileID)}/content`)

  async function open(fileID: string, then: (f: FileContent) => void) {
    setError(null)
    try {
      then(await content(fileID))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    }
  }

  if (!status) return error ? <p className="page-error">{error}</p> : null
  const failed = status.files.filter((f) => f.fedStatus === 'ERROR')
  const flagged = status.leftOut.filter((i) => i.reason === 'OFAC review')

  return (
    <>
      <h2>ACH Files</h2>
      <p className="hint">The file builds by itself after the {status.cutoff} cutoff (Eastern) on each banking day.</p>

      {failed.map((f) => (
        <p className="page-error" key={f.fileID}>
          Operations alert: {f.fileID} could not be sent to the Fed after 3 attempts. Check the connection, then resend it.
        </p>
      ))}
      {flagged.length > 0 && (
        <p className="page-error">Compliance alert: {flagged.length} flagged by OFAC and left out of the file, waiting for review.</p>
      )}
      {error && <p className="page-error">{error}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}

      <div className="toolbar">
        <label>
          Business date
          <input type="date" value={businessDate} onChange={(e) => setBusinessDate(e.target.value)} />
        </label>
        <button className="primary" disabled={busy || !businessDate} onClick={run}>Run now</button>
      </div>

      <h3 className="section">Waiting for a file ({status.pending.length})</h3>
      {status.pending.length === 0
        ? <p className="empty">No transfers are waiting.</p>
        : <ItemTable items={status.pending} caption="Waiting" />}

      {status.leftOut.length > 0 && (
        <>
          <h3 className="section">Left out ({status.leftOut.length})</h3>
          <ItemTable items={status.leftOut} caption="Left out" />
        </>
      )}

      <h3 className="section">Files</h3>
      {status.files.length === 0 ? <p className="empty">No files yet.</p> : (
        <table aria-label="Files">
          <thead>
            <tr><th>File</th><th>Date</th><th>Status</th><th>Entries</th><th>Credits</th><th>Sent</th><th /></tr>
          </thead>
          <tbody>
            {status.files.map((f) => (
              <tr key={f.fileID}>
                <td className="code">{f.fileName}</td>
                <td>{formatDate(f.fileHeaderDt)}</td>
                <td>{f.fedStatus}</td>
                <td>{f.totalCreditCount}</td>
                <td>{usd(f.totalCreditAmt)}</td>
                <td>{f.sendTm ? f.sendTm.replace('T', ' ').slice(0, 19) : '—'}</td>
                <td className="row-actions">
                  <button onClick={() => open(f.fileID, setViewing)}>View</button>
                  <button onClick={() => open(f.fileID, saveFile)}>Download</button>
                  {f.fedStatus === 'ERROR' && <button disabled={busy} onClick={() => resend(f.fileID)}>Resend</button>}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {viewing && (
        <div className="overlay">
          <div className="modal wide" role="dialog" aria-label={viewing.fileName}>
            <h2>{viewing.fileName}</h2>
            <pre className="nacha">{viewing.content}</pre>
            <div className="actions">
              <button onClick={() => saveFile(viewing)}>Download</button>
              <button className="primary" onClick={() => setViewing(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
