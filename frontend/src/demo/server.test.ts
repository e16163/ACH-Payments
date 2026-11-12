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
    origAcctNo: '100049281920', externalAccountId: 'REC-SRV-20261102-01', amount: '1500.00', effectiveDt: earliest, frequency: 'ONETIME',
    transferCount: null, memo: 'rent', ...overrides,
  }
}

/** A registered external bank account for this holder name (the transfer's To list only offers registered ones). */
async function recipient(accountHolderName: string, accountNumber = '77700001') {
  const added = await post('/v1/holders/M10982347/external-accounts', {
    bankName: 'JPMorgan Chase Bank, N.A.', routingNumber: '021000021', accountNumber, accountType: 'CHECKING', accountHolderName,
  }) as { externalAccountId: string }
  return added.externalAccountId
}

beforeEach(() => store.clear())

describe('demo sign-in', () => {
  it('rejects a wrong password with the server message', async () => {
    expect((await error(demoLogin('member', 'nope'))).message).toBe('Incorrect username or password.')
  })

  it('needs a sign-in, then shows who is signed in, and signs out', async () => {
    expect((await error(demoApi('/me'))).status).toBe(401)
    await demoLogin('member', 'member123')
    expect(await demoApi('/me')).toEqual({ username: 'member', roles: ['MEMBER'], mfaVerified: false, memberNo: 'M10982347' })
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
    expect(r).toMatchObject({ processStatus: 'PENDING_BATCH', ofacStatus: 'OFAC_PASSED', memoStatus: 'POSTED', recvName: 'JOHN DOE', memo: 'RENT', amount: 1500, total: 1500 })
    const accounts = await demoApi<{ acctNo: string; availBal: number }[]>('/member/accounts')
    expect(accounts.find((a) => a.acctNo === '100049281920')?.availBal).toBe(7000)
  })

  it('flags an OFAC name for review without holding the money', async () => {
    const r = await post('/transfers', await transfer({ externalAccountId: await recipient('Blocked Test Person') })) as Record<string, unknown>
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
    await post('/v1/ach/receiving/files/receive', { sample: 'PAYROLL' })
    await demoLogin('admin', 'admin123')
    expect(await messages()).toContain(`ACH_RCV INFO User 'achrecv' received sample PAYROLL: PAYROLL.ach is waiting for the next processing window`)
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

  it('filters by app: the 9 apps and the system itself', async () => {
    await error(demoLogin('achrecv', 'wrong-secret'))
    await demoLogin('admin', 'admin123')
    expect((await logs('systemID=ACH_RCV')).items.map((l) => l.logAppId)).toEqual([1])
    expect((await logs('systemID=ACH_SYS')).items.map((l) => l.logMesg)).toEqual(["User 'admin' signed in", "Failed sign-in for user 'achrecv'"])
    expect((await logs('systemID=ACH_RCV_NOC')).total).toBe(0)
    expect((await error(logs('systemID=NOPE'))).status).toBe(400)
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
  const files = () => demoApi<File[]>(`${BASE}/files`)
  /** The Fed delivers the sample, then the 9:00 AM window processes it (ReceivingBlueprintIntegrationTest.receive). */
  const receive = async (sample: string) => {
    await post(`${BASE}/files/receive`, { sample })
    await post(`${BASE}/trigger/0900`, {})
    return (await files())[0]
  }
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

  it("runs Ba's steps 4-6 on a received file, as the real app does on the same sample", async () => {
    await asOperator()
    const r = await receive('PAYROLL')
    type Row = { tranID: number; recvAcctID: string; processStatus: string; returnCode: string | null; correctedAcctNo: string | null; processReason: string | null }
    const rows = (await demoApi<{ items: Row[] }>(`${BASE}/transactions?fileID=${r.fileID}&page=1&limit=100&sortBy=traceNo&sortDir=ASC`)).items
    const by = (acct: string) => rows.find((x) => x.recvAcctID === acct)!
    expect(by('987654321019').processStatus).toBe('MEMO_POSTED')
    expect(by('987654329019')).toMatchObject({ correctedAcctNo: '0987654329019', processReason: 'ZERO_PADDING' })
    expect(by('987654328019')).toMatchObject({ correctedAcctNo: '555000328019', processReason: 'KNOWN_ALIAS' })
    expect([by('987654330019').returnCode, by('987654327019').returnCode, by('987654326019').returnCode]).toEqual(['R03', 'R16', 'R02'])
    const rejected = await demoApi<{ total: number }>(`${BASE}/transactions?fileID=${r.fileID}&status=REJECTED&page=1&limit=10&sortBy=traceNo&sortDir=ASC`)
    expect(rejected.total).toBe(3)
  })

  it('returns retail debits that are stopped, blocked or short (R08, R10, R01)', async () => {
    await asOperator()
    type Row = { recvAcctID: string; returnCode: string | null }
    const retail = await receive('RETAIL')
    const debits = (await demoApi<{ items: Row[] }>(`${BASE}/transactions?fileID=${retail.fileID}&page=1&limit=100&sortBy=traceNo&sortDir=ASC`)).items
    const code = (acct: string) => debits.find((x) => x.recvAcctID === acct)!.returnCode
    expect([code('987654322019'), code('987654323019'), code('987654410019')]).toEqual(['R10', 'R08', 'R01'])
  })

  it('keeps achrecv out of the other screens', async () => {
    await asOperator()
    expect(await demoApi('/me')).toMatchObject({ username: 'achrecv', roles: ['ACH_RECV_OPS'] })
    expect((await error(demoApi('/params/systems'))).status).toBe(403)
    expect((await error(demoApi('/eod/status'))).status).toBe(403)
    expect((await error(demoApi('/v1/ach/processing/windows'))).status).toBe(403)
    expect((await error(demoApi('/transfers/dates'))).status).toBe(403)
  })

  it('keeps achorig out of the receiving screens and lets it use origination processing', async () => {
    await demoLogin('achorig', 'achorig123')
    expect(await demoApi('/me')).toMatchObject({ username: 'achorig', roles: ['ACH_ORIG_OPS'] })
    expect(await demoApi('/v1/ach/processing/windows')).toHaveProperty('schedules')
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
    expect((await page(r.fileID)).total).toBe(0)
    await demoLogin('admin', 'admin123')
    const log = await demoApi<{ items: { logMesg: string }[] }>('/v1/admin/logs?page=1&limit=5&level=WARN')
    expect(log.items[0].logMesg).toContain('Batch 3: total credits says 2675000 but the entries add up to 2670000')
  })

  it('EX-03: the same file twice is flagged and adds no entries', async () => {
    await asOperator()
    await receive('PAYROLL')
    const second = await receive('PAYROLL')
    expect(second.processStatus).toBe('DUPLICATE')
    expect((await page(second.fileID)).total).toBe(0)
  })

  it("ACH Receiving Processing: a received file waits for a window, the run is kept, and step 7 posts once", async () => {
    type Today = { schedules: { window_time: string }[]; waiting_files: string[]; runs: Record<string, unknown>[]; posting_file: Record<string, unknown> | null; business_date: string }
    const today = () => demoApi<Today>(`${BASE}/windows`)
    await asOperator()
    expect((await today()).schedules.map((w) => w.window_time)).toEqual(['06:00', '09:00', '12:00', '15:00', '18:00'])
    expect(await post(`${BASE}/files/receive`, { sample: 'PAYROLL' })).toEqual({ fileName: 'PAYROLL.ach', message: 'PAYROLL.ach is in fed/inbound, waiting for the next processing window.' })
    expect((await today()).waiting_files).toEqual(['PAYROLL.ach'])
    expect(await files()).toHaveLength(1) // only the design's sample, until a window runs

    await post(`${BASE}/trigger/0900`, {})
    const t = await today()
    expect(t.waiting_files).toEqual([])
    expect(t.runs).toEqual([expect.objectContaining({ window_time: '09:00', files: 1, validated: 1, entries: 50, matched: 47, auto_corrected: 2, manual_review: 1, rejected: 3, memo_posted: 47, run_by: 'achrecv' })])
    expect((await error(post(`${BASE}/trigger/0800`, {}))).status).toBe(400)

    const job = await post(`${BASE}/generate-posting-file`, { business_date: t.business_date }) as { total_records_posted: number }
    expect(job.total_records_posted).toBeGreaterThan(0)
    expect(await post(`${BASE}/generate-posting-file`, { business_date: t.business_date })).toEqual(job)
    expect((await today()).posting_file).toEqual(job)
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
  // On a banking day the demo starts with sample transfers for today; the 6:00 AM schedule sends them first.
  let seedFiles = 0
  beforeEach(async () => {
    await asOrigOps()
    await post('/v1/ach/processing/trigger-window', { window_time: '06:00' })
    seedFiles = (await post('/v1/ach/processing/send-to-fed', { schedule_id: 1 }) as { fileID: string | null }).fileID ? 1 : 0
    await demoLogout()
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
    expect((await status()).files).toHaveLength(seedFiles + 1)
    expect((await status()).pending).toHaveLength(0)
    await demoLogout()
    await memberWithMfa()
    expect(await demoApi(`/transfers/${r.transferSerNo}`)).toMatchObject({ processStatus: 'PROCESSED' })
  })

  it('EX-01: a flagged item stays out and the clean one proceeds', async () => {
    await memberWithMfa()
    const clean = await post('/transfers', await transfer({ amount: '10.00' })) as { effectiveDt: string }
    await post('/transfers', await transfer({ externalAccountId: await recipient('Blocked Test Person'), amount: '20.00' }))
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
    expect(s.leftOut).toEqual([expect.objectContaining({ reason: 'Failed the account, limit or funds check' })])
    expect(s.files).toHaveLength(seedFiles + 1)
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

  it("Ba's OFAC review: only achcomp resolves; Clear sends it in the next file, Block gives the hold back", async () => {
    await memberWithMfa()
    const hit = await post('/transfers', await transfer({ externalAccountId: await recipient('Blocked Test Person'), amount: '20.00' })) as { transferSerNo: string; effectiveDt: string }
    const other = await post('/transfers', await transfer({ externalAccountId: await recipient('Blocked Demo Entity', '77700002'), amount: '30.00' })) as { transferSerNo: string }
    const resolve = (transfer_id: string, resolution: string) => demoApi<{ current_status: string }>('/v1/compliance/ofac/resolve', {
      method: 'PATCH', body: JSON.stringify({ transfer_id, resolution }),
    })
    await asOrigOps()
    expect((await error(resolve(hit.transferSerNo, 'CLEARED_FALSE_POSITIVE'))).status).toBe(403)

    await demoLogin('achcomp', 'achcomp123')
    expect((await demoApi<{ transfer_id: string }[]>('/v1/compliance/ofac/flagged')).map((f) => f.transfer_id)).toEqual([hit.transferSerNo, other.transferSerNo])
    expect((await resolve(hit.transferSerNo, 'CLEARED_FALSE_POSITIVE')).current_status).toBe('CLEARED')
    expect((await resolve(other.transferSerNo, 'BLOCKED_CONFIRMED_MATCH')).current_status).toBe('BLOCKED')
    expect((await error(resolve(hit.transferSerNo, 'CLEARED_FALSE_POSITIVE'))).message).toBe(`${hit.transferSerNo} isn't waiting for OFAC review (ofacStatus CLEARED).`)
    expect(await demoApi('/v1/compliance/ofac/flagged')).toEqual([])

    await asOrigOps()
    const r = await run(hit.effectiveDt)
    const s = await status()
    expect(s.files[0]).toMatchObject({ fileID: r.fileID, totalCreditCount: 1, totalCreditAmt: 20 })
    expect(s.leftOut).toEqual([expect.objectContaining({ recvName: 'BLOCKED DEMO ENTITY', reason: 'OFAC blocked' })])
    await demoLogout()
    await memberWithMfa()
    expect(await available()).toBe(8500 - 20) // the $20 is held at the window after Clear; the blocked $30 was never held
  })

  it('builds no file on a day that is not a banking day', async () => {
    await asOrigOps()
    expect((await run('2030-01-05')).message).toBe("2030-01-05 isn't a banking day, so no file was built.")
  })
})

describe("demo ACH Origination Processing (Ba's critique, 2026-11-12)", () => {
  type Today = { business_date: string; schedules: { schedule_id: number; window_time: string; last_run: Record<string, unknown> | null }[] }

  it('lists the 4 schedules for today, and only lets achorig in', async () => {
    await demoLogin('achrecv', 'achrecv123')
    expect((await error(demoApi('/v1/ach/processing/windows'))).status).toBe(403)
    await demoLogin('achorig', 'achorig123')
    const t = await demoApi<Today>('/v1/ach/processing/windows')
    expect(t.schedules.map((w) => w.window_time)).toEqual(['06:00', '09:00', '12:00', '15:00'])
    expect(t.schedules.every((w) => w.last_run === null)).toBe(true)
    expect((await error(post('/v1/ach/processing/send-to-fed', { schedule_id: 9 }))).message).toBe('Choose a schedule.')
  })

  it("sends only the selected schedule's ready transactions, in a file with its batches", async (ctx) => {
    await demoLogin('achorig', 'achorig123')
    const probe = await post('/v1/ach/processing/send-to-fed', { schedule_id: 1 }) as { message: string }
    if (probe.message.includes("isn't a banking day")) ctx.skip() // no sample transfers then
    const ready = (id: number) => demoApi<{ transfer_id: string }[]>(`/v1/ach/processing/ready?schedule_id=${id}`)
    expect(await ready(2)).toEqual([])
    const r = await post('/v1/ach/processing/trigger-window', { window_time: '09:00' }) as { window_id: string; message: string }
    expect(r.message).toBe('ACH processing window 09:00: 3 due, 3 ready to post, 0 flagged, 0 rejected.')
    const t = await demoApi<Today>('/v1/ach/processing/windows')
    expect(t.schedules[1].last_run).toMatchObject({ window_id: r.window_id, extracted: 3, ready_to_post: 3, run_by: 'achorig' })
    expect(await ready(2)).toHaveLength(3)
    expect(await ready(3)).toEqual([])

    const sent = await post('/v1/ach/processing/send-to-fed', { schedule_id: 2 }) as { fileID: string; fedStatus: string }
    expect(sent.fedStatus).toBe('SENT')
    expect(await ready(2)).toEqual([])
    const files = await demoApi<{ file_id: string; schedule_id: number; total_credit_count: number; batches: { company_entry_desc: string; entry_count: number }[] }[]>('/v1/ach/processing/files?schedule_id=2')
    expect(files).toEqual([expect.objectContaining({ file_id: sent.fileID, schedule_id: 2, total_credit_count: 3 })])
    expect(files[0].batches.map((b) => [b.company_entry_desc, b.entry_count])).toEqual([['RENT', 1], ['TUITION', 1], ['UTILITIES', 1]])
    expect(await demoApi('/v1/ach/processing/files?schedule_id=3')).toEqual([])
  })
})

describe("demo External Bank Accounts (Ba's ach-origination-system.yaml)", () => {
  type Ext = { externalAccountId: string; bankName: string | null; maskedAccountNumber: string; accountType: string; status: string }
  const PATH = '/v1/holders/M10982347/external-accounts'
  const list = () => demoApi<Ext[]>(PATH)
  const add = (overrides: Record<string, unknown> = {}) => post(PATH, {
    bankName: 'JPMorgan Chase Bank, N.A.', routingNumber: '021000021', accountNumber: '55501234', accountType: 'SAVINGS', accountHolderName: 'Jane Smith', ...overrides,
  }) as Promise<{ externalAccountId: string; status: string }>

  it('starts with the sample account and puts a new one first, pending micro-deposits', async () => {
    await memberWithMfa()
    expect(await list()).toEqual([{ externalAccountId: 'REC-SRV-20261102-01', bankName: 'Sample Bank (from the design samples)', maskedAccountNumber: '••••1012', accountType: 'CHECKING', status: 'ACTIVE' }])
    const added = await add()
    expect(added.status).toBe('ACTIVE_PENDING_MICRO_DEPOSIT')
    expect((await list())[0]).toEqual({ externalAccountId: added.externalAccountId, bankName: 'JPMorgan Chase Bank, N.A.', maskedAccountNumber: '••••1234', accountType: 'SAVINGS', status: 'ACTIVE_PENDING_MICRO_DEPOSIT' })
    expect((await list())).toHaveLength(2)
  })

  it('checks the details like the server, and adds an account only once', async () => {
    await memberWithMfa()
    await add()
    expect((await error(add())).message).toBe('This account is already in your external bank accounts.')
    expect((await error(add({ routingNumber: '02100002' }))).message).toBe('Enter all 9 digits of the routing number.')
    expect((await error(add({ accountNumber: '123' }))).message).toBe('Enter the account number: 4 to 17 digits.')
    expect((await error(add({ accountType: 'MONEY_MARKET', accountNumber: '9999' }))).message).toBe('Choose Checking or Savings.')
    expect((await error(add({ bankName: ' ', accountNumber: '9999' }))).message).toBe('Enter the bank name.')
    expect((await error(add({ accountHolderName: '<script>', accountNumber: '9999' }))).message).toBe("Enter the account holder's name: up to 22 letters, numbers and spaces.")
    expect(await list()).toHaveLength(2)
  })

  it('for now accepts any 9-digit routing number; a bank missing from the directory has no name', async () => {
    await memberWithMfa()
    await add({ bankName: 'Navy Federal', routingNumber: '256074974' })
    expect((await list())[0]).toMatchObject({ bankName: null, maskedAccountNumber: '••••1234' })
    await add({ bankName: 'Typo Bank', routingNumber: '021000022' }) // fails the check digit, still saved
    expect(await list()).toHaveLength(3)
  })

  it('transfers only to a registered account: no bad check digit, and a pending one becomes active', async () => {
    await memberWithMfa()
    const pending = (await add()).externalAccountId
    const r = await post('/transfers', await transfer({ externalAccountId: pending, amount: '10.00' })) as Record<string, unknown>
    expect(r).toMatchObject({ recvName: 'JANE SMITH', bankName: 'JPMorgan Chase Bank, N.A.', acctLast4: '1234' })
    expect((await list()).find((a) => a.externalAccountId === pending)?.status).toBe('ACTIVE')
    expect(await list()).toHaveLength(2) // a transfer adds no account
    const navy = (await add({ bankName: 'Navy Federal', routingNumber: '256074974', accountNumber: '4444' })).externalAccountId
    expect(await post('/transfers/preview', await transfer({ externalAccountId: navy, amount: '10.00' }))).toMatchObject({ bankName: null })
    const typo = (await add({ bankName: 'Typo Bank', routingNumber: '021000022', accountNumber: '5555' })).externalAccountId
    expect((await error(post('/transfers/preview', await transfer({ externalAccountId: typo })))).message).toBe('Invalid routing number. Please check the 9-digit ABA number.')
    for (const id of [undefined, 'REC-NOPE']) {
      expect((await error(post('/transfers/preview', await transfer({ externalAccountId: id })))).message).toBe('Choose one of your external bank accounts to transfer to.')
    }
  })

  it('keeps other holders and staff out', async () => {
    await memberWithMfa()
    expect((await error(demoApi('/v1/holders/SOMEONE-ELSE/external-accounts'))).status).toBe(403)
    await demoLogin('admin', 'admin123')
    expect((await error(list())).status).toBe(403)
    await demoLogin('member', 'member123')
    expect((await error(list())).status).toBe(403) // MFA not passed yet
  })
})
