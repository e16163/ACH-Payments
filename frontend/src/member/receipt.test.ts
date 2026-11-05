import { describe, expect, it } from 'vitest'
import { receiptText, statusLabel } from './receipt'
import type { Receipt } from './types'

const r: Receipt = {
  transferSerNo: 'TRF-20261104-0001', processStatus: 'PENDING_BATCH', ofacStatus: 'OFAC_PASSED', memoStatus: 'POSTED',
  recvName: 'JANE SMITH', bankName: 'JPMorgan Chase Bank, N.A.', routingNo: '021000021', acctLast4: '6789',
  acctType: 'CHECKING', fromAcctName: 'Primary Checking', fromAcctLast4: '1920', amount: '1500.00', fee: '0.00',
  total: '1500.00', effectiveDt: '2026-11-05', estimatedArrival: '2026-11-06', frequency: 'ONETIME', transferCount: 1,
  memo: 'RENT', submitted: '2026-11-04T10:15:00',
}

describe('receipt', () => {
  it('has the reference number, tracking details and masked accounts only', () => {
    const text = receiptText(r)
    expect(text).toContain('TRF-20261104-0001')
    expect(text).toContain('Status:             Scheduled')
    expect(text).toContain('Estimated arrival:  Fri, Nov 6, 2026')
    expect(text).toContain('••••6789')
    expect(text).toContain('$1,500.00')
    expect(text).not.toContain('123456789')
  })
  it('shows an OFAC hit as under review', () => {
    expect(statusLabel({ ...r, ofacStatus: 'FLAGGED' })).toBe('Under review')
  })
})

describe('statusLabel after the EOD file', () => {
  it('never shows a raw status to a member', () => {
    expect(statusLabel({ ofacStatus: 'OFAC_PASSED', processStatus: 'PENDING_BATCH' })).toBe('Scheduled')
    expect(statusLabel({ ofacStatus: 'OFAC_PASSED', processStatus: 'PROCESSED' })).toBe('Processed')
    expect(statusLabel({ ofacStatus: 'OFAC_PASSED', processStatus: 'REJECTED' })).toBe('Rejected')
  })
})
