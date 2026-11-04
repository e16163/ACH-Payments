import { useEffect, useState } from 'react'
import { api } from '../api'
import { masked, money } from './rules'
import type { Account } from './types'

/** The member's landing page: their accounts and available balances. */
export default function AccountsPage() {
  const [accounts, setAccounts] = useState<Account[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<Account[]>('/member/accounts').then(setAccounts).catch((e) => setError(String(e.message ?? e)))
  }, [])

  if (error) return <p className="page-error">{error}</p>
  return (
    <section>
      <h2>Accounts</h2>
      <table>
        <thead>
          <tr>
            <th>Account</th>
            <th>Type</th>
            <th className="num">Available balance</th>
          </tr>
        </thead>
        <tbody>
          {accounts?.map((a) => (
            <tr key={a.acctNo}>
              <td>{a.acctName} <span className="muted">{masked(a.acctNo.slice(-4))}</span></td>
              <td>{a.acctType === 'CHECKING' ? 'Checking' : 'Savings'}</td>
              <td className="num">{money(a.availBal)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </section>
  )
}
