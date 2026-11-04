import { useState } from 'react'
import { api } from '../api'

/** Simulated multi-factor step (USE-CASES T7). A real credit union would text or email the code. */
export default function MfaPage({ onVerified }: { onVerified: () => void }) {
  const [demoCode, setDemoCode] = useState<string | null>(null)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function send() {
    setError(null)
    setBusy(true)
    try {
      const res = await api<{ demoCode: string }>('/member/mfa/send', { method: 'POST' })
      setDemoCode(res.demoCode)
      setCode('')
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  async function verify() {
    setError(null)
    setBusy(true)
    try {
      await api('/member/mfa/verify', { method: 'POST', body: JSON.stringify({ code }) })
      onVerified()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="card narrow">
      <h2>Verify it's you</h2>
      <p className="muted">For your security, we need to confirm your identity before you can move money.</p>
      {!demoCode ? (
        <button className="primary" onClick={send} disabled={busy}>Send verification code</button>
      ) : (
        <>
          <p className="notice">
            Demo: your code is <strong className="code">{demoCode}</strong>. A real credit union would text it to your phone.
          </p>
          <label className="field">
            Verification code
            <input
              inputMode="numeric"
              maxLength={6}
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))}
              autoFocus
            />
          </label>
          <div className="actions left">
            <button className="primary" onClick={verify} disabled={busy || code.length !== 6}>Verify</button>
            <button onClick={send} disabled={busy}>Send a new code</button>
          </div>
        </>
      )}
      {error && <p className="field-error">{error}</p>}
    </section>
  )
}
