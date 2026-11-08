import { useEffect, useState } from 'react'
import { api } from '../api'
import type { ExternalAccount } from './types'

// 'unknown': not in our bank directory, so the member types the bank name (Ba's API requires bankName)
type Bank = { state: 'idle' | 'checking' | 'ok' | 'unknown'; name?: string }
const ROUTING_DIGITS = 'Enter all 9 digits of the routing number.'

const STATUS: Record<string, string> = { ACTIVE: 'Active', ACTIVE_PENDING_MICRO_DEPOSIT: 'Pending micro-deposit verification' }
const ADDED = 'External bank account added. It stays pending until micro-deposit verification.'

/** The member's external bank accounts (Ba's /v1/holders/{holderId}/external-accounts), and adding a new one. */
export default function ExternalAccountsPage({ memberNo }: { memberNo: string }) {
  const path = `/v1/holders/${encodeURIComponent(memberNo)}/external-accounts`
  const [accounts, setAccounts] = useState<ExternalAccount[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const [toast, setToast] = useState<string | null>(null)

  const load = () => api<ExternalAccount[]>(path).then(setAccounts).catch((e) => setLoadError(String(e.message ?? e)))
  useEffect(() => {
    load()
  }, [path])
  useEffect(() => {
    if (!toast) return
    const timer = setTimeout(() => setToast(null), 4000)
    return () => clearTimeout(timer)
  }, [toast])

  if (loadError) return <p className="page-error">{loadError}</p>
  return (
    <section>
      <p className="breadcrumb">Transfer &amp; Pay › External Bank Accounts</p>
      <h2>External Bank Accounts</h2>
      {adding ? (
        <AddAccountForm
          path={path}
          onCancel={() => setAdding(false)}
          onAdded={() => {
            setAdding(false)
            setToast(ADDED)
            load()
          }}
        />
      ) : (
        <div className="toolbar">
          <button className="primary" onClick={() => setAdding(true)}>Add New External Bank Account</button>
        </div>
      )}
      {accounts?.length === 0 && <p className="notice">You haven't added any external bank accounts yet.</p>}
      {accounts && accounts.length > 0 && (
        <table aria-label="External bank accounts">
          <thead>
            <tr><th>Bank</th><th>Account</th><th>Type</th><th>Status</th></tr>
          </thead>
          <tbody>
            {accounts.map((a) => (
              <tr key={a.externalAccountId}>
                <td>{a.bankName ?? '—'}</td>
                <td className="code">{a.maskedAccountNumber}</td>
                <td>{a.accountType === 'CHECKING' ? 'Checking' : 'Savings'}</td>
                <td>{STATUS[a.status] ?? a.status}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {toast && <div className="toast" role="status">{toast}</div>}
    </section>
  )
}

function AddAccountForm({ path, onCancel, onAdded }: { path: string; onCancel: () => void; onAdded: () => void }) {
  const [holderName, setHolderName] = useState('')
  const [routingNo, setRoutingNo] = useState('')
  const [bank, setBank] = useState<Bank>({ state: 'idle' })
  const [typedBankName, setTypedBankName] = useState('')
  const [routingLeft, setRoutingLeft] = useState(false)
  const [acctNo, setAcctNo] = useState('')
  const [acctType, setAcctType] = useState('CHECKING')
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // For now any 9 digits pass (the user, 2026-11-07), until real routing number validation is written.
  // The bank directory only fills in the bank name.
  useEffect(() => {
    if (routingNo.length < 9) return setBank({ state: 'idle' })
    setBank({ state: 'checking' })
    let current = true
    api<{ bankName: string }>(`/routing/${routingNo}`)
      .then((b) => current && setBank({ state: 'ok', name: b.bankName }))
      .catch(() => current && setBank({ state: 'unknown' }))
    return () => {
      current = false
    }
  }, [routingNo])

  const errors = {
    holderName: /^[A-Za-z0-9 .,'&-]{1,22}$/.test(holderName.trim()) ? null : "Enter the account holder's name: up to 22 letters, numbers and spaces.",
    routing: /^\d{9}$/.test(routingNo) ? null : ROUTING_DIGITS,
    bankName: bank.state !== 'unknown' || typedBankName.trim() ? null : 'Enter the bank name.',
    acctNo: /^\d{4,17}$/.test(acctNo) ? null : 'Enter the account number: 4 to 17 digits.',
  }
  const show = (field: keyof typeof errors) => (touched ? errors[field] : null)
  const showRouting = (touched || routingLeft) && errors.routing !== null

  async function save() {
    setTouched(true)
    if (Object.values(errors).some((e) => e !== null) || bank.state === 'checking') return
    setBusy(true)
    setError(null)
    try {
      await api(path, {
        method: 'POST',
        body: JSON.stringify({ bankName: bank.state === 'ok' ? bank.name : typedBankName.trim(), routingNumber: routingNo, accountNumber: acctNo, accountType: acctType, accountHolderName: holderName }),
      })
      onAdded()
    } catch (e) {
      setError(String((e as Error).message ?? e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="card form-card external-account-form">
      <h3>Add New External Bank Account</h3>
      {error && <p className="page-error" role="alert">{error}</p>}
      <label className="field">
        Account holder name
        <input value={holderName} maxLength={22} onChange={(e) => setHolderName(e.target.value)} className={show('holderName') ? 'invalid' : ''} />
      </label>
      {show('holderName') && <p className="field-error">{errors.holderName}</p>}
      <div className="row">
        <label className="field">
          Routing number (9 digits)
          <input
            inputMode="numeric"
            maxLength={9}
            value={routingNo}
            onChange={(e) => setRoutingNo(e.target.value.replace(/\D/g, ''))}
            onBlur={() => setRoutingLeft(true)}
            className={showRouting ? 'invalid code' : 'code'}
          />
        </label>
        <label className="field">
          Account number
          <input
            inputMode="numeric"
            maxLength={17}
            value={acctNo}
            onChange={(e) => setAcctNo(e.target.value.replace(/\D/g, ''))}
            className={show('acctNo') ? 'invalid code' : 'code'}
          />
        </label>
      </div>
      {bank.state === 'ok' && <p className="bank-name">✓ {bank.name}</p>}
      {bank.state === 'checking' && <p className="muted small">Looking up bank…</p>}
      {showRouting && <p className="field-error">{ROUTING_DIGITS}</p>}
      {bank.state === 'unknown' && (
        <>
          <label className="field">
            Bank name
            <input value={typedBankName} maxLength={60} onChange={(e) => setTypedBankName(e.target.value)} className={show('bankName') ? 'invalid' : ''} />
          </label>
          {show('bankName') && <p className="field-error">{errors.bankName}</p>}
        </>
      )}
      {show('acctNo') && <p className="field-error">{errors.acctNo}</p>}
      <fieldset className="field radio-row">
        <legend>Account type</legend>
        {['CHECKING', 'SAVINGS'].map((t) => (
          <label key={t}>
            <input type="radio" name="externalAcctType" checked={acctType === t} onChange={() => setAcctType(t)} />
            {t === 'CHECKING' ? 'Checking' : 'Savings'}
          </label>
        ))}
      </fieldset>
      <div className="actions left">
        <button onClick={onCancel} disabled={busy}>Cancel</button>
        <button className="primary" onClick={save} disabled={busy}>Add Account</button>
      </div>
    </div>
  )
}
