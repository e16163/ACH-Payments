const BASE = `${import.meta.env.BASE_URL}api`

/** The GitHub Pages build (VITE_DEMO=1) has no server: the API runs in the browser on demo data (src/demo). */
export const DEMO = import.meta.env.VITE_DEMO === '1'

export class ApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
    readonly kind?: string,
  ) {
    super(message)
  }
}

/** Spring Security puts the CSRF token in this cookie; writes must echo it back in a header. */
function csrfToken(): string {
  const match = document.cookie.match(/(?:^|; )XSRF-TOKEN=([^;]*)/)
  return match ? decodeURIComponent(match[1]) : ''
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  if (DEMO) return (await import('./demo/server')).demoApi<T>(path, init)
  const headers = new Headers(init.headers)
  if (init.body) headers.set('Content-Type', 'application/json')
  if (init.method && init.method !== 'GET') headers.set('X-XSRF-TOKEN', csrfToken())

  const res = await fetch(`${BASE}${path}`, { ...init, headers })
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new ApiError(res.status, body?.error ?? `Request failed (HTTP ${res.status})`, body?.kind)
  }
  return res.json()
}

/** The sign-in page posts a form, as Spring Security's form login expects. */
export async function login(username: string, password: string): Promise<void> {
  if (DEMO) return (await import('./demo/server')).demoLogin(username, password)
  if (!csrfToken()) await fetch(`${BASE}/health`) // gets the XSRF-TOKEN cookie on a first visit
  const res = await fetch(`${BASE}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded', 'X-XSRF-TOKEN': csrfToken() },
    body: new URLSearchParams({ username, password }),
  })
  if (!res.ok) {
    const body = await res.json().catch(() => null)
    throw new ApiError(res.status, body?.error ?? `Sign-in failed (HTTP ${res.status})`)
  }
}

export async function logout(): Promise<void> {
  if (DEMO) return (await import('./demo/server')).demoLogout()
  await fetch(`${BASE}/logout`, { method: 'POST', headers: { 'X-XSRF-TOKEN': csrfToken() } })
}
