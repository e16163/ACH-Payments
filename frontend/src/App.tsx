import { useCallback, useEffect, useState } from 'react'
import { api, ApiError, DEMO, logout } from './api'
import BackOfficePage from './backoffice/BackOfficePage'
import { SCREENS } from './backoffice/screens'
import AchOriginationFilePage from './files/AchOriginationFilePage'
import AchReceivingFilePage from './files/AchReceivingFilePage'
import AchReceivingTransactionPage from './files/AchReceivingTransactionPage'
import LoginPage from './LoginPage'
import AccountsPage from './member/AccountsPage'
import ExternalTransferPage from './member/ExternalTransferPage'
import MfaPage from './member/MfaPage'
import AppLogPage from './logs/AppLogPage'
import ParameterPage from './params/ParameterPage'
import './app.css'

type Health = { db: string; error?: string }
type Me = { username: string; roles: string[]; mfaVerified: boolean }

const ROUTES = {
  params: '#/system-configuration',
  appLogs: '#/system-audit-app-logs',
  originationFile: '#/ach-origination-file',
  receivingFile: '#/ach-receiving-file',
  receivingTransactions: '#/ach-receiving-transactions',
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
  const [signedOut, setSignedOut] = useState(false)
  const [meError, setMeError] = useState<string | null>(null)
  const [menuOpen, setMenuOpen] = useState(false)
  const hash = useHash()

  const loadMe = useCallback(() => {
    setMeError(null)
    api<Me>('/me')
      .then((m) => {
        setMe(m)
        setSignedOut(false)
      })
      .catch((e) => {
        if (e instanceof ApiError && e.status === 401) setSignedOut(true)
        else setMeError(String(e.message ?? e))
      })
  }, [])

  async function signOut() {
    await logout()
    setMe(null)
    setSignedOut(true)
    window.location.hash = ''
  }

  useEffect(() => {
    if (!DEMO) {
      fetch(`${import.meta.env.BASE_URL}api/health`)
        .then((res) => res.json())
        .then(setHealth)
        .catch((e) => setHealth({ db: 'UNREACHABLE', error: String(e) }))
    }
    loadMe()
  }, [loadMe])

  useEffect(() => setMenuOpen(false), [hash])

  const isAdmin = me?.roles.includes('SYS_ADMIN')
  const isOrigOps = me?.roles.includes('ACH_ORIG_OPS')
  const isRecvOps = me?.roles.includes('ACH_RECV_OPS')
  const isMember = me?.roles.includes('MEMBER')
  const memberReady = isMember && me?.mfaVerified

  const onTransactions = hash.startsWith(ROUTES.receivingTransactions)
  const adminElsewhere = hash === ROUTES.appLogs || hash === ROUTES.originationFile || hash === ROUTES.receivingFile || onTransactions
  const myScreens = SCREENS.filter((s) => me?.roles.includes(s.role))
  const screen = myScreens.find((s) => s.route === hash)

  let page = null
  if (signedOut) page = <LoginPage onSignedIn={loadMe} />
  else if (isAdmin && hash === ROUTES.appLogs) page = <AppLogPage />
  else if (screen) page = <BackOfficePage key={screen.route} screen={screen} />
  else if (isOrigOps || (isAdmin && hash === ROUTES.originationFile)) page = <AchOriginationFilePage />
  else if ((isAdmin || isRecvOps) && onTransactions) {
    page = <AchReceivingTransactionPage route={ROUTES.receivingTransactions} fileID={decodeURIComponent(hash.slice(ROUTES.receivingTransactions.length + 1))} />
  } else if (isRecvOps || (isAdmin && hash === ROUTES.receivingFile)) {
    page = <AchReceivingFilePage transactionsRoute={ROUTES.receivingTransactions} />
  } else if (isAdmin) page = <ParameterPage />
  else if (isMember && !me?.mfaVerified) page = <MfaPage onVerified={loadMe} />
  else if (memberReady) page = hash === ROUTES.external ? <ExternalTransferPage /> : <AccountsPage />
  else if (me) page = <p className="card narrow">There are no screens for your role yet.</p>

  return (
    <div className="shell">
      {DEMO && (
        <div className="demo-banner">
          Demo with made-up data. Nothing here is a real bank, account or payment, and changes reset when you close the tab.
        </div>
      )}
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" aria-hidden="true">DCU</span>
          <span className="brand-name">Demo Credit Union</span>
          <span className="brand-product">{isMember ? 'Online Banking' : 'ACH Payments'}</span>
        </div>
        {me && (
          <div className="user">
            <span>Signed in as {me.username}</span>
            <button onClick={signOut}>Sign out</button>
          </div>
        )}
      </header>
      {me && (
        <nav className="mainnav" aria-label="Main">
          {isAdmin && (
            <>
              <a className={!adminElsewhere ? 'active' : ''} href={ROUTES.params}>System Configuration</a>
              <a className={hash === ROUTES.appLogs ? 'active' : ''} href={ROUTES.appLogs}>System Audit &amp; App Logs</a>
            </>
          )}
          {(isAdmin || isOrigOps) && (
            <a className={(isAdmin ? hash === ROUTES.originationFile : !screen) ? 'active' : ''} href={ROUTES.originationFile}>ACH Origination File</a>
          )}
          {(isAdmin || isRecvOps) && (
            <>
              <a className={(isAdmin ? hash === ROUTES.receivingFile : !onTransactions && !screen) ? 'active' : ''} href={ROUTES.receivingFile}>ACH Receiving File</a>
              <a className={onTransactions ? 'active' : ''} href={ROUTES.receivingTransactions}>ACH Receiving Transactions</a>
            </>
          )}
          {myScreens.map((s) => (
            <a key={s.route} className={s === screen ? 'active' : ''} href={s.route}>{s.title}</a>
          ))}
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
      )}
      <main>
        {meError && <p className="page-error">{meError}</p>}
        {page}
      </main>
      <footer className="statusbar">
        {DEMO ? (
          'Demo mode: no server or database. Everything runs in this browser tab.'
        ) : (
          <>
            <span className={`dot ${health?.db === 'OK' ? 'ok' : 'down'}`} aria-hidden="true" />
            Database: {health ? health.db : 'checking…'}
            {health?.error && <span className="status-error"> — {health.error}</span>}
          </>
        )}
      </footer>
    </div>
  )
}
