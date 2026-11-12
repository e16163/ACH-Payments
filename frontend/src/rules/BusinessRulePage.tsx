import { useCallback, useEffect, useState } from 'react'
import { api, ApiError } from '../api'

export type BusinessRule = {
  ruleID: number; systemName: string; ruleName: string; ruleDesc: string | null; pseudoCode: string; ruleJson: string
  lastModTm: string; lastModBy: string
}
type App = { systemName: string; appName: string }

/** MySQL stores JSON in its own key order and spacing; show it indented. */
export const prettyJson = (json: string) => {
  try {
    return JSON.stringify(JSON.parse(json), null, 2)
  } catch {
    return json
  }
}

/** Ba (2026-11-12): ACH Business Rules Management, beside System Configuration, with the same app list. */
export default function BusinessRulePage() {
  const [apps, setApps] = useState<App[]>([])
  const [systemName, setSystemName] = useState('')
  const [rules, setRules] = useState<BusinessRule[]>([])
  const [query, setQuery] = useState('')
  const [editing, setEditing] = useState<BusinessRule | 'new' | null>(null)
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
    api<BusinessRule[]>(`/ach-business-rules?systemName=${encodeURIComponent(systemName)}`).then(setRules).catch(showError)
  }, [systemName])

  useEffect(load, [load])

  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), 4000)
    return () => clearTimeout(timer)
  }, [toast])

  if (error) return <p className="page-error">{error}</p>

  const q = query.trim().toLowerCase()
  const shown = rules.filter((r) => !q || [r.ruleName, r.ruleDesc ?? '', r.pseudoCode, r.ruleJson].some((f) => f.toLowerCase().includes(q)))

  return (
    <section>
      <h2>ACH Business Rules Management</h2>
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
        <input type="search" placeholder="Search rules" aria-label="Search rules" value={query} onChange={(e) => setQuery(e.target.value)} />
        <button className="primary" disabled={!systemName} onClick={() => setEditing('new')}>
          Add New Rule
        </button>
      </div>

      <table aria-label="ACH business rules">
        <thead>
          <tr>
            <th>ACH Business Rule Name</th>
            <th>Business Description</th>
            <th>Pseudo Code</th>
            <th>JSON Expression Rule</th>
            <th>Last modified</th>
            <th>Modified by</th>
            <th />
          </tr>
        </thead>
        <tbody>
          {shown.length === 0 && (
            <tr>
              <td className="empty" colSpan={7}>
                {query ? 'No rules match your search.' : 'No business rules for this app yet.'}
              </td>
            </tr>
          )}
          {shown.map((r) => (
            <tr key={r.ruleID}>
              <td>{r.ruleName}</td>
              <td>{r.ruleDesc}</td>
              <td><pre className="code value">{r.pseudoCode}</pre></td>
              <td><pre className="code value">{prettyJson(r.ruleJson)}</pre></td>
              <td>{r.lastModTm.replace('T', ' ')}</td>
              <td>{r.lastModBy}</td>
              <td>
                <button onClick={() => setEditing(r)}>Edit</button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      {editing && (
        <RuleModal
          systemName={systemName}
          appName={apps.find((a) => a.systemName === systemName)?.appName ?? systemName}
          existing={editing === 'new' ? null : editing}
          onCancel={() => setEditing(null)}
          onSaved={() => {
            setEditing(null)
            setToast('Business rule saved. It applies from the next processing window.')
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

const SAMPLE = `{
  "when": { "all": [
    { "field": "companyID", "op": "eq", "value": "23416789" },
    { "field": "creditIndicator", "op": "eq", "value": true }
  ] },
  "then": [ { "set": "earlyPosting", "value": true } ]
}`

/** Add or edit one rule. The server checks the JSON rule (fields, ops, shape) and says what's wrong. */
function RuleModal(props: { systemName: string; appName: string; existing: BusinessRule | null; onSaved: () => void; onCancel: () => void }) {
  const { systemName, appName, existing, onSaved, onCancel } = props
  const [ruleName, setRuleName] = useState(existing?.ruleName ?? '')
  const [ruleDesc, setRuleDesc] = useState(existing?.ruleDesc ?? '')
  const [pseudoCode, setPseudoCode] = useState(existing?.pseudoCode ?? '')
  const [ruleJson, setRuleJson] = useState(existing ? prettyJson(existing.ruleJson) : SAMPLE)
  const [serverError, setServerError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  let jsonError: string | null = null
  try {
    JSON.parse(ruleJson)
  } catch {
    jsonError = 'The JSON rule isn\'t valid JSON.'
  }
  const blocked = !ruleName.trim() || !pseudoCode.trim() || Boolean(jsonError) || saving

  async function save() {
    setSaving(true)
    setServerError(null)
    try {
      await api(existing ? `/ach-business-rules/${existing.ruleID}` : '/ach-business-rules', {
        method: existing ? 'PUT' : 'POST',
        body: JSON.stringify({ systemName, ruleName, ruleDesc, pseudoCode, ruleJson }),
      })
      onSaved()
    } catch (e) {
      setServerError(e instanceof Error ? e.message : String(e))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="overlay">
      <div className="modal wide" role="dialog" aria-label={existing ? 'Edit business rule' : 'Add new business rule'}>
        <h2>{existing ? `Edit ${existing.ruleName}` : `Add New Business Rule to ${appName}`}</h2>
        <label>
          ACH Business Rule Name
          <input value={ruleName} maxLength={100} onChange={(e) => setRuleName(e.target.value)} />
        </label>
        <label>
          Business Description
          <input value={ruleDesc} maxLength={500} onChange={(e) => setRuleDesc(e.target.value)} />
        </label>
        <label>
          Pseudo Code
          <textarea value={pseudoCode} rows={3} maxLength={2000} className="code" onChange={(e) => setPseudoCode(e.target.value)} />
        </label>
        <label>
          JSON Expression Rule
          <textarea value={ruleJson} rows={9} className={`code ${jsonError ? 'invalid' : ''}`} onChange={(e) => setRuleJson(e.target.value)} />
        </label>
        {jsonError && <p className="field-error">{jsonError}</p>}
        <p className="hint">
          "when" takes "all" or "any" of {'{field, op, value}'}; op is eq, ne, in, gt, gte, lt or lte. "then" sets results, such as earlyPosting.
        </p>
        {serverError && <p className="field-error">{serverError}</p>}
        <div className="actions">
          <button onClick={onCancel}>Cancel</button>
          <button className="primary" disabled={blocked} onClick={save}>
            Save Changes
          </button>
        </div>
      </div>
    </div>
  )
}
