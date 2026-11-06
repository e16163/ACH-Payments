import { describe, expect, it } from 'vitest'
import { cell, label } from './BackOfficePage'

describe('back-office labels and cells', () => {
  it('turns column names into labels', () => {
    expect(label('returnTraceNo')).toBe('Return Trace No')
    expect(label('nocCode')).toBe('NOC Code')
    expect(label('returnID')).toBe('Return ID')
  })

  it('formats values by column', () => {
    expect(cell('amount', 3450.5)).toBe('$3,450.50')
    expect(cell('totalDebitAmt', 0)).toBe('$0.00')
    expect(cell('actionTm', '2026-11-04T08:10:00')).toBe('2026-11-04 08:10:00')
    expect(cell('decision', null)).toBe('—')
    expect(cell('creditFlag', true)).toBe('Yes')
    expect(cell('returnCode', 'R03')).toBe('R03')
  })
})
