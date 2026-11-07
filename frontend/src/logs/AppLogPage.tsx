import { useEffect, useState } from 'react'
import { api } from '../api'

type Row = { logAppId: number; systemID: string | null; logLevel: string; logMesg: string; logTm: string }
type Detail = Row & { logExceptionData: string | null }
type Page = { items: Row[]; total: number; page: number; limit: number }
type App = { systemName: string; appName: string }

const LIMIT = 20
const LEVELS = ['ALL', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL']
const SUMMARY_LENGTH = 100
const SEARCH_DELAY_MS = 250
// Sign-ins, failed sign-ins and access-denied rows belong to no single app
const SYSTEM_APP: App = { systemName: 'ACH_SYS', appName: 'ACH Payments System' }

/** YYYY-MM-DD HH:mm:ss.SSS. The server drops trailing zeros from the milliseconds. */
export function logTime(s: string): string {
  const [time, millis = ''] = s.split('.')
  return `${time.replace('T', ' ')}.${millis.padEnd(3, '0').slice(0, 3)}`
}

export function summary(message: string): string {
  return message.length > SUMMARY_LENGTH ? `${message.slice(0, SUMMARY_LENGTH)}…` : message
}

function LevelBadge({ level }: { level: string }) {
  return <span className={`badge level-${level.toLowerCase()}`}>{level}</span>
}

/** UC-SYS-LOG-01 phase 2: System Audit & App Logs. */
export default function AppLogPage() {
  const [apps, setApps] = useState<App[]>([])
  const [systemID, setSystemID] = useState('')
  const [level, setLevel] = useState('ALL')
  const [searchBox, setSearchBox] = useState('')
  const [search, setSearch] = useState('')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [page, setPage] = useState(1)
  const [data, setData] = useState<Page | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [detail, setDetail] = useState<Detail | null>(null)
  const [copied, setCopied] = useState<string | null>(null)

  /** Any new filter starts again from page 1. */
  const filter = (set: (value: string) => void) => (value: string) => {
    set(value)
    setPage(1)
  }

  // The app list, as in System Configuration, plus the system's own logs
  useEffect(() => {
    api<App[]>('/params/systems')
      .then((list) => {
        setApps([...list, SYSTEM_APP])
        setSystemID(list[0]?.systemName ?? SYSTEM_APP.systemName)
      })
      .catch((e) => setError(String(e.message ?? e)))
  }, [])

  // Only typing waits, so each keystroke doesn't send a query.
  useEffect(() => {
    const timer = setTimeout(() => filter(setSearch)(searchBox), SEARCH_DELAY_MS)
    return () => clearTimeout(timer)
  }, [searchBox])

  useEffect(() => {
    if (!systemID) return
    const query = new URLSearchParams({ page: String(page), limit: String(LIMIT), level, systemID })
    if (search.trim()) query.set('search', search.trim())
    if (from) query.set('from', from)
    if (to) query.set('to', to)
    let current = true
    api<Page>(`/v1/admin/logs?${query}`)
      .then((d) => {
        if (current) {
          setData(d)
          setError(null)
        }
      })
      .catch((e) => {
        if (current) {
          setData(null)
          setError(String(e.message ?? e))
        }
      })
    return () => {
      current = false
    }
  }, [page, systemID, level, search, from, to])

  async function inspect(logAppId: number) {
    try {
      setCopied(null)
      setError(null)
      setDetail(await api<Detail>(`/v1/admin/logs/${logAppId}`))
    } catch (e) {
      setError(String((e as Error).message ?? e))
    }
  }

  async function copy(text: string) {
    try {
      await navigator.clipboard.writeText(text)
      setCopied('Copied.')
    } catch {
      setCopied("Couldn't copy. Select the text instead.")
    }
  }

  const pages = data ? Math.ceil(data.total / LIMIT) : 0

  return (
    <>
      <h2>System Audit &amp; App Logs</h2>
      <div className="toolbar log-toolbar">
        <label>
          App
          <select value={systemID} onChange={(e) => filter(setSystemID)(e.target.value)}>
            {apps.map((a) => (
              <option key={a.systemName} value={a.systemName}>
                {a.appName} ({a.systemName})
              </option>
            ))}
          </select>
        </label>
        <label>
          Level
          <select value={level} onChange={(e) => filter(setLevel)(e.target.value)}>
            {LEVELS.map((l) => <option key={l} value={l}>{l}</option>)}
          </select>
        </label>
        <label>
          Search
          <input type="search" value={searchBox} onChange={(e) => setSearchBox(e.target.value)} placeholder="Search messages" />
        </label>
        <label>
          From
          <input type="date" value={from} onChange={(e) => filter(setFrom)(e.target.value)} />
        </label>
        <label>
          To
          <input type="date" value={to} onChange={(e) => filter(setTo)(e.target.value)} />
        </label>
      </div>

      {error && <p className="page-error">{error}</p>}
      {data?.total === 0 && <p className="notice" role="status">No system logs found matching the selected criteria.</p>}
      {data && data.total > 0 && (
        <>
          <table aria-label="System logs">
            <thead>
              <tr><th>Timestamp</th><th>Level</th><th>System ID</th><th>Message Summary</th><th>Action</th></tr>
            </thead>
            <tbody>
              {data.items.map((row) => (
                <tr key={row.logAppId}>
                  <td className="code log-time">{logTime(row.logTm)}</td>
                  <td><LevelBadge level={row.logLevel} /></td>
                  <td>{row.systemID ?? '—'}</td>
                  <td title={row.logMesg}>{summary(row.logMesg)}</td>
                  <td className="row-actions"><button onClick={() => inspect(row.logAppId)}>View Details</button></td>
                </tr>
              ))}
            </tbody>
          </table>
          <div className="pager">
            <button disabled={page <= 1} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page} of {pages} ({data.total} logs)</span>
            <button disabled={page >= pages} onClick={() => setPage(page + 1)}>Next</button>
          </div>
        </>
      )}

      {detail && (
        <div className="overlay">
          <div className="modal wide" role="dialog" aria-label="Log details">
            <h2>Log details</h2>
            <dl className="details">
              <div><dt>Timestamp</dt><dd>{logTime(detail.logTm)}</dd></div>
              <div><dt>Level</dt><dd><LevelBadge level={detail.logLevel} /></dd></div>
              <div><dt>System ID</dt><dd>{detail.systemID ?? '—'}</dd></div>
            </dl>
            <p className="log-message">{detail.logMesg}</p>
            {detail.logExceptionData
              ? <pre className="stack-trace">{detail.logExceptionData}</pre>
              : <p className="notice">No stack trace for this entry.</p>}
            <div className="actions">
              {copied && <span role="status">{copied}</span>}
              {detail.logExceptionData && <button onClick={() => copy(detail.logExceptionData!)}>Copy stack trace</button>}
              <button className="primary" onClick={() => setDetail(null)}>Close</button>
            </div>
          </div>
        </div>
      )}
    </>
  )
}
