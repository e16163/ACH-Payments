import { useCallback, useEffect, useState } from 'react'
import { api } from '../api'

type Flagged = {
  transfer_id: string; member_no: string; recipient_name: string; amount: number; effective_dt: string; flagged_at: string | null
}

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' })

/**
 * Ba's blueprint, ACH Origination step 5: the compliance officer's list of transfers flagged by OFAC screening.
 * Clear (false positive) sends a transfer on at the next window; Block rejects it and gives the member's hold back.
 */
export default function OfacReviewPage() {
  const [items, setItems] = useState<Flagged[] | null>(null)
  const [notes, setNotes] = useState<Record<string, string>>({})
  const [message, setMessage] = useState('')
  const [error, setError] = useState('')

  const load = useCallback(async () => {
    try {
      setItems(await api<Flagged[]>('/v1/compliance/ofac/flagged'))
    } catch (e) {
      setError((e as Error).message)
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  async function resolve(id: string, resolution: 'CLEARED_FALSE_POSITIVE' | 'BLOCKED_CONFIRMED_MATCH') {
    setMessage('')
    setError('')
    try {
      const r = await api<{ current_status: string }>('/v1/compliance/ofac/resolve', {
        method: 'PATCH',
        body: JSON.stringify({ transfer_id: id, resolution, notes: notes[id] ?? '' }),
      })
      setMessage(`${id} is ${r.current_status}.`)
      await load()
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <>
      <h2>OFAC Review</h2>
      <p>Transfers held because the recipient's name matched the OFAC list. Clear a false positive to send it at the next window, or block a true match.</p>
      {message && <p className="notice" role="status">{message}</p>}
      {error && <p className="page-error" role="alert">{error}</p>}
      {items?.length === 0 && <p className="notice" role="status">No transfers are waiting for review.</p>}
      {items && items.length > 0 && (
        <table aria-label="Flagged transfers">
          <thead>
            <tr><th>Transfer</th><th>Member</th><th>Recipient</th><th>Amount</th><th>Effective Date</th><th>Flagged</th><th>Notes</th><th>Action</th></tr>
          </thead>
          <tbody>
            {items.map((t) => (
              <tr key={t.transfer_id}>
                <td className="code">{t.transfer_id}</td>
                <td>{t.member_no}</td>
                <td>{t.recipient_name}</td>
                <td>{usd(t.amount)}</td>
                <td>{t.effective_dt}</td>
                <td>{t.flagged_at?.replace('T', ' ') ?? '—'}</td>
                <td>
                  <input aria-label={`Notes for ${t.transfer_id}`} maxLength={500} value={notes[t.transfer_id] ?? ''}
                    onChange={(e) => setNotes({ ...notes, [t.transfer_id]: e.target.value })} />
                </td>
                <td className="row-actions">
                  <button className="primary" onClick={() => resolve(t.transfer_id, 'CLEARED_FALSE_POSITIVE')}>Clear</button>
                  <button onClick={() => resolve(t.transfer_id, 'BLOCKED_CONFIRMED_MATCH')}>Block</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
