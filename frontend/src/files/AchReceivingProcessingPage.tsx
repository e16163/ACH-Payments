import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import { formatDate } from '../member/rules'

export type ReceivedFile = {
  fileID: string; fileName: string; fileHeaderDt: string; fileHeaderTm: string; fedStatus: string; processStatus: string
  entryCount: number; totalCreditAmt: number; totalDebitAmt: number
}
// Every value on this page comes from the database (ACHRecvSchedule, ACHRecvWindowRun, ACHRecvFile, ACHRecvPostingJob),
// except the files still waiting in fed/inbound.
type Schedule = { schedule_id: number; window_time: string; window_name: string }
type WindowRun = {
  execution_id: string; window_time: string; files: number; validated: number; entries: number; matched: number
  auto_corrected: number; manual_review: number; rejected: number; memo_posted: number; run_by: string; started_at: string
}
type PostingFile = {
  posting_job_id: string; total_records_posted: number; total_debit_volume: number; total_credit_volume: number; posting_file_name: string
}
type Today = { business_date: string; banking_day: boolean; schedules: Schedule[]; waiting_files: string[]; runs: WindowRun[]; posting_file: PostingFile | null }

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
const time = (s: string) => s.replace('T', ' ').slice(11, 16)
const longDate = (iso: string) =>
  new Date(`${iso}T12:00:00`).toLocaleDateString('en-US', { weekday: 'long', year: 'numeric', month: 'long', day: 'numeric' })

/**
 * Ba's ACH Receiving Processing (2026-11-12): today's date, the schedule, what each window did (steps 2-6), the files
 * received, and the end-of-day core posting file (step 7).
 */
export default function AchReceivingProcessingPage({ transactionsRoute }: { transactionsRoute: string }) {
  const [today, setToday] = useState<Today | null>(null)
  const [files, setFiles] = useState<ReceivedFile[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [sample, setSample] = useState(SAMPLES[0][0])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    const [t, f] = await Promise.all([api<Today>('/v1/ach/receiving/windows'), api<ReceivedFile[]>('/v1/ach/receiving/files')])
    setToday(t)
    setFiles(f)
    setSelected((s) => s ?? t.runs[t.runs.length - 1]?.window_time ?? t.schedules[0]?.window_time ?? null)
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

  const receive = () => act(async () => {
    const r = await api<{ message: string }>('/v1/ach/receiving/files/receive', { method: 'POST', body: JSON.stringify({ sample }) })
    return r.message
  })

  const processWindow = () => act(async () => {
    const r = await api<{ execution_id: string }>(`/v1/ach/receiving/trigger/${selected!.replace(':', '')}`, { method: 'POST', body: '{}' })
    return `${r.execution_id}: done.`
  })

  const generatePostingFile = () => act(async () => {
    const r = await api<PostingFile>('/v1/ach/receiving/generate-posting-file', {
      method: 'POST', body: JSON.stringify({ business_date: today!.business_date }),
    })
    return `${r.posting_file_name}: ${r.total_records_posted} entries posted.`
  })

  if (!today) return error ? <p className="page-error">{error}</p> : null
  const schedule = today.schedules.find((s) => s.window_time === selected)
  const run = [...today.runs].reverse().find((r) => r.window_time === selected)
  const alerts = files.filter((f) => FAILED[f.processStatus])

  return (
    <>
      <h2>ACH Receiving Processing</h2>
      <p className="hint">{longDate(today.business_date)}. Files from the Fed wait in the inbound folder until a window processes them.</p>
      {!today.banking_day && (
        <p className="notice" role="status">Today isn't a banking day. This is the next business day, view only until it opens.</p>
      )}

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
        <button disabled={busy || !today.banking_day} onClick={receive}>Receive file</button>
      </div>
      <p className="hint">
        Waiting for a window: {today.waiting_files.length === 0 ? 'none' : today.waiting_files.join(', ')}
      </p>

      <div className="schedule" role="group" aria-label="Schedule">
        {today.schedules.map((s) => (
          <button key={s.schedule_id} className={s.window_time === selected ? 'primary' : ''} aria-pressed={s.window_time === selected}
            onClick={() => { setNotice(null); setSelected(s.window_time) }}>
            {s.window_name}{today.runs.some((r) => r.window_time === s.window_time) ? ' ✓' : ''}
          </button>
        ))}
      </div>

      {schedule && (
        <>
          <h3 className="section">{schedule.window_name}: steps 2–6</h3>
          {run ? (
            <dl className="steps">
              <div><dt>Files picked up</dt><dd>{run.files}</dd></div>
              <div><dt>2–3. Validated and extracted</dt><dd>{run.validated} ({run.entries} entries)</dd></div>
              <div><dt>4. Matched / auto-corrected</dt><dd>{run.matched} / {run.auto_corrected}</dd></div>
              <div><dt>4. Manual review</dt><dd>{run.manual_review}</dd></div>
              <div><dt>5. Returned</dt><dd>{run.rejected}</dd></div>
              <div><dt>6. Memo posted</dt><dd>{run.memo_posted}</dd></div>
            </dl>
          ) : <p className="empty">Not processed yet today.</p>}
          <div className="toolbar">
            {run && <span className="hint">Processed at {time(run.started_at)} by {run.run_by} ({run.execution_id})</span>}
            <button className="primary" disabled={busy || !today.banking_day} onClick={processWindow}>{run ? 'Process again' : 'Process window'}</button>
          </div>
        </>
      )}

      <h3 className="section">Files received</h3>
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

      <h3 className="section">7. Core posting file (7:00 PM)</h3>
      {today.posting_file ? (
        <p className="notice">
          {today.posting_file.posting_file_name}: {today.posting_file.total_records_posted} entries,
          debits {usd(today.posting_file.total_debit_volume)}, credits {usd(today.posting_file.total_credit_volume)}.
        </p>
      ) : (
        <div className="toolbar">
          <span className="hint">Not written yet today.</span>
          <button disabled={busy || !today.banking_day} onClick={generatePostingFile}>Generate now</button>
        </div>
      )}
    </>
  )
}
