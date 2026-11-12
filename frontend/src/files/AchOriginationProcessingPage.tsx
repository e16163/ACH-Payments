import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { formatDate } from '../member/rules'

// Every value on this page comes from the database: ACHOrigSchedule, ACHOrigWindowRun, ACHOriginatorTransfer, ACHOrigFile, ACHOrigBatch.
type WindowRun = {
  window_id: string; extracted: number; account_rejected: number; limit_rejected: number; ofac_flagged: number
  hold_rejected: number; ready_to_post: number; run_by: string; started_at: string
}
type Schedule = { schedule_id: number; window_time: string; window_name: string; last_run: WindowRun | null }
type Today = { business_date: string; schedules: Schedule[]; waiting_for_compliance: number }
type Ready = { transfer_id: string; recipient_name: string; routing_number: string; amount: number; effective_dt: string; ready_at: string }
type Batch = {
  batch_id: string; batch_no: number; company_name: string; std_entry_code: string; company_entry_desc: string
  effective_dt: string; entry_count: number; total_credit_amt: number
}
type FileRow = {
  file_id: string; file_name: string; file_header_tm: string; total_credit_count: number; total_credit_amt: number
  fed_status: string; send_tm: string | null; batches: Batch[]
}
type SendResult = { fileID: string | null; fedStatus: string | null; message: string }
type FileContent = { fileName: string; content: string }

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' })
const time = (s: string) => s.replace('T', ' ').slice(11, 16)
const longDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })

function saveFile(f: FileContent) {
  const url = URL.createObjectURL(new Blob([f.content], { type: 'text/plain' }))
  const a = document.createElement('a')
  a.href = url
  a.download = f.fileName
  a.click()
  URL.revokeObjectURL(url)
}

/**
 * Ba's ACH Origination Processing (2026-11-12): today's date, pick a schedule, see the transactions it made ready for the Fed,
 * then Send to the Fed, which creates the ACH origination file for them.
 */
export default function AchOriginationProcessingPage() {
  const [today, setToday] = useState<Today | null>(null)
  const [selected, setSelected] = useState<number | null>(null)
  const [ready, setReady] = useState<Ready[]>([])
  const [files, setFiles] = useState<FileRow[]>([])
  const [viewing, setViewing] = useState<FileContent | null>(null)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    const t = await api<Today>('/v1/ach/processing/windows')
    setToday(t)
    // Start on the latest window that has run today, else the first one.
    setSelected((s) => s ?? ([...t.schedules].reverse().find((x) => x.last_run) ?? t.schedules[0])?.schedule_id ?? null)
  }, [])

  const loadSchedule = useCallback(async (id: number) => {
    const [r, f] = await Promise.all([
      api<Ready[]>(`/v1/ach/processing/ready?schedule_id=${id}`),
      api<FileRow[]>(`/v1/ach/processing/files?schedule_id=${id}`),
    ])
    setReady(r)
    setFiles(f)
  }, [])

  useEffect(() => {
    load().catch((e) => setError(String(e.message ?? e)))
  }, [load])

  useEffect(() => {
    if (selected !== null) loadSchedule(selected).catch((e) => setError(String(e.message ?? e)))
  }, [selected, loadSchedule])

  async function act(action: () => Promise<string>) {
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      setNotice(await action())
      await load()
      if (selected !== null) await loadSchedule(selected)
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const schedule = today?.schedules.find((s) => s.schedule_id === selected)

  const processWindow = () => act(async () => {
    const r = await api<{ message: string }>('/v1/ach/processing/trigger-window', {
      method: 'POST', body: JSON.stringify({ window_time: schedule!.window_time }),
    })
    return r.message
  })

  const sendToFed = () => act(async () => {
    const r = await api<SendResult>('/v1/ach/processing/send-to-fed', { method: 'POST', body: JSON.stringify({ schedule_id: selected }) })
    return r.message
  })

  const resend = (fileID: string) => act(async () => {
    const r = await api<{ fedStatus: string }>(`/eod/files/${encodeURIComponent(fileID)}/resend`, { method: 'POST' })
    return `${fileID}: ${r.fedStatus}`
  })

  async function open(fileID: string, then: (f: FileContent) => void) {
    setError(null)
    try {
      then(await api<FileContent>(`/eod/files/${encodeURIComponent(fileID)}/content`))
    } catch (e) {
      setError(e instanceof ApiError ? e.message : String(e))
    }
  }

  if (!today) return error ? <p className="page-error">{error}</p> : null
  const run = schedule?.last_run

  return (
    <>
      <h2>ACH Origination Processing</h2>
      <p className="hint">{longDate(today.business_date)}. Anything ready that isn't sent from a schedule goes in the 5:00 PM file.</p>

      {files.filter((f) => f.fed_status === 'ERROR').map((f) => (
        <p className="page-error" key={f.file_id}>
          Operations alert: {f.file_id} could not be sent to the Fed after 3 attempts. Check the connection, then resend it.
        </p>
      ))}
      {today.waiting_for_compliance > 0 && (
        <p className="page-error">Compliance alert: {today.waiting_for_compliance} transfer(s) flagged by OFAC, waiting for review.</p>
      )}
      {error && <p className="page-error">{error}</p>}
      {notice && <p className="notice" role="status">{notice}</p>}

      <div className="schedule" role="group" aria-label="Schedule">
        {today.schedules.map((s) => (
          <button key={s.schedule_id} className={s.schedule_id === selected ? 'primary' : ''} aria-pressed={s.schedule_id === selected}
            onClick={() => { setNotice(null); setSelected(s.schedule_id) }}>
            {s.window_name}{s.last_run ? ' ✓' : ''}
          </button>
        ))}
      </div>

      {schedule && (
        <>
          <h3 className="section">{schedule.window_name}: steps 1–7</h3>
          {run ? (
            <dl className="steps">
              <div><dt>1. Extracted</dt><dd>{run.extracted}</dd></div>
              <div><dt>2. Account rejected</dt><dd>{run.account_rejected}</dd></div>
              <div><dt>3. Over a limit</dt><dd>{run.limit_rejected}</dd></div>
              <div><dt>4–5. OFAC flagged, to compliance</dt><dd>{run.ofac_flagged}</dd></div>
              <div><dt>6. Hold failed</dt><dd>{run.hold_rejected}</dd></div>
              <div><dt>7. Ready to post</dt><dd>{run.ready_to_post}</dd></div>
            </dl>
          ) : <p className="empty">Not processed yet today.</p>}
          <div className="toolbar">
            {run && <span className="hint">Processed at {time(run.started_at)} by {run.run_by} ({run.window_id})</span>}
            <button disabled={busy} onClick={processWindow}>{run ? 'Process again' : 'Process window'}</button>
          </div>

          <h3 className="section">Ready to be sent to the Fed ({ready.length})</h3>
          {ready.length === 0 ? <p className="empty">No transactions from this schedule are waiting.</p> : (
            <table aria-label="Ready to be sent to the Fed">
              <thead>
                <tr><th>Transfer</th><th>Recipient</th><th>Routing</th><th>Amount</th><th>Effective Date</th><th>Ready At</th></tr>
              </thead>
              <tbody>
                {ready.map((r) => (
                  <tr key={r.transfer_id}>
                    <td className="code">{r.transfer_id}</td>
                    <td>{r.recipient_name}</td>
                    <td className="code">{r.routing_number}</td>
                    <td>{usd(r.amount)}</td>
                    <td>{formatDate(r.effective_dt)}</td>
                    <td>{time(r.ready_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          <div className="toolbar">
            <button className="primary" disabled={busy || ready.length === 0} onClick={sendToFed}>Send to the Fed</button>
          </div>

          <h3 className="section">ACH origination files from this schedule today</h3>
          {files.length === 0 ? <p className="empty">No files yet.</p> : (
            <table aria-label="ACH origination files">
              <thead>
                <tr><th>File / Batch</th><th>Created / Effective</th><th>SEC</th><th>Entry Desc</th><th>Entries</th><th>Credits</th><th>Fed Status</th><th /></tr>
              </thead>
              <tbody>
                {files.flatMap((f) => [
                  <tr key={f.file_id}>
                    <td className="code">{f.file_name}</td>
                    <td>{f.file_header_tm.slice(0, 5)}</td>
                    <td /><td />
                    <td>{f.total_credit_count}</td>
                    <td>{usd(f.total_credit_amt)}</td>
                    <td>{f.fed_status}{f.send_tm ? ` ${time(f.send_tm)}` : ''}</td>
                    <td className="row-actions">
                      <button onClick={() => open(f.file_id, setViewing)}>View</button>
                      <button onClick={() => open(f.file_id, saveFile)}>Download</button>
                      {f.fed_status === 'ERROR' && <button disabled={busy} onClick={() => resend(f.file_id)}>Resend</button>}
                    </td>
                  </tr>,
                  ...f.batches.map((b) => (
                    <tr key={b.batch_id} className="sub">
                      <td className="code">Batch {b.batch_no}: {b.company_name}</td>
                      <td>{formatDate(b.effective_dt)}</td>
                      <td>{b.std_entry_code}</td>
                      <td>{b.company_entry_desc}</td>
                      <td>{b.entry_count}</td>
                      <td>{usd(b.total_credit_amt)}</td>
                      <td /><td />
                    </tr>
                  )),
                ])}
              </tbody>
            </table>
          )}
        </>
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
