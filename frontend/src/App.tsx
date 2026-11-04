import { useEffect, useState } from 'react'
import ParameterPage from './params/ParameterPage'
import './app.css'

type Health = { db: string; error?: string }

export default function App() {
  const [health, setHealth] = useState<Health | null>(null)

  useEffect(() => {
    fetch(`${import.meta.env.BASE_URL}api/health`)
      .then((res) => res.json())
      .then(setHealth)
      .catch((e) => setHealth({ db: 'UNREACHABLE', error: String(e) }))
  }, [])

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">DCU</span>
          <span className="brand-name">Demo Credit Union</span>
          <span className="brand-product">ACH Payments</span>
        </div>
      </header>
      <nav className="mainnav" aria-label="Main">
        <a className="active" href="#">System Configuration</a>
      </nav>
      <main>
        <ParameterPage />
      </main>
      <footer className="statusbar">
        <span className={`dot ${health?.db === 'OK' ? 'ok' : 'down'}`} aria-hidden="true" />
        Database: {health ? health.db : 'checking…'}
        {health?.error && <span className="status-error"> — {health.error}</span>}
      </footer>
    </div>
  )
}
