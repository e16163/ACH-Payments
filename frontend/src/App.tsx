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
    <main>
      <h1>ACH Payments</h1>
      <p>
        Database: <strong>{health ? `DB ${health.db}` : 'checking…'}</strong>
      </p>
      {health?.error && <pre>{health.error}</pre>}
      <ParameterPage />
    </main>
  )
}
