import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '../api'
import { demoApi, demoLogin, demoLogout } from './server'

// Vitest runs in Node, which has no sessionStorage.
const store = new Map<string, string>()
globalThis.sessionStorage = {
  getItem: (k: string) => store.get(k) ?? null,
  setItem: (k: string, v: string) => void store.set(k, v),
  removeItem: (k: string) => void store.delete(k),
  clear: () => store.clear(),
  key: () => null,
  length: 0,
} as Storage

async function error(p: Promise<unknown>): Promise<ApiError> {
  try {
    await p
  } catch (e) {
    if (e instanceof ApiError) return e
    throw e
  }
  throw new Error('expected an ApiError')
}

const post = (path: string, body: unknown) => demoApi(path, { method: 'POST', body: JSON.stringify(body) })

async function memberWithMfa() {
  await demoLogin('member', 'member123')
  const { demoCode } = await post('/member/mfa/send', {}) as { demoCode: string }
  await post('/member/mfa/verify', { code: demoCode })
}

async function transfer(overrides: Record<string, unknown> = {}) {
  const { earliest } = await demoApi<{ earliest: string }>('/transfers/dates')
  return {
    origAcctNo: '100049281920', recvName: 'Jane Smith', routingNo: '021000021', acctNo: '123456789',
    acctType: 'CHECKING', amount: '1500.00', effectiveDt: earliest, frequency: 'ONETIME',
    transferCount: null, memo: 'rent', ...overrides,
  }
}

beforeEach(() => store.clear())

describe('demo sign-in', () => {
  it('rejects a wrong password with the server message', async () => {
    expect((await error(demoLogin('member', 'nope'))).message).toBe('Incorrect username or password.')
  })

  it('needs a sign-in, then shows who is signed in, and signs out', async () => {
    expect((await error(demoApi('/me'))).status).toBe(401)
    await demoLogin('member', 'member123')
    expect(await demoApi('/me')).toEqual({ username: 'member', roles: ['MEMBER'], mfaVerified: false })
    await demoLogout()
    expect((await error(demoApi('/me'))).status).toBe(401)
  })

  it('keeps members out of transfers until MFA, and out of the Parameter UI', async () => {
    await demoLogin('member', 'member123')
    expect((await error(demoApi('/member/accounts'))).status).toBe(403)
    expect((await error(post('/member/mfa/verify', { code: '000000' }))).status).toBe(400)
    expect((await error(demoApi('/params/systems'))).status).toBe(403)
  })

  it('keeps admins out of member screens', async () => {
    await demoLogin('admin', 'admin123')
    expect((await error(demoApi('/transfers/dates'))).status).toBe(403)
    expect(await demoApi<unknown[]>('/params/systems')).toHaveLength(6)
  })
})

describe('demo transfer (UC-ACH-ORG-01)', () => {
  beforeEach(memberWithMfa)

  it('looks up the bank, or gives EX-01 for a bad routing number', async () => {
    expect(await demoApi('/routing/021000021')).toEqual({ routingNo: '021000021', bankName: 'JPMorgan Chase Bank, N.A.' })
    expect((await error(demoApi('/routing/051000031'))).message).toBe('Invalid routing number. Please check the 9-digit ABA number.')
  })

  it("shows Ba's EX-02 limit message over $25,000 from checking", async () => {
    const e = await error(post('/transfers/preview', await transfer({ amount: '25000.01' })))
    expect(e.kind).toBe('LIMIT')
    expect(e.message).toBe('This transfer exceeds your daily external limit of $25,000. Please reduce the amount or contact support.')
  })

  it("counts today's earlier transfers against the limit (checked before funds, as on the server)", async () => {
    await post('/transfers', await transfer({ amount: '8000.00' }))
    expect((await error(post('/transfers/preview', await transfer({ amount: '17000.01' })))).kind).toBe('LIMIT')
    const e = await error(post('/transfers/preview', await transfer({ amount: '500.01' })))
    expect(e.message).toBe("Your available balance of $500 isn't enough for this transfer.")
  })

  it('submits: PENDING with a reference number, and holds the money', async () => {
    const r = await post('/transfers', await transfer()) as Record<string, unknown>
    expect(r.transferSerNo).toMatch(/^TRF-\d{8}-0001$/)
    expect(r).toMatchObject({ processStatus: 'PENDING', memoStatus: 'POSTED', recvName: 'JANE SMITH', memo: 'RENT', amount: 1500, total: 1500 })
    const accounts = await demoApi<{ acctNo: string; availBal: number }[]>('/member/accounts')
    expect(accounts.find((a) => a.acctNo === '100049281920')?.availBal).toBe(7000)
  })

  it('flags an OFAC name for review without holding the money', async () => {
    const r = await post('/transfers', await transfer({ recvName: 'Blocked Test Person' })) as Record<string, unknown>
    expect(r).toMatchObject({ ofacStatus: 'FLAGGED', memoStatus: 'REVIEW' })
  })

  it('rejects amounts with more than 2 decimals and weekend dates', async () => {
    expect((await error(post('/transfers/preview', await transfer({ amount: '1.001' })))).message).toBe('Enter an amount between $0.01 and $99,999,999,999.99.')
    expect((await error(post('/transfers/preview', await transfer({ effectiveDt: '2030-01-05' })))).message).toBe(
      "Choose a banking day. Weekends and Federal Reserve holidays aren't available.",
    )
  })
})

describe('demo Parameter UI (UC-SYS-PARAM-01)', () => {
  beforeEach(() => demoLogin('admin', 'admin123'))

  it('saves a parameter with the admin as lastModBy, and the fee then applies to transfers', async () => {
    const saved = await demoApi<Record<string, string>>('/params/ACH_ORIG/EXTERNAL_XFER_FEE', {
      method: 'PUT',
      body: JSON.stringify({ paramDesc: 'Fee', paramRawType: 'DECIMAL', paramRawValue: '2.50' }),
    })
    expect(saved).toMatchObject({ paramRawValue: '2.50', lastModBy: 'admin' })
    await demoLogout()
    await memberWithMfa()
    expect(await post('/transfers/preview', await transfer())).toMatchObject({ fee: 2.5, total: 1502.5 })
  })

  it('gives EX-01 for a value that does not match its type', async () => {
    const e = await error(demoApi('/params/ACH_ORIG/MAX_RETRY', {
      method: 'PUT',
      body: JSON.stringify({ paramDesc: '', paramRawType: 'INTEGER', paramRawValue: 'abc' }),
    }))
    expect(e.message).toBe('Value must be a valid integer.')
  })
})
