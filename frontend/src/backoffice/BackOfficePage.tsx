import { useEffect, useState } from 'react'
import { api } from '../api'
import type { Screen, Section } from './screens'

type Row = Record<string, unknown>

export function label(column: string): string {
  const words = column.replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/\bnoc\b/i, 'NOC')
  return words[0].toUpperCase() + words.slice(1)
}

export function cell(column: string, value: unknown): string {
  if (value === null || value === undefined || value === '') return '—'
  if (typeof value === 'boolean') return value ? 'Yes' : 'No'
  if (column === 'amount' || column.endsWith('Amt')) return Number(value).toLocaleString('en-US', { style: 'currency', currency: 'USD' })
  if (column.endsWith('Tm')) return String(value).replace('T', ' ').slice(0, 19)
  return String(value)
}

function SectionTable({ section, onSelect }: { section: Section; onSelect: (row: Row) => void }) {
  const [rows, setRows] = useState<Row[] | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    api<Row[]>(section.path).then(setRows).catch(() => setFailed(true))
  }, [section.path])

  return (
    <section>
      <h3 className="section">{section.heading}</h3>
      {failed && <p className="page-error">Couldn't load this list.</p>}
      {rows?.length === 0 && <p className="notice" role="status">Nothing here yet.</p>}
      {rows && rows.length > 0 && (
        <table aria-label={section.heading}>
          <thead>
            <tr>{section.columns.map((c) => <th key={c}>{label(c)}</th>)}<th>Action</th></tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i}>
                {section.columns.map((c) => <td key={c}>{cell(c, row[c])}</td>)}
                <td className="row-actions"><button className="primary" onClick={() => onSelect(row)}>Select</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}

/** One back-office screen: its lists, and every field of the row you select. Read-only until the use cases arrive. */
export default function BackOfficePage({ screen }: { screen: Screen }) {
  const [detail, setDetail] = useState<Row | null>(null)

  return (
    <>
      <h2>{screen.title}</h2>
      {screen.sections.map((s) => <SectionTable key={s.path} section={s} onSelect={setDetail} />)}
      {detail && (
        <div className="overlay">
          <div className="modal wide" role="dialog" aria-label="Details">
            <h2>Details</h2>
            <dl className="details">
              {Object.entries(detail).map(([k, v]) => <div key={k}><dt>{label(k)}</dt><dd>{cell(k, v)}</dd></div>)}
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
