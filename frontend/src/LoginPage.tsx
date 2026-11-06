import { useState, type FormEvent } from 'react'
import { DEMO, login } from './api'

/** The app's own sign-in page (replaces the browser's login popup). */
export default function LoginPage({ onSignedIn }: { onSignedIn: () => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setBusy(true)
    try {
      await login(username.trim(), password)
      onSignedIn()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
      setPassword('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card narrow login" onSubmit={submit}>
      <h2>Sign in</h2>
      {DEMO && (
        <p className="notice">
          Demo logins: <strong className="code">member</strong> / <strong className="code">member123</strong> for online
          banking, <strong className="code">admin</strong> / <strong className="code">admin123</strong> for System
          Configuration, <strong className="code">achorig</strong> / <strong className="code">achorig123</strong> for the ACH
          Origination back office, or <strong className="code">achrecv</strong> / <strong className="code">achrecv123</strong> for
          the ACH Receiving back office.
        </p>
      )}
      <label className="field">
        Username
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" autoFocus />
      </label>
      <label className="field">
        Password
        <input
          type="password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="current-password"
        />
      </label>
      {error && <p className="field-error">{error}</p>}
      <div className="actions left">
        <button className="primary" type="submit" disabled={busy || !username.trim() || !password}>
          Sign in
        </button>
      </div>
    </form>
  )
}
