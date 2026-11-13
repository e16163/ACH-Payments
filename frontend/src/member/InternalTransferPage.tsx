import { useEffect, useState } from 'react'
import { api } from '../api'
import { accountLabel } from './AccountsPage'
import { amountError, formatAmountInput, money, parseAmount } from './rules'
import type { Account } from './types'

type Receipt = { transferId: string; fromAcctNo: string; toAcctNo: string; amount: string; fromAvailBal: string; toAvailBal: string }

/**
 * Ba (2026-11-13): move money between the member's own accounts. It's a book transfer inside the core, so both
 * accounts' available and posted balances change at once.
 */
export default function InternalTransferPage() {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')
  const [amount, setAmount] = useState('')
  const [memo, setMemo] = useState('')
  const [touched, setTouched] = useState(false)
  const [busy, setBusy] = useState(false)
  const [banner, setBanner] = useState<string | null>(null)
  const [receipt, setReceipt] = useState<Receipt | null>(null)

  const load = () =>
    api<Account[]>('/member/accounts')
      .then((list) => {
        setAccounts(list)
        setFrom((f) => f || list[0]?.acctNo || '')
        setTo((t) => t || list[1]?.acctNo || '')
      })
      .catch((e) => setBanner(String(e.message ?? e)))

  useEffect(() => {
    load()
  }, [])

  const fromAcct = accounts.find((a) => a.acctNo === from)
  const toAcct = accounts.find((a) => a.acctNo === to)
  const errors = {
    accounts: from && to && from === to ? 'Choose two different accounts.' : null,
    amount: amountError(amount),
  }
  const ready = Boolean(fromAcct && toAcct) && Object.values(errors).every((e) => e === null)

  async function submit() {
    setTouched(true)
    setBanner(null)
    if (!ready) return
    setBusy(true)
    try {
      setReceipt(await api<Receipt>('/transfers/internal', {
        method: 'POST',
        body: JSON.stringify({ fromAcctNo: from, toAcctNo: to, amount: parseAmount(amount), memo }),
      }))
      load()
    } catch (e) {
      setBanner(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  function again() {
    setReceipt(null)
    setAmount('')
    setMemo('')
    setTouched(false)
  }

  return (
    <section>
      <p className="breadcrumb">Transfer &amp; Pay › Internal Transfer</p>
      <h2>Internal Transfer</h2>
      {banner && <p className="page-error" role="alert">{banner}</p>}

      {receipt ? (
        <div className="card form-card" role="status">
          <h3>Transfer complete</h3>
          <dl className="summary">
            <dt>Confirmation</dt><dd className="code">{receipt.transferId}</dd>
            <dt>Amount</dt><dd>{money(receipt.amount)}</dd>
            <dt>From</dt><dd>{fromAcct ? accountLabel(fromAcct) : receipt.fromAcctNo} · available {money(receipt.fromAvailBal)}</dd>
            <dt>To</dt><dd>{toAcct ? accountLabel(toAcct) : receipt.toAcctNo} · available {money(receipt.toAvailBal)}</dd>
          </dl>
          <p className="muted small">Both accounts' available and posted balances are updated now.</p>
          <div className="actions left">
            <button className="primary" onClick={again}>Make another transfer</button>
            <a className="button" href="#/accounts">View accounts</a>
          </div>
        </div>
      ) : (
        <div className="card form-card">
          <div className="row">
            <label className="field">
              From
              <select value={from} onChange={(e) => setFrom(e.target.value)}>
                {accounts.map((a) => <option key={a.acctNo} value={a.acctNo}>{accountLabel(a)}</option>)}
              </select>
            </label>
            <label className="field">
              To
              <select value={to} onChange={(e) => setTo(e.target.value)} className={touched && errors.accounts ? 'invalid' : ''}>
                {accounts.map((a) => <option key={a.acctNo} value={a.acctNo}>{accountLabel(a)}</option>)}
              </select>
            </label>
          </div>
          {fromAcct && <p className="balance">Available balance: <strong>{money(fromAcct.availBal)}</strong></p>}
          {touched && errors.accounts && <p className="field-error">{errors.accounts}</p>}

          <div className="row">
            <label className="field">
              Amount
              <div className="money-input">
                <span>$</span>
                <input inputMode="decimal" value={amount} placeholder="0.00"
                  onChange={(e) => setAmount(formatAmountInput(e.target.value))}
                  className={touched && errors.amount ? 'invalid' : ''} />
              </div>
            </label>
            <label className="field">
              Memo (optional, 30 characters)
              <input value={memo} maxLength={30} onChange={(e) => setMemo(e.target.value)} />
            </label>
          </div>
          {touched && errors.amount && <p className="field-error">{errors.amount}</p>}
          <p className="muted small">The money moves now, and both balances of both accounts change at once.</p>

          <div className="actions left">
            <button className="primary" onClick={submit} disabled={busy}>Transfer</button>
          </div>
        </div>
      )}
    </section>
  )
}
