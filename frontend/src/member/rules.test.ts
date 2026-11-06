import { describe, expect, it } from 'vitest'
import {
  accountNumberError, amountError, countError, formatAmountInput, isSelectableDate, isValidRouting, memoError, money,
  nameError, parseAmount,
} from './rules'

describe('routing check digit (EX-01)', () => {
  it('accepts valid numbers and rejects bad ones', () => {
    expect(isValidRouting('021000021')).toBe(true)
    expect(isValidRouting('051000033')).toBe(true)
    expect(isValidRouting('051000031')).toBe(false) // The sample: bad check digit
    expect(isValidRouting('02100002')).toBe(false)
    expect(isValidRouting('02100002A')).toBe(false)
  })
})

describe('amount mask', () => {
  it('adds commas and keeps at most 2 decimals', () => {
    expect(formatAmountInput('1500')).toBe('1,500')
    expect(formatAmountInput('1234567.891')).toBe('1,234,567.89')
    expect(formatAmountInput('$1,2a3')).toBe('123')
    expect(formatAmountInput('.5')).toBe('0.5')
    expect(formatAmountInput('007')).toBe('7')
    expect(formatAmountInput('1.2.3')).toBe('1.23')
  })
  it('caps whole dollars at 11 digits (DECIMAL(13,2))', () => {
    expect(formatAmountInput('123456789012345')).toBe('12,345,678,901')
  })
  it('parses to an exact decimal string', () => {
    expect(parseAmount('1,500')).toBe('1500.00')
    expect(parseAmount('1,500.5')).toBe('1500.50')
    expect(parseAmount('')).toBeNull()
    expect(parseAmount('abc')).toBeNull()
  })
  it('guards the range', () => {
    expect(amountError('0.00')).not.toBeNull()
    expect(amountError('0.01')).toBeNull()
    expect(amountError('99,999,999,999.99')).toBeNull()
    expect(amountError('')).not.toBeNull()
  })
  it('formats money without floating point', () => {
    expect(money('1500.00')).toBe('$1,500.00')
    expect(money('25000')).toBe('$25,000.00')
    expect(money('99999999999.99')).toBe('$99,999,999,999.99')
  })
})

describe('date picker restrictions', () => {
  const holidays = new Set(['2026-11-26'])
  it('blocks weekends, holidays and dates before the earliest', () => {
    expect(isSelectableDate('2026-11-27', '2026-11-27', holidays)).toBe(true) // Fri
    expect(isSelectableDate('2026-11-26', '2026-11-25', holidays)).toBe(false) // Thanksgiving
    expect(isSelectableDate('2026-11-28', '2026-11-25', holidays)).toBe(false) // Sat
    expect(isSelectableDate('2026-11-29', '2026-11-25', holidays)).toBe(false) // Sun
    expect(isSelectableDate('2026-11-24', '2026-11-25', holidays)).toBe(false) // before earliest
  })
})

describe('field rules match the server', () => {
  it('checks name, account, memo and count', () => {
    expect(nameError('Jane Smith')).toBeNull()
    expect(nameError('A'.repeat(23))).not.toBeNull()
    expect(nameError('')).not.toBeNull()
    expect(accountNumberError('123456789')).toBeNull()
    expect(accountNumberError('123')).not.toBeNull()
    expect(accountNumberError('12AB5678')).not.toBeNull()
    expect(memoError('Rent')).toBeNull()
    expect(memoError('ELEVENCHARS')).not.toBeNull()
    expect(countError('ONETIME', '')).toBeNull()
    expect(countError('MONTHLY', '1')).not.toBeNull()
    expect(countError('MONTHLY', '6')).toBeNull()
  })
})
