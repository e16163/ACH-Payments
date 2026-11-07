import { beforeEach, describe, expect, it } from 'vitest'
import { ApiError } from '../api'
import { SCREENS } from '../backoffice/screens'
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
    expect(await demoApi<unknown[]>('/params/systems')).toHaveLength(9)
  })
})

describe('demo transfer (UC-ACH-ORG-01)', () => {
  beforeEach(memberWithMfa)

  it('looks up the bank, or gives EX-01 for a bad routing number', async () => {
    expect(await demoApi('/routing/021000021')).toEqual({ routingNo: '021000021', bankName: 'JPMorgan Chase Bank, N.A.' })
    expect((await error(demoApi('/routing/051000031'))).message).toBe('Invalid routing number. Please check the 9-digit ABA number.')
  })

  it("shows the design's EX-02 limit message over $25,000 from checking", async () => {
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

describe('demo AppLog (UC-SYS-LOG-01)', () => {
  type Log = { logAppId: number; systemID: string | null; logLevel: string; logMesg: string; logTm: string }
  type Page = { items: Log[]; total: number; page: number; limit: number }
  const logs = (query = 'page=1&limit=20&level=ALL') => demoApi<Page>(`/v1/admin/logs?${query}`)
  const messages = async () => (await logs()).items.map((l) => `${l.systemID} ${l.logLevel} ${l.logMesg}`)

  it('logs sign-ins, failed sign-ins and sign-outs with their systems', async () => {
    await error(demoLogin('achrecv', 'wrong-secret'))
    await demoLogin('achrecv', 'achrecv123')
    await demoLogout()
    await demoLogin('achorig', 'achorig123')
    await demoLogout()
    await demoLogin('admin', 'admin123')
    const seen = await messages()
    expect(seen).toEqual(expect.arrayContaining([
      "ACH_SYS WARN Failed sign-in for user 'achrecv'", "ACH_RCV INFO User 'achrecv' signed in", "ACH_RCV INFO User 'achrecv' signed out",
      "ACH_ORIG INFO User 'achorig' signed in", "ACH_ORIG INFO User 'achorig' signed out", "ACH_SYS INFO User 'admin' signed in",
    ]))
    expect(seen.join()).not.toContain('wrong-secret')
  })

  it('logs a saved parameter and a denied call, and keeps the logs for admins', async () => {
    await demoLogin('achrecv', 'achrecv123')
    expect((await error(logs())).status).toBe(403)
    await demoLogin('admin', 'admin123')
    await demoApi('/params/ACH_ORIG/EXTERNAL_XFER_FEE', { method: 'PUT', body: JSON.stringify({ paramDesc: 'Fee', paramRawType: 'DECIMAL', paramRawValue: '2.50' }) })
    expect(await messages()).toEqual(expect.arrayContaining([
      "ACH_SYS WARN Access denied: user 'achrecv' attempted GET /api/v1/admin/logs?page=1&limit=20&level=ALL",
      "ACH_ORIG INFO User 'admin' saved parameter ACH_ORIG/EXTERNAL_XFER_FEE = 2.50",
    ]))
  })

  it('never logs a sign-in name that is not a login', async () => {
    await error(demoLogin('my-password-in-the-wrong-box', 'x'))
    await demoLogin('admin', 'admin123')
    expect(await messages()).toContain("ACH_SYS WARN Failed sign-in for user '(unknown)'")
    expect((await messages()).join()).not.toContain('wrong-box')
  })

  it('logs a received file', async () => {
    await demoLogin('achrecv', 'achrecv123')
    const r = (await post('/v1/ach/receiving/files/receive', { sample: 'PAYROLL' }) as { fileID: string }[])[0]
    await demoLogin('admin', 'admin123')
    expect(await messages()).toContain(`ACH_RCV INFO User 'achrecv' received sample PAYROLL: ${r.fileID} VALIDATED`)
  })

  it('filters by level, keyword and date, newest first', async () => {
    await demoLogin('admin', 'admin123')
    expect((await logs('level=ERROR')).items.map((l) => l.logAppId)).toEqual([1])
    expect((await logs('search=HEADER')).total).toBe(1)
    expect((await logs('search=nothing-like-this')).total).toBe(0)
    expect((await logs('from=2026-11-03&to=2026-11-03')).items.map((l) => l.logAppId)).toEqual([1])
    expect((await logs('to=2026-11-02')).total).toBe(0)
    expect((await error(logs('from=2026-11-04&to=2026-11-03'))).status).toBe(400)
    expect((await logs()).items[0].logMesg).toBe("User 'admin' signed in")
  })

  it('returns the stack trace for one entry', async () => {
    await demoLogin('admin', 'admin123')
    expect(await demoApi('/v1/admin/logs/1')).toMatchObject({ logExceptionData: expect.stringContaining('IllegalArgumentException') })
    expect((await error(demoApi('/v1/admin/logs/999'))).status).toBe(404)
  })
})

describe('demo ACH Receiving (UC-ACH-RCV-01, UC-ACH-RCV-02)', () => {
  type File = { fileID: string; processStatus: string; entryCount: number; totalCreditAmt: number; totalDebitAmt: number }
  type Page = { items: { traceNo: string; tranID: number }[]; total: number }
  const BASE = '/v1/ach/receiving'
  const asOperator = () => demoLogin('achrecv', 'achrecv123')
  const receive = async (sample: string) => (await post(`${BASE}/files/receive`, { sample }) as { fileID: string; processStatus: string; message: string }[])[0]
  const files = () => demoApi<File[]>(`${BASE}/files`)
  const page = (fileID: string, n = 1) => demoApi<Page>(`${BASE}/transactions?fileID=${fileID}&page=${n}&limit=10&sortBy=traceNo&sortDir=ASC`)

  it("starts with the sample file for operators, and keeps admins and members out", async () => {
    await asOperator()
    expect(await files()).toEqual([expect.objectContaining({ fileID: 'ACHFILE-88392', processStatus: 'PROCESSED', entryCount: 2, totalCreditAmt: 3450.5, totalDebitAmt: 3450.5 })])
    await demoLogin('admin', 'admin123')
    expect((await error(files())).status).toBe(403)
    expect((await error(post(`${BASE}/files/receive`, { sample: 'PAYROLL' }))).status).toBe(403)
    await memberWithMfa()
    expect((await error(files())).status).toBe(403)
  })

  it('keeps achrecv out of the other screens', async () => {
    await asOperator()
    expect(await demoApi('/me')).toMatchObject({ username: 'achrecv', roles: ['ACH_RECV_OPS'] })
    expect((await error(demoApi('/params/systems'))).status).toBe(403)
    expect((await error(demoApi('/eod/status'))).status).toBe(403)
    expect((await error(demoApi('/transfers/dates'))).status).toBe(403)
  })

  it('keeps achorig out of the receiving screens and lets it use the origination file', async () => {
    await demoLogin('achorig', 'achorig123')
    expect(await demoApi('/me')).toMatchObject({ username: 'achorig', roles: ['ACH_ORIG_OPS'] })
    expect(await demoApi('/eod/status')).toHaveProperty('pending')
    expect((await error(files())).status).toBe(403)
    expect((await error(demoApi('/params/systems'))).status).toBe(403)
  })

  it('has no ACHOperator login any more', async () => {
    expect((await error(demoLogin('ACHOperator', 'operator123'))).status).toBe(401)
  })

  it('serves every back-office list to its own login only', async () => {
    for (const screen of SCREENS) {
      await demoLogin(screen.role === 'ACH_ORIG_OPS' ? 'achorig' : 'achrecv', screen.role === 'ACH_ORIG_OPS' ? 'achorig123' : 'achrecv123')
      for (const section of screen.sections) {
        const rows = await demoApi<Record<string, unknown>[]>(section.path)
        expect(rows.length).toBeGreaterThan(0)
        for (const column of section.columns) expect(rows[0]).toHaveProperty(column)
      }
      await demoLogin('admin', 'admin123')
      expect((await error(demoApi(screen.sections[0].path))).status).toBe(403)
      await demoLogin(screen.role === 'ACH_ORIG_OPS' ? 'achrecv' : 'achorig', screen.role === 'ACH_ORIG_OPS' ? 'achrecv123' : 'achorig123')
      expect((await error(demoApi(screen.sections[0].path))).status).toBe(403)
    }
  })

  it('saves sample 1 with the same totals as the Java engine', async () => {
    await asOperator()
    const r = await receive('PAYROLL')
    expect(r.processStatus).toBe('VALIDATED')
    expect((await files())[0]).toMatchObject({ fileID: r.fileID, entryCount: 50, totalCreditAmt: 129450, totalDebitAmt: 0 })
  })

  it('saves sample 2 with debits and credits', async () => {
    await asOperator()
    const r = await receive('RETAIL')
    expect(r.processStatus).toBe('VALIDATED')
    expect((await files())[0]).toMatchObject({ entryCount: 50, totalCreditAmt: 71840, totalDebitAmt: 1144.9 })
  })

  it('pages 10 rows by trace number and shows all 24 fields of a row', async () => {
    await asOperator()
    const { fileID } = await receive('PAYROLL')
    const first = await page(fileID)
    expect(first.total).toBe(50)
    expect(first.items).toHaveLength(10)
    expect(first.items[0].traceNo).toBe('198765430000001')
    expect((await page(fileID, 5)).items[9].traceNo).toBe('198765430000050')
    const d = await demoApi<Record<string, unknown>>(`${BASE}/transactions/${first.items[0].tranID}`)
    expect(d).toMatchObject({ fileID, batchID: `${fileID}-B001`, creditFlag: 1, tranCode: '22', companyName: 'ACME PAYROLL', companyID: '1900483920', companyDiscData: 'WEEKLY PAYROLL', stdEntryCode: 'PPD', companyEntryDesc: 'PAIDROLL', descriptiveDt: '2026-11-06', effectiveDt: '2026-11-06', settlementDt: '2026-11-06', originatorID: '1900483920', amount: 1500, recvAcctID: '987654321019', recvName: 'JOHN DOE', traceNo: '198765430000001', fedStatus: 'SETTLED', sourceID: 'FED_ACH_INBOUND', transferVersion: 1 })
    expect(d.transferSerNo).toMatch(/^TRF-\d{8}-0001$/)
  })

  it('EX-01: a tampered file saves nothing', async () => {
    await asOperator()
    const r = await receive('TAMPERED')
    expect(r.processStatus).toBe('REJECTED_SIG_FAIL')
    expect((await page(r.fileID)).total).toBe(0)
  })

  it('EX-02: a wrong batch total saves nothing, with the same message as the server', async () => {
    await asOperator()
    const r = await receive('OUT_OF_BALANCE')
    expect(r.processStatus).toBe('OUT_OF_BALANCE')
    expect(r.message).toContain('Batch 3: total credits says 2675000 but the entries add up to 2670000')
    expect((await page(r.fileID)).total).toBe(0)
  })

  it('EX-03: the same file twice is flagged and adds no entries', async () => {
    await asOperator()
    await receive('PAYROLL')
    const second = await receive('PAYROLL')
    expect(second.processStatus).toBe('DUPLICATE')
    expect((await page(second.fileID)).total).toBe(0)
  })
})

describe('demo ACH Origination File (UC-ACH-EOD-01)', () => {
  type Status = {
    nextBusinessDate: string
    pending: { transferSerNo: string; effectiveDt: string }[]
    leftOut: { recvName: string; reason: string }[]
    files: { fileID: string; fedStatus: string; totalCreditCount: number; totalCreditAmt: number }[]
  }
  const status = () => demoApi<Status>('/eod/status')
  const run = (businessDate: string) => post('/eod/run', { businessDate }) as Promise<{ fileID: string | null; fedStatus: string; message: string }>
  const asOrigOps = async () => {
    await demoLogout()
    await demoLogin('achorig', 'achorig123')
  }
  const available = async () => (await demoApi<{ acctNo: string; availBal: number }[]>('/member/accounts')).find((a) => a.acctNo === '100049281920')?.availBal
  const setFailures = (n: string) => demoApi('/params/ACH_ORIG/FED_SIM_FAIL_ATTEMPTS', {
    method: 'PUT',
    body: JSON.stringify({ paramDesc: 'Fed simulator', paramRawType: 'INTEGER', paramRawValue: n }),
  })

  it('only lets achorig in', async () => {
    await memberWithMfa()
    expect((await error(demoApi('/eod/status'))).status).toBe(403)
    await demoLogin('admin', 'admin123')
    expect((await error(demoApi('/eod/status'))).status).toBe(403)
    expect((await error(run('2030-01-07'))).status).toBe(403)
  })

  it('sends a valid file, marks the transfer Processed, and a rerun adds nothing', async () => {
    await memberWithMfa()
    const r = await post('/transfers', await transfer()) as { transferSerNo: string; effectiveDt: string; estimatedArrival: string }
    expect(r.estimatedArrival > r.effectiveDt).toBe(true)
    await asOrigOps()
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
    await asOrigOps()
    await run(clean.effectiveDt)
    const s = await status()
    expect(s.files[0]).toMatchObject({ totalCreditCount: 1, totalCreditAmt: 10 })
    expect(s.leftOut).toEqual([expect.objectContaining({ recvName: 'BLOCKED TEST PERSON', reason: 'OFAC review' })])
  })

  it('EX-02: three failed attempts end in ERROR; a resend sends it; a sent file can\'t be resent', async () => {
    await memberWithMfa()
    const t = await post('/transfers', await transfer()) as { effectiveDt: string }
    await demoLogin('admin', 'admin123') // the Fed simulator's failure count is a parameter, which only admins edit
    await setFailures('3')
    await asOrigOps()
    const r = await run(t.effectiveDt)
    expect(r.fedStatus).toBe('ERROR')
    await demoLogin('admin', 'admin123')
    await setFailures('2')
    await asOrigOps()
    expect(await post(`/eod/files/${r.fileID}/resend`, {})).toEqual({ fedStatus: 'SENT' })
    expect((await error(post(`/eod/files/${r.fileID}/resend`, {}))).message).toBe('Only a file in ERROR can be resent.')
    expect((await error(demoApi('/eod/files/NOPE/content'))).status).toBe(404)
  })

  it('makes each recurring payment its own row; short funds reject that one and the series goes on', async () => {
    await memberWithMfa()
    const first = await post('/transfers', await transfer({ amount: '6000.00', frequency: 'WEEKLY', transferCount: 3 })) as { effectiveDt: string; transferCount: number }
    expect(first.transferCount).toBe(3)
    await asOrigOps()
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
    await asOrigOps()
    const { fileID } = await run(t.effectiveDt)
    const { content } = await demoApi<{ content: string }>(`/eod/files/${fileID}/content`)
    expect(content.split('\n')[1].slice(53, 63).trim()).toBe('TRANSFER')
  })

  it('builds no file on a day that is not a banking day', async () => {
    await asOrigOps()
    expect((await run('2030-01-05')).message).toBe("2030-01-05 isn't a banking day, so no file was built.")
  })
})
