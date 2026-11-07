import { useMemo, useState } from 'react'
import achOriginationYaml from './ach-origination-system.yaml?raw'
import achReceivingYaml from './ach-receiving-system.yaml?raw'
import coreBankingYaml from './banking-core-services.yaml?raw'
import { parseSpec, type Field } from './spec'

const PAGES = {
  core: { title: 'Open Core Banking API', specs: [{ name: 'Banking Core Services', yaml: coreBankingYaml }] },
  ach: {
    title: 'ACH Payments API',
    specs: [{ name: 'ACH Origination API', yaml: achOriginationYaml }, { name: 'ACH Receiving API', yaml: achReceivingYaml }],
  },
}

/** Shows Ba's OpenAPI specs: pick an API (when there is more than one), then an operation, and see its inputs and outputs. */
export default function ApiSpecPage({ page }: { page: keyof typeof PAGES }) {
  const { title, specs } = PAGES[page]
  const [specIndex, setSpecIndex] = useState(0)
  const [opIndex, setOpIndex] = useState(0)
  const spec = useMemo(() => parseSpec(specs[specIndex].yaml), [specs, specIndex])
  const op = spec.operations[opIndex]

  return (
    <>
      <h2>{title}</h2>
      <div className="toolbar api-toolbar">
        {specs.length > 1 && (
          <label>
            API
            <select value={specIndex} onChange={(e) => { setSpecIndex(Number(e.target.value)); setOpIndex(0) }}>
              {specs.map((s, i) => <option key={s.name} value={i}>{s.name}</option>)}
            </select>
          </label>
        )}
        <label>
          Operation
          <select value={opIndex} onChange={(e) => setOpIndex(Number(e.target.value))}>
            {spec.operations.map((o, i) => <option key={`${o.method} ${o.path}`} value={i}>{o.summary}</option>)}
          </select>
        </label>
      </div>
      <p className="notice">
        <strong>{spec.title}</strong> <span className="code">v{spec.version}</span>. {spec.description}
      </p>

      <h3 className="section">{op.summary}</h3>
      <p><span className="badge method">{op.method}</span> <span className="code">{op.path}</span></p>
      <p>{op.description}</p>

      <h3 className="section">Input parameters</h3>
      {op.parameters.length > 0
        ? <FieldTable label="Parameters" fields={op.parameters} />
        : <p className="empty-note">No path or query parameters.</p>}

      <h3 className="section">Request body</h3>
      {op.requestBody ? (
        <>
          <p className="code">application/json: {op.requestBody.type}{op.requestBody.required && ' (required)'}</p>
          <FieldTable label="Request body" fields={op.requestBody.fields} />
        </>
      ) : <p className="empty-note">No request body.</p>}

      <h3 className="section">Responses</h3>
      {op.responses.map((r) => (
        <div key={r.status} className="api-response">
          <p><span className="badge method">{r.status}</span> {r.description}</p>
          {r.body && (
            <>
              <p className="code">application/json: {r.body.type}</p>
              {r.body.fields.length > 0 && <FieldTable label={`Response ${r.status}`} fields={r.body.fields} />}
            </>
          )}
        </div>
      ))}
    </>
  )
}

function FieldTable({ label, fields }: { label: string; fields: (Field & { in?: string })[] }) {
  const showIn = fields.some((f) => f.in)
  return (
    <div className="api-table-scroll">
      <table aria-label={label}>
        <thead>
          <tr>
            <th>Name</th>
            {showIn && <th>In</th>}
            <th>Type</th>
            <th>Required</th>
            <th>Description</th>
            <th>Example</th>
          </tr>
        </thead>
        <tbody>
          {fields.map((f, i) => (
            <tr key={i}>
              <td className="code" style={{ paddingLeft: 16 + f.depth * 20 }}>{f.depth > 0 && '↳ '}{f.name}</td>
              {showIn && <td>{f.in}</td>}
              <td>
                {f.type}
                {f.enum && <div className="field-note">One of: {f.enum.join(', ')}</div>}
                {f.default !== undefined && <div className="field-note">Default: {f.default}</div>}
              </td>
              <td>{f.required ? 'Yes' : 'No'}</td>
              <td>{f.description}</td>
              <td className="code api-example">{f.example}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
