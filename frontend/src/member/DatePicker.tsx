import { useState } from 'react'
import { formatDate, isSelectableDate } from './rules'

type Props = { value: string; earliest: string; holidays: ReadonlySet<string>; onChange: (iso: string) => void }

const pad = (n: number) => String(n).padStart(2, '0')
const WEEKDAYS = ['Su', 'Mo', 'Tu', 'We', 'Th', 'Fr', 'Sa']

/** "Date Picker Restrictions" (UC-ACH-ORG-01): weekends, Federal Reserve holidays and past dates are disabled. */
export default function DatePicker({ value, earliest, holidays, onChange }: Props) {
  const start = value || earliest
  const [month, setMonth] = useState({ y: Number(start.slice(0, 4)), m: Number(start.slice(5, 7)) })
  const first = new Date(Date.UTC(month.y, month.m - 1, 1))
  const daysInMonth = new Date(Date.UTC(month.y, month.m, 0)).getUTCDate()
  const blanks = first.getUTCDay()
  const canGoBack = `${month.y}-${pad(month.m)}` > earliest.slice(0, 7)

  const move = (delta: number) => {
    const d = new Date(Date.UTC(month.y, month.m - 1 + delta, 1))
    setMonth({ y: d.getUTCFullYear(), m: d.getUTCMonth() + 1 })
  }

  return (
    <div className="datepicker">
      <div className="dp-head">
        <button type="button" onClick={() => move(-1)} disabled={!canGoBack} aria-label="Previous month">‹</button>
        <span>{first.toLocaleDateString('en-US', { month: 'long', year: 'numeric', timeZone: 'UTC' })}</span>
        <button type="button" onClick={() => move(1)} aria-label="Next month">›</button>
      </div>
      <div className="dp-grid">
        {WEEKDAYS.map((w) => <span key={w} className="dp-dow">{w}</span>)}
        {Array.from({ length: blanks }, (_, i) => <span key={`b${i}`} />)}
        {Array.from({ length: daysInMonth }, (_, i) => {
          const iso = `${month.y}-${pad(month.m)}-${pad(i + 1)}`
          const ok = isSelectableDate(iso, earliest, holidays)
          return (
            <button
              type="button"
              key={iso}
              className={`dp-day${iso === value ? ' selected' : ''}${holidays.has(iso) ? ' holiday' : ''}`}
              disabled={!ok}
              title={holidays.has(iso) ? 'Federal Reserve holiday' : undefined}
              aria-label={formatDate(iso)}
              onClick={() => onChange(iso)}
            >
              {i + 1}
            </button>
          )
        })}
      </div>
    </div>
  )
}
