import { lazy, Suspense, useCallback, useEffect, useState } from 'react'
import { api, ApiError, DEMO, logout } from './api'
import BackOfficePage from './backoffice/BackOfficePage'
import { SCREENS } from './backoffice/screens'
import AchOriginationProcessingPage from './files/AchOriginationProcessingPage'
import AchOriginationTransactionPage from './files/AchOriginationTransactionPage'
import AchReceivingProcessingPage from './files/AchReceivingProcessingPage'
import AchReceivingTransactionPage from './files/AchReceivingTransactionPage'
import LoginPage from './LoginPage'
import AccountsPage from './member/AccountsPage'
import ExternalAccountsPage from './member/ExternalAccountsPage'
import ExternalTransferPage from './member/ExternalTransferPage'
import MfaPage from './member/MfaPage'
import AppLogPage from './logs/AppLogPage'
import ParameterPage from './params/ParameterPage'
import BusinessRulePage from './rules/BusinessRulePage'
import OfacReviewPage from './compliance/OfacReviewPage'
import './app.css'

// Admin-only, so the YAML parser and specs load only when an API page opens. A failed download (e.g. after a redeploy) shows a message, not a blank app.
const ApiSpecPage = lazy(() => import('./apidocs/ApiSpecPage').catch(() => ({
  default: () => <p className="page-error">This page couldn't be loaded. Reload the page to try again.</p>,
})))

type Health = { db: string; error?: string }
type Me = { username: string; roles: string[]; mfaVerified: boolean; memberNo: string | null }

const ROUTES = {
  params: '#/system-configuration',
  businessRules: '#/ach-business-rules',
  appLogs: '#/system-audit-app-logs',
  coreApi: '#/open-core-banking-api',
  achApi: '#/ach-payments-api',
  originationProcessing: '#/ach-origination-processing',
  originationTransactions: '#/ach-origination-transactions',
  receivingProcessing: '#/ach-receiving-processing',
  receivingTransactions: '#/ach-receiving-transactions',
  ofacReview: '#/ofac-review',
  accounts: '#/accounts',
  external: '#/transfer-pay/external-ach',
  externalAccounts: '#/transfer-pay/external-accounts',
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
  const isCompliance = me?.roles.includes('COMPLIANCE')
  const isMember = me?.roles.includes('MEMBER')
  const memberReady = isMember && me?.mfaVerified
  const onTransferPay = hash === ROUTES.external || hash === ROUTES.externalAccounts

  const onTransactions = hash.startsWith(ROUTES.receivingTransactions)
  const adminElsewhere = hash === ROUTES.appLogs || hash === ROUTES.coreApi || hash === ROUTES.achApi || hash === ROUTES.businessRules
  const myScreens = SCREENS.filter((s) => me?.roles.includes(s.role))
  const screen = myScreens.find((s) => s.route === hash)

  let page = null
  if (signedOut) page = <LoginPage onSignedIn={loadMe} />
  else if (isAdmin && hash === ROUTES.appLogs) page = <AppLogPage />
  else if (isAdmin && hash === ROUTES.businessRules) page = <BusinessRulePage />
  else if (isAdmin && hash === ROUTES.coreApi) page = <ApiSpecPage key={hash} page="core" />
  else if (isAdmin && hash === ROUTES.achApi) page = <ApiSpecPage key={hash} page="ach" />
  else if (screen) page = <BackOfficePage key={screen.route} screen={screen} />
  else if (isOrigOps && hash === ROUTES.originationTransactions) page = <AchOriginationTransactionPage />
  else if (isOrigOps) page = <AchOriginationProcessingPage />
  else if (isRecvOps && onTransactions) {
    page = <AchReceivingTransactionPage route={ROUTES.receivingTransactions} fileID={decodeURIComponent(hash.slice(ROUTES.receivingTransactions.length + 1))} />
  } else if (isRecvOps) {
    page = <AchReceivingProcessingPage transactionsRoute={ROUTES.receivingTransactions} />
  } else if (isCompliance) page = <OfacReviewPage />
  else if (isAdmin) page = <ParameterPage />
  else if (isMember && !me?.mfaVerified) page = <MfaPage onVerified={loadMe} />
  else if (memberReady && hash === ROUTES.external) page = <ExternalTransferPage memberNo={me.memberNo ?? ''} />
  else if (memberReady && hash === ROUTES.externalAccounts) page = <ExternalAccountsPage memberNo={me.memberNo ?? ''} />
  else if (memberReady) page = <AccountsPage />
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
              <a className={hash === ROUTES.businessRules ? 'active' : ''} href={ROUTES.businessRules}>ACH Business Rules Management</a>
              <a className={hash === ROUTES.appLogs ? 'active' : ''} href={ROUTES.appLogs}>System Audit &amp; App Logs</a>
              <a className={hash === ROUTES.coreApi ? 'active' : ''} href={ROUTES.coreApi}>Open Core Banking API</a>
              <a className={hash === ROUTES.achApi ? 'active' : ''} href={ROUTES.achApi}>ACH Payments API</a>
            </>
          )}
          {isOrigOps && (
            <>
              <a className={!screen && hash !== ROUTES.originationTransactions ? 'active' : ''} href={ROUTES.originationProcessing}>
                ACH Origination Processing
              </a>
              <a className={hash === ROUTES.originationTransactions ? 'active' : ''} href={ROUTES.originationTransactions}>
                ACH Origination Transactions
              </a>
            </>
          )}
          {isCompliance && <a className="active" href={ROUTES.ofacReview}>OFAC Review</a>}
          {isRecvOps && (
            <>
              <a className={!onTransactions && !screen ? 'active' : ''} href={ROUTES.receivingProcessing}>ACH Receiving Processing</a>
              <a className={onTransactions ? 'active' : ''} href={ROUTES.receivingTransactions}>ACH Receiving Transactions</a>
            </>
          )}
          {myScreens.map((s) => (
            <a key={s.route} className={s === screen ? 'active' : ''} href={s.route}>{s.title}</a>
          ))}
          {memberReady && (
            <>
              <a className={!onTransferPay ? 'active' : ''} href={ROUTES.accounts}>Accounts</a>
              <div className="menu">
                <button
                  className={`menu-button${onTransferPay ? ' active' : ''}`}
                  aria-expanded={menuOpen}
                  onClick={() => setMenuOpen((o) => !o)}
                >
                  Transfer &amp; Pay ▾
                </button>
                {menuOpen && (
                  <div className="menu-list" role="menu">
                    <a role="menuitem" href={ROUTES.external}>Add ACH Transfer</a>
                    <a role="menuitem" href={ROUTES.externalAccounts}>External Bank Accounts</a>
                  </div>
                )}
              </div>
            </>
          )}
        </nav>
      )}
      <main>
        {meError && <p className="page-error">{meError}</p>}
        <Suspense fallback={<p className="empty-note">Loading…</p>}>{page}</Suspense>
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
