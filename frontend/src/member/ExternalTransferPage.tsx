import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api'
import DatePicker from './DatePicker'
import { receiptText, statusLabel } from './receipt'
import {
  FREQUENCIES, INVALID_ROUTING, accountNumberError, amountError, countError, formatAmountInput, formatDate,
  frequencyLabel, isValidRouting, masked, memoError, money, nameError, parseAmount,
} from './rules'
import type { Account, Preview, Receipt } from './types'

type DateRules = { earliest: string; holidays: string[] }
type Bank = { state: 'idle' | 'checking' | 'ok' | 'error'; name?: string }

/** UC-ACH-ORG-01 External ACH Transfer (Other Bank): enter details, review, confirm, receipt. */
export default function ExternalTransferPage() {
  const [accounts, setAccounts] = useState<Account[]>([])
  const [dateRules, setDateRules] = useState<DateRules | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const [fromAcct, setFromAcct] = useState('')
  const [recvName, setRecvName] = useState('')
  const [routingNo, setRoutingNo] = useState('')
  const [bank, setBank] = useState<Bank>({ state: 'idle' })
  const [acctNo, setAcctNo] = useState('')
  const [acctType, setAcctType] = useState('CHECKING')
  const [amount, setAmount] = useState('')
  const [effectiveDt, setEffectiveDt] = useState('')
  const [frequency, setFrequency] = useState('ONETIME')
  const [count, setCount] = useState('')
  const [memo, setMemo] = useState('')
  const [touched, setTouched] = useState(false)

  const [step, setStep] = useState<'form' | 'review'>('form')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [agreed, setAgreed] = useState(false)
  const [busy, setBusy] = useState(false)
  const [banner, setBanner] = useState<string | null>(null)
  const [limitMessage, setLimitMessage] = useState<string | null>(null)
  const [receipt, setReceipt] = useState<Receipt | null>(null)

  const loadAccounts = () =>
    api<Account[]>('/member/accounts')
      .then((list) => {
        setAccounts(list)
        setFromAcct((current) => current || list[0]?.acctNo || '')
      })
      .catch((e) => setLoadError(String(e.message ?? e)))

  useEffect(() => {
    loadAccounts()
    api<DateRules>('/transfers/dates').then(setDateRules).catch((e) => setLoadError(String(e.message ?? e)))
  }, [])

  // Step 3: check the routing number as soon as 9 digits are typed, then show the bank name.
  useEffect(() => {
    if (routingNo.length < 9) return setBank({ state: 'idle' })
    if (!isValidRouting(routingNo)) return setBank({ state: 'error' })
    setBank({ state: 'checking' })
    let current = true
    api<{ bankName: string }>(`/routing/${routingNo}`)
      .then((b) => current && setBank({ state: 'ok', name: b.bankName }))
      .catch(() => current && setBank({ state: 'error' }))
    return () => {
      current = false
    }
  }, [routingNo])

  const holidays = useMemo(() => new Set(dateRules?.holidays ?? []), [dateRules])
  const from = accounts.find((a) => a.acctNo === fromAcct)

  const errors = {
    recvName: nameError(recvName),
    routing: bank.state === 'ok' ? null : routingNo.length === 9 && bank.state === 'checking' ? null : INVALID_ROUTING,
    acctNo: accountNumberError(acctNo),
    amount: amountError(amount),
    date: effectiveDt ? null : 'Choose a transfer date.',
    count: countError(frequency, count),
    memo: memoError(memo),
  }
  const ready = Object.values(errors).every((e) => e === null) && bank.state === 'ok' && Boolean(from)
  const show = (field: keyof typeof errors) => (touched ? errors[field] : null)

  const request = () => ({
    origAcctNo: fromAcct,
    recvName,
    routingNo,
    acctNo,
    acctType,
    amount: parseAmount(amount),
    effectiveDt,
    frequency,
    transferCount: frequency === 'ONETIME' ? null : Number(count),
    memo,
  })

  function handleError(e: unknown) {
    if (e instanceof ApiError && e.kind === 'LIMIT') setLimitMessage(e.message) // EX-02: modal warning
    else setBanner(e instanceof Error ? e.message : String(e))
  }

  async function review() {
    setTouched(true)
    setBanner(null)
    if (!ready) return
    setBusy(true)
    try {
      setPreview(await api<Preview>('/transfers/preview', { method: 'POST', body: JSON.stringify(request()) }))
      setAgreed(false)
      setStep('review')
      window.scrollTo(0, 0)
    } catch (e) {
      handleError(e)
    } finally {
      setBusy(false)
    }
  }

  async function confirm() {
    setBanner(null)
    setBusy(true)
    try {
      setReceipt(await api<Receipt>('/transfers', { method: 'POST', body: JSON.stringify(request()) }))
      loadAccounts()
    } catch (e) {
      handleError(e)
      setStep('form')
    } finally {
      setBusy(false)
    }
  }

  function startOver() {
    setReceipt(null)
    setStep('form')
    setPreview(null)
    setTouched(false)
    setRecvName('')
    setRoutingNo('')
    setAcctNo('')
    setAmount('')
    setEffectiveDt('')
    setFrequency('ONETIME')
    setCount('')
    setMemo('')
  }

  function download(r: Receipt) {
    const url = URL.createObjectURL(new Blob([receiptText(r)], { type: 'text/plain' }))
    const a = document.createElement('a')
    a.href = url
    a.download = `${r.transferSerNo}-receipt.txt`
    a.click()
    URL.revokeObjectURL(url)
  }

  if (loadError) return <p className="page-error">{loadError}</p>

  return (
    <section>
      <p className="breadcrumb">Transfer &amp; Pay › External ACH Transfer (Other Bank)</p>
      <h2>External ACH Transfer (Other Bank)</h2>
      {banner && <p className="page-error" role="alert">{banner}</p>}

      {step === 'form' && (
        <div className="card form-card">
          <h3>From</h3>
          <label className="field">
            Funding account
            <select value={fromAcct} onChange={(e) => setFromAcct(e.target.value)}>
              {accounts.map((a) => (
                <option key={a.acctNo} value={a.acctNo}>
                  {a.acctName} {masked(a.acctNo.slice(-4))}
                </option>
              ))}
            </select>
          </label>
          {from && <p className="balance">Available balance: <strong>{money(from.availBal)}</strong></p>}

          <h3>To</h3>
          <label className="field">
            Recipient name
            <input value={recvName} maxLength={22} onChange={(e) => setRecvName(e.target.value)} className={show('recvName') ? 'invalid' : ''} />
          </label>
          {show('recvName') && <p className="field-error">{errors.recvName}</p>}

          <div className="row">
            <label className="field">
              Routing number (9 digits)
              <input
                inputMode="numeric"
                maxLength={9}
                value={routingNo}
                onChange={(e) => setRoutingNo(e.target.value.replace(/\D/g, ''))}
                className={bank.state === 'error' ? 'invalid code' : 'code'}
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
          {bank.state === 'error' && <p className="field-error">{INVALID_ROUTING}</p>}
          {show('acctNo') && <p className="field-error">{errors.acctNo}</p>}

          <fieldset className="field radio-row">
            <legend>Account type</legend>
            {['CHECKING', 'SAVINGS'].map((t) => (
              <label key={t}>
                <input type="radio" name="acctType" checked={acctType === t} onChange={() => setAcctType(t)} />
                {t === 'CHECKING' ? 'Checking' : 'Savings'}
              </label>
            ))}
          </fieldset>

          <h3>Transfer details</h3>
          <div className="row">
            <label className="field">
              Amount
              <div className="money-input">
                <span>$</span>
                <input inputMode="decimal" value={amount} placeholder="0.00"
                  onChange={(e) => setAmount(formatAmountInput(e.target.value))}
                  onBlur={() => parseAmount(amount) && setAmount(formatAmountInput(parseAmount(amount)!))}
                  className={show('amount') ? 'invalid' : ''} />
              </div>
            </label>
            <label className="field">
              Memo (optional, 10 characters)
              <input value={memo} maxLength={10} onChange={(e) => setMemo(e.target.value)} className={show('memo') ? 'invalid' : ''} />
            </label>
          </div>
          {show('amount') && <p className="field-error">{errors.amount}</p>}
          {show('memo') && <p className="field-error">{errors.memo}</p>}

          <div className="row">
            <label className="field">
              Frequency
              <select value={frequency} onChange={(e) => setFrequency(e.target.value)}>
                {FREQUENCIES.map((f) => <option key={f.value} value={f.value}>{f.label}</option>)}
              </select>
            </label>
            {frequency !== 'ONETIME' && (
              <label className="field">
                Number of transfers (2 to 52)
                <input inputMode="numeric" value={count} maxLength={2}
                  onChange={(e) => setCount(e.target.value.replace(/\D/g, ''))} className={show('count') ? 'invalid' : ''} />
              </label>
            )}
          </div>
          {show('count') && <p className="field-error">{errors.count}</p>}

          <div className="field">
            <span className="label">Transfer date {effectiveDt && <strong>· {formatDate(effectiveDt)}</strong>}</span>
            {dateRules && (
              <DatePicker value={effectiveDt} earliest={dateRules.earliest} holidays={holidays} onChange={setEffectiveDt} />
            )}
            <p className="muted small">Weekends and Federal Reserve holidays aren't available. The earliest date is the next banking day.</p>
          </div>
          {show('date') && <p className="field-error">{errors.date}</p>}

          <div className="actions left">
            <button className="primary" onClick={review} disabled={busy || bank.state !== 'ok'}>Continue</button>
          </div>
        </div>
      )}

      {step === 'review' && preview && (
        <div className="card form-card">
          <h3>Review your transfer</h3>
          <dl className="summary">
            <dt>From</dt><dd>{from?.acctName} {masked(fromAcct.slice(-4))}</dd>
            <dt>To</dt><dd>{recvName.toUpperCase()}</dd>
            <dt>Bank</dt><dd>{preview.bankName} <span className="muted">(routing {routingNo})</span></dd>
            <dt>Account</dt><dd>{acctType === 'CHECKING' ? 'Checking' : 'Savings'} {masked(acctNo.slice(-4))}</dd>
            <dt>Amount</dt><dd>{money(preview.amount)}</dd>
            <dt>Fee</dt><dd>{money(preview.fee)}</dd>
            <dt>Total</dt><dd><strong>{money(preview.total)}</strong></dd>
            <dt>Transfer date</dt><dd>{formatDate(preview.effectiveDt)}</dd>
            <dt>Estimated arrival</dt><dd>{formatDate(preview.estimatedArrival)}</dd>
            <dt>Frequency</dt><dd>{frequencyLabel(frequency)}{frequency !== 'ONETIME' && ` (${count} transfers)`}</dd>
            {memo.trim() && <><dt>Memo</dt><dd>{memo.trim().toUpperCase()}</dd></>}
          </dl>
          {preview.underReview && (
            <p className="notice">This transfer will be reviewed by our compliance team before it's sent.</p>
          )}
          <div className="terms">
            <h4>Fee disclosure and transfer terms</h4>
            <p>
              External transfers cost {money(preview.fee)}. The total is held from your available balance today. Funds
              should arrive at the other bank by the estimated arrival date. You can't cancel a transfer after it's been
              sent to the Federal Reserve. By confirming, you authorize Demo Credit Union to send this ACH credit to the
              account above{frequency !== 'ONETIME' ? ' on the schedule shown' : ''}.
            </p>
            <label className="checkbox">
              <input type="checkbox" checked={agreed} onChange={(e) => setAgreed(e.target.checked)} />
              I have read and agree to the transfer terms.
            </label>
          </div>
          <div className="actions left">
            <button className="primary" onClick={confirm} disabled={busy || !agreed}>Confirm Transfer</button>
            <button onClick={() => setStep('form')} disabled={busy}>Back</button>
          </div>
        </div>
      )}

      {limitMessage && (
        <div className="overlay">
          <div className="modal" role="alertdialog" aria-label="Transfer limit exceeded">
            <h2>Transfer limit exceeded</h2>
            <p>{limitMessage}</p>
            <div className="actions">
              <button className="primary" onClick={() => setLimitMessage(null)}>OK</button>
            </div>
          </div>
        </div>
      )}

      {receipt && (
        <div className="overlay">
          <div className="modal" role="dialog" aria-label="Transfer submitted">
            <h2>{receipt.ofacStatus === 'FLAGGED' ? 'Transfer under review' : 'Transfer scheduled'}</h2>
            <p className="muted">Reference number</p>
            <p className="reference code">{receipt.transferSerNo}</p>
            <dl className="summary">
              <dt>Status</dt><dd>{statusLabel(receipt)}</dd>
              <dt>Amount</dt><dd>{money(receipt.total)}</dd>
              <dt>To</dt><dd>{receipt.recvName} · {receipt.bankName} {masked(receipt.acctLast4)}</dd>
              <dt>Transfer date</dt><dd>{formatDate(receipt.effectiveDt)}</dd>
              <dt>Estimated arrival</dt><dd>{formatDate(receipt.estimatedArrival)}</dd>
            </dl>
            {receipt.ofacStatus === 'FLAGGED' && (
              <p className="notice">Your transfer is under review. We'll contact you if we need more information.</p>
            )}
            <div className="actions">
              <button onClick={() => download(receipt)}>Download receipt</button>
              <button className="primary" onClick={startOver}>Done</button>
            </div>
          </div>
        </div>
      )}
    </section>
  )
}
