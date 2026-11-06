import { describe, expect, it } from 'vitest'
import { logTime, summary } from './AppLogPage'

describe('AppLog grid formatting', () => {
  it('always shows three millisecond digits', () => {
    expect(logTime('2026-11-06T11:20:30.123')).toBe('2026-11-06 11:20:30.123')
    expect(logTime('2026-11-06T11:20:30.12')).toBe('2026-11-06 11:20:30.120')
    expect(logTime('2026-11-06T11:20:30')).toBe('2026-11-06 11:20:30.000')
  })

  it('cuts messages to 100 characters', () => {
    expect(summary('x'.repeat(100))).toBe('x'.repeat(100))
    expect(summary('x'.repeat(101))).toBe(`${'x'.repeat(100)}…`)
  })
})
