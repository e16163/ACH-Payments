import { useEffect, useState } from 'react'
import { api } from '../api'
import { masked, money } from './rules'
import type { Account, AcctTran } from './types'

type TranPage = { items: AcctTran[]; total: number; page: number; limit: number }

const LIMIT = 10
const typeLabel = (t: string) => (t === 'CHECKING' ? 'Checking' : t === 'SAVINGS' ? 'Savings' : t)
const statusLabel = (s: string) => s.charAt(0) + s.slice(1).toLowerCase()

/** "Everyday Checking", or the product name when the account has no nickname. */
export const accountLabel = (a: Account) => `${a.acctNickname ?? a.acctName} ${masked(a.acctNo.slice(-4))}`

/**
 * The member's landing page (Ba, 2026-11-13): choose an account; its summary is at the top and its credits and debits
 * below, newest first. A hold (an ACH transfer going out today) shows as Pending: it lowers the available balance only.
 */
export default function AccountsPage() {
  const [accounts, setAccounts] = useState<Account[] | null>(null)
  const [acctNo, setAcctNo] = useState('')
  const [page, setPage] = useState(1)
  const [trans, setTrans] = useState<TranPage | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Account[]>('/member/accounts')
      .then((list) => {
        setAccounts(list)
        setAcctNo(list[0]?.acctNo ?? '')
      })
      .catch((e) => setError(String(e.message ?? e)))
  }, [])

  useEffect(() => {
    if (!acctNo) return
    api<TranPage>(`/member/accounts/${encodeURIComponent(acctNo)}/transactions?page=${page}&limit=${LIMIT}`)
      .then(setTrans)
      .catch((e) => setError(String(e.message ?? e)))
  }, [acctNo, page])

  if (error) return <p className="page-error">{error}</p>
  const a = accounts?.find((x) => x.acctNo === acctNo)
  const pages = trans ? Math.max(1, Math.ceil(trans.total / LIMIT)) : 1

  return (
    <section>
      <h2>Accounts</h2>
      <div className="toolbar">
        <label>
          Account{' '}
          <select value={acctNo} onChange={(e) => { setAcctNo(e.target.value); setPage(1) }}>
            {accounts?.map((x) => <option key={x.acctNo} value={x.acctNo}>{accountLabel(x)}</option>)}
          </select>
        </label>
      </div>

      {a && (
        <div className="card">
          <dl className="details" aria-label="Account summary">
            <div><dt>Account number</dt><dd className="code">{a.acctNo}</dd></div>
            <div><dt>Nickname</dt><dd>{a.acctNickname ?? '—'}</dd></div>
            <div><dt>Account type</dt><dd>{typeLabel(a.acctType)} · {statusLabel(a.acctGroup)}</dd></div>
            <div><dt>Available balance</dt><dd><strong>{money(a.availBal)}</strong></dd></div>
            <div><dt>Posted balance</dt><dd><strong>{money(a.ledgerBal)}</strong></dd></div>
            <div><dt>Status</dt><dd>{statusLabel(a.acctStatus)}</dd></div>
          </dl>
        </div>
      )}

      <h3>Transactions</h3>
      <table aria-label="Account transactions">
        <thead>
          <tr>
            <th>Date</th><th>Description</th><th>Status</th><th className="num">Debit</th><th className="num">Credit</th>
            <th className="num">Available after</th><th className="num">Posted after</th>
          </tr>
        </thead>
        <tbody>
          {trans?.items.length === 0 && (
            <tr><td className="empty" colSpan={7}>No transactions on this account yet.</td></tr>
          )}
          {trans?.items.map((t) => (
            <tr key={t.acctTranId}>
              <td>{t.tranTm.replace('T', ' ').slice(0, 16)}</td>
              <td>{t.tranDesc}</td>
              <td>{t.balanceType === 'POSTED' ? 'Posted' : 'Pending'}</td>
              <td className="num">{t.credit ? '' : money(t.amount)}</td>
              <td className="num">{t.credit ? money(t.amount) : ''}</td>
              <td className="num">{money(t.availBalAfter)}</td>
              <td className="num">{money(t.ledgerBalAfter)}</td>
            </tr>
          ))}
        </tbody>
      </table>
      {trans && trans.total > LIMIT && (
        <div className="pager">
          <button disabled={page <= 1} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page} of {pages}</span>
          <button disabled={page >= pages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </section>
  )
}
