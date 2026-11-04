import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'
import ParamModal from './ParamModal'

export type AppParam = {
  systemName: string
  paramName: string
  paramDesc: string | null
  paramRawType: string
  paramRawValue: string
  lastModTm: string
  lastModBy: string
}

type App = { systemName: string; appName: string }

const SAVED_MESSAGE = 'Parameter updated successfully. Changes applied to system cache.'

/** System Configuration dashboard (UC-SYS-PARAM-01). */
export default function ParameterPage() {
  const [apps, setApps] = useState<App[]>([])
  const [systemName, setSystemName] = useState('')
  const [params, setParams] = useState<AppParam[]>([])
  const [editing, setEditing] = useState<AppParam | 'new' | null>(null)
  const [toast, setToast] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  const showError = (e: unknown) =>
    setError(e instanceof ApiError && e.status === 403 ? 'You need the System Administrator role to use this page.' : String(e))

  useEffect(() => {
    api<App[]>('/params/systems')
      .then((list) => {
        setApps(list)
        setSystemName(list[0]?.systemName ?? '')
      })
      .catch(showError)
  }, [])

  const load = useCallback(() => {
    if (!systemName) return
    api<AppParam[]>(`/params?systemName=${encodeURIComponent(systemName)}`).then(setParams).catch(showError)
  }, [systemName])

  useEffect(load, [load])

  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), 4000)
    return () => clearTimeout(timer)
  }, [toast])

  if (error) return <p className="page-error">{error}</p>

  return (
    <section>
      <h2>System Configuration</h2>
      <div className="toolbar">
        <label>
          App{' '}
          <select value={systemName} onChange={(e) => setSystemName(e.target.value)}>
            {apps.map((a) => (
              <option key={a.systemName} value={a.systemName}>
                {a.appName} ({a.systemName})
              </option>
            ))}
          </select>
        </label>
        <button className="primary" disabled={!systemName} onClick={() => setEditing('new')}>
          Add New Parameter
        </button>
      </div>

      <table>
        <thead>
          <tr>
            <th>Parameter</th>
            <th>Description</th>
            <th>Type</th>
            <th>Value</th>
            <th>Last modified</th>
            <th>Modified by</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {params.map((p) => (
            <tr key={p.paramName}>
              <td className="code">{p.paramName}</td>
              <td>{p.paramDesc}</td>
              <td>{p.paramRawType}</td>
              <td className="code value">{p.paramRawValue}</td>
              <td>{p.lastModTm.replace('T', ' ')}</td>
              <td>{p.lastModBy}</td>
              <td>
                <button onClick={() => setEditing(p)}>Edit</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {editing && (
        <ParamModal
          systemName={systemName}
          appName={apps.find((a) => a.systemName === systemName)?.appName ?? systemName}
          existing={editing === 'new' ? null : editing}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            setToast(SAVED_MESSAGE)
            load()
          }}
        />
      )}

      {toast && (
        <div className="toast" role="status">
          {toast}
        </div>
      )}
    </section>
  )
}
