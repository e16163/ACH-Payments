import { useCallback, useEffect, useState } from 'react'
import { api } from './api'
import AccountsPage from './member/AccountsPage'
import ExternalTransferPage from './member/ExternalTransferPage'
import MfaPage from './member/MfaPage'
import ParameterPage from './params/ParameterPage'
import './app.css'

type Health = { db: string; error?: string }
type Me = { username: string; roles: string[]; mfaVerified: boolean }

const ROUTES = {
  params: '#/system-configuration',
  accounts: '#/accounts',
  external: '#/transfer-pay/external-ach',
}

function useHash() {
  const [hash, setHash] = useState(window.location.hash)
  useEffect(() => {
    const onChange = () => setHash(window.location.hash)
    window.addEventListener('hashchange', onChange)
    return () => window.removeEventListener('hashchange', onChange)
  }, [])
  return hash
}

export default function App() {
  const [health, setHealth] = useState<Health | null>(null)
  const [me, setMe] = useState<Me | null>(null)
  const [meError, setMeError] = useState<string | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const hash = useHash()

  const loadMe = useCallback(() => {
    api<Me>('/me').then(setMe).catch((e) => setMeError(String(e.message ?? e)))
  }, [])

  useEffect(() => {
    fetch(`${import.meta.env.BASE_URL}api/health`)
      .then((res) => res.json())
      .then(setHealth)
      .catch((e) => setHealth({ db: 'UNREACHABLE', error: String(e) }))
    loadMe()
  }, [loadMe])

  useEffect(() => setMenuOpen(false), [hash])

  const isAdmin = me?.roles.includes('SYS_ADMIN')
  const isMember = me?.roles.includes('MEMBER')
  const memberReady = isMember && me?.mfaVerified

  let page = null
  if (isAdmin) page = <ParameterPage />
  else if (isMember && !me?.mfaVerified) page = <MfaPage onVerified={loadMe} />
  else if (memberReady) page = hash === ROUTES.external ? <ExternalTransferPage /> : <AccountsPage />
  else if (me) page = <p className="card narrow">There are no screens for your role yet.</p>

  return (
    <div className="shell">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">DCU</span>
          <span className="brand-name">Demo Credit Union</span>
          <span className="brand-product">{isMember ? 'Online Banking' : 'ACH Payments'}</span>
        </div>
        {me && <span className="user">Signed in as {me.username}</span>}
      </header>
      <nav className="mainnav" aria-label="Main">
        {isAdmin && <a className="active" href={ROUTES.params}>System Configuration</a>}
        {memberReady && (
          <>
            <a className={hash !== ROUTES.external ? 'active' : ''} href={ROUTES.accounts}>Accounts</a>
            <div className="menu">
              <button
                className={`menu-button${hash === ROUTES.external ? ' active' : ''}`}
                aria-expanded={menuOpen}
                onClick={() => setMenuOpen((o) => !o)}
              >
                Transfer &amp; Pay ▾
              </button>
              {menuOpen && (
                <div className="menu-list" role="menu">
                  <a role="menuitem" href={ROUTES.external}>External ACH Transfer (Other Bank)</a>
                </div>
              )}
            </div>
          </>
        )}
      </nav>
      <main>
        {meError && <p className="page-error">{meError}</p>}
        {page}
      </main>
      <footer className="statusbar">
        <span className={`dot ${health?.db === 'OK' ? 'ok' : 'down'}`} aria-hidden="true" />
        Database: {health ? health.db : 'checking…'}
        {health?.error && <span className="status-error"> — {health.error}</span>}
      </footer>
    </div>
  )
}
