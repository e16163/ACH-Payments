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

  it('submits: PENDING_BATCH with a reference number, and holds the money', async () => {
    const r = await post('/transfers', await transfer()) as Record<string, unknown>
    expect(r.transferSerNo).toMatch(/^TRF-\d{8}-0001$/)
    expect(r).toMatchObject({ processStatus: 'PENDING_BATCH', ofacStatus: 'OFAC_PASSED', memoStatus: 'POSTED', recvName: 'JANE SMITH', memo: 'RENT', amount: 1500, total: 1500 })
    const accounts = await demoApi<{ acctNo: string; availBal: number }[]>('/member/accounts')
    expect(accounts.find((a) => a.acctNo === '100049281920')?.availBal).toBe(7000)
  })

  it('flags an OFAC name for review without holding the money', async () => {
    const r = await post('/transfers', await transfer({ recvName: 'Blocked Test Person' })) as Record<string, unknown>
    expect(r).toMatchObject({ processStatus: 'PENDING', ofacStatus: 'FLAGGED', memoStatus: 'REVIEW' })
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

describe('demo ACH Files (UC-ACH-EOD-01)', () => {
  type Status = {
    nextBusinessDate: string
    pending: { transferSerNo: string; effectiveDt: string }[]
    leftOut: { recvName: string; reason: string }[]
    files: { fileID: string; fedStatus: string; totalCreditCount: number; totalCreditAmt: number }[]
  }
  const status = () => demoApi<Status>('/eod/status')
  const run = (businessDate: string) => post('/eod/run', { businessDate }) as Promise<{ fileID: string | null; fedStatus: string; message: string }>
  const asAdmin = async () => {
    await demoLogout()
    await demoLogin('admin', 'admin123')
  }
  const available = async () => (await demoApi<{ acctNo: string; availBal: number }[]>('/member/accounts')).find((a) => a.acctNo === '100049281920')?.availBal
  const setFailures = (n: string) => demoApi('/params/ACH_ORIG/FED_SIM_FAIL_ATTEMPTS', {
    method: 'PUT',
    body: JSON.stringify({ paramDesc: 'Fed simulator', paramRawType: 'INTEGER', paramRawValue: n }),
  })

  it('only lets admins in', async () => {
    await memberWithMfa()
    expect((await error(demoApi('/eod/status'))).status).toBe(403)
  })

  it('sends a valid file, marks the transfer Processed, and a rerun adds nothing', async () => {
    await memberWithMfa()
    const r = await post('/transfers', await transfer()) as { transferSerNo: string; effectiveDt: string; estimatedArrival: string }
    expect(r.estimatedArrival > r.effectiveDt).toBe(true)
    await asAdmin()
    const s = await status()
    expect(s.pending).toHaveLength(1)
    expect(s.nextBusinessDate).toBe(r.effectiveDt)

    const result = await run(r.effectiveDt)
    expect(result.fedStatus).toBe('SENT')
    const { content } = await demoApi<{ content: string }>(`/eod/files/${result.fileID}/content`)
    const lines = content.trimEnd().split('\n')
    expect(lines.length % 10).toBe(0)
    expect(lines.every((l) => l.length === 94)).toBe(true)
    expect(lines[2]).toContain(r.transferSerNo.replace(/-/g, ''))
    expect(lines[1].slice(53, 63).trim()).toBe('RENT')

    expect((await run(r.effectiveDt)).fileID).toBeNull()
    expect((await status()).files).toHaveLength(1)
    expect((await status()).pending).toHaveLength(0)
    await demoLogout()
    await memberWithMfa()
    expect(await demoApi(`/transfers/${r.transferSerNo}`)).toMatchObject({ processStatus: 'PROCESSED' })
  })

  it('EX-01: a flagged item stays out and the clean one proceeds', async () => {
    await memberWithMfa()
    const clean = await post('/transfers', await transfer({ amount: '10.00' })) as { effectiveDt: string }
    await post('/transfers', await transfer({ recvName: 'Blocked Test Person', amount: '20.00' }))
    await asAdmin()
    await run(clean.effectiveDt)
    const s = await status()
    expect(s.files[0]).toMatchObject({ totalCreditCount: 1, totalCreditAmt: 10 })
    expect(s.leftOut).toEqual([expect.objectContaining({ recvName: 'BLOCKED TEST PERSON', reason: 'OFAC review' })])
  })

  it('EX-02: three failed attempts end in ERROR; a resend sends it; a sent file can\'t be resent', async () => {
    await memberWithMfa()
    const t = await post('/transfers', await transfer()) as { effectiveDt: string }
    await asAdmin()
    await setFailures('3')
    const r = await run(t.effectiveDt)
    expect(r.fedStatus).toBe('ERROR')
    await setFailures('2')
    expect(await post(`/eod/files/${r.fileID}/resend`, {})).toEqual({ fedStatus: 'SENT' })
    expect((await error(post(`/eod/files/${r.fileID}/resend`, {}))).message).toBe('Only a file in ERROR can be resent.')
    expect((await error(demoApi('/eod/files/NOPE/content'))).status).toBe(404)
  })

  it('makes each recurring payment its own row; short funds reject that one and the series goes on', async () => {
    await memberWithMfa()
    const first = await post('/transfers', await transfer({ amount: '6000.00', frequency: 'WEEKLY', transferCount: 3 })) as { effectiveDt: string; transferCount: number }
    expect(first.transferCount).toBe(3)
    await asAdmin()
    await run(first.effectiveDt)
    const second = (await status()).pending[0]
    await run(second.effectiveDt) // $2,500 left, so the $6,000 hold fails
    let s = await status()
    expect(s.leftOut).toEqual([expect.objectContaining({ reason: 'Insufficient funds' })])
    expect(s.files).toHaveLength(1)
    expect(s.pending).toHaveLength(1)
    await run(s.pending[0].effectiveDt) // the last one, also short
    s = await status()
    expect(s.leftOut).toHaveLength(2)
    expect(s.pending).toHaveLength(0)
    await demoLogout()
    await memberWithMfa()
    expect(await available()).toBe(2500)
  })

  it("doesn't send a reserved Nacha word as the entry description", async () => {
    await memberWithMfa()
    const t = await post('/transfers', await transfer({ memo: 'reversal' })) as { effectiveDt: string }
    await asAdmin()
    const { fileID } = await run(t.effectiveDt)
    const { content } = await demoApi<{ content: string }>(`/eod/files/${fileID}/content`)
    expect(content.split('\n')[1].slice(53, 63).trim()).toBe('TRANSFER')
  })

  it('builds no file on a day that is not a banking day', async () => {
    await demoLogin('admin', 'admin123')
    expect((await run('2030-01-05')).message).toBe("2030-01-05 isn't a banking day, so no file was built.")
  })
})
