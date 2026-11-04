import { useState } from 'react'
import { api } from '../api'
import type { AppParam } from './ParameterPage'
import { PARAM_TYPES, validateDesc, validateName, validateValue } from './validation'

type Props = {
  systemName: string
  appName: string
  existing: AppParam | null // null = "Add New Parameter"
  onSaved: () => void
  onCancel: () => void
}

/** Edit / create modal (UC-SYS-PARAM-01 steps 3–5). lastModTm and lastModBy are never editable. */
export default function ParamModal({ systemName, appName, existing, onSaved, onCancel }: Props) {
  const [paramName, setParamName] = useState(existing?.paramName ?? '')
  const [paramDesc, setParamDesc] = useState(existing?.paramDesc ?? '')
  const [paramRawType, setParamRawType] = useState(existing?.paramRawType ?? 'STRING')
  const [paramRawValue, setParamRawValue] = useState(existing?.paramRawValue ?? '')
  const [serverError, setServerError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const nameError = existing ? null : validateName(paramName)
  const descError = validateDesc(paramDesc)
  const valueError = validateValue(paramRawType, paramRawValue)
  const blocked = Boolean(nameError || descError || valueError) || saving

  async function save() {
    setSaving(true)
    setServerError(null)
    try {
      await api(`/params/${encodeURIComponent(systemName)}/${encodeURIComponent(paramName.trim())}`, {
        method: 'PUT',
        body: JSON.stringify({ paramDesc, paramRawType, paramRawValue }),
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
      <div className="modal" role="dialog" aria-label={existing ? 'Edit parameter' : 'Add new parameter'}>
        <h2>{existing ? `Edit ${existing.paramName}` : `Add New Parameter to ${appName}`}</h2>

        <label>
          Parameter name
          <input
            value={paramName}
            disabled={Boolean(existing)}
            onChange={(e) => setParamName(e.target.value)}
            className={nameError && paramName ? 'invalid' : ''}
          />
        </label>
        {nameError && paramName && <p className="field-error">{nameError}</p>}

        <label>
          Description
          <input value={paramDesc} onChange={(e) => setParamDesc(e.target.value)} className={descError ? 'invalid' : ''} />
        </label>
        {descError && <p className="field-error">{descError}</p>}

        <label>
          Type
          <select value={paramRawType} onChange={(e) => setParamRawType(e.target.value)}>
            {PARAM_TYPES.map((t) => (
              <option key={t}>{t}</option>
            ))}
          </select>
        </label>

        <label>
          Value
          <ValueInput type={paramRawType} value={paramRawValue} invalid={Boolean(valueError)} onChange={setParamRawValue} />
        </label>
        {valueError && <p className="field-error">{valueError}</p>}

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

/** The value input changes with the parameter type (spec: dynamic input rendering). */
function ValueInput(props: { type: string; value: string; invalid: boolean; onChange: (v: string) => void }) {
  const { type, value, invalid, onChange } = props
  const className = invalid ? 'invalid' : ''
  switch (type) {
    case 'BOOLEAN':
      return (
        <select value={value} onChange={(e) => onChange(e.target.value)} className={className}>
          {value !== 'TRUE' && value !== 'FALSE' && <option value={value}>Choose…</option>}
          <option>TRUE</option>
          <option>FALSE</option>
        </select>
      )
    case 'INTEGER':
    case 'DECIMAL':
      return (
        <input
          value={value}
          inputMode={type === 'INTEGER' ? 'numeric' : 'decimal'}
          onChange={(e) => onChange(e.target.value)}
          className={className}
        />
      )
    case 'JSON':
      return (
        <textarea value={value} rows={5} className={`code ${className}`} onChange={(e) => onChange(e.target.value)} />
      )
    default:
      return <input value={value} onChange={(e) => onChange(e.target.value)} className={className} />
  }
}
