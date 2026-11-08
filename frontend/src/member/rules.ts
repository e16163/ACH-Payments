/** Client-side rules for the External ACH Transfer screen (UC-ACH-ORG-01). The server checks all of them again. */

export const MAX_AMOUNT = '99999999999.99' // The design's DECIMAL(13,2)

/** ABA 3-7-1 check digit (EX-01), as the server's AchFileValidator.isValidRouting (the demo server uses it). */
export function isValidRouting(routing: string): boolean {
  if (!/^\d{9}$/.test(routing)) return false
  const w = [3, 7, 1, 3, 7, 1, 3, 7, 1]
  return [...routing].reduce((sum, d, i) => sum + Number(d) * w[i], 0) % 10 === 0
}

/** "Amount Input Masking": keeps digits and one decimal point, at most 2 decimals, and adds thousands commas. */
export function formatAmountInput(raw: string): string {
  const cleaned = raw.replace(/[^\d.]/g, '')
  const dot = cleaned.indexOf('.')
  let whole = dot === -1 ? cleaned : cleaned.slice(0, dot)
  const cents = dot === -1 ? null : cleaned.slice(dot + 1).replace(/\./g, '').slice(0, 2)
  whole = whole.replace(/^0+(?=\d)/, '').slice(0, 11) // DECIMAL(13,2): at most 11 whole digits
  const withCommas = whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return cents === null ? withCommas : `${withCommas || '0'}.${cents}`
}

/** "1,500.5" -> "1500.50", or null if it isn't an amount. */
export function parseAmount(display: string): string | null {
  const plain = display.replace(/,/g, '')
  if (!/^\d+(\.\d{0,2})?$/.test(plain)) return null
  const [whole, cents = ''] = plain.split('.')
  return `${BigInt(whole)}.${cents.padEnd(2, '0')}`
}

function cents(amount: string): bigint {
  const [whole, c] = amount.split('.')
  return BigInt(whole) * 100n + BigInt(c)
}

export function amountError(display: string): string | null {
  const amount = parseAmount(display)
  if (amount === null || cents(amount) < 1n || cents(amount) > cents(MAX_AMOUNT)) {
    return 'Enter an amount between $0.01 and $99,999,999,999.99.'
  }
  return null
}

/** "1500.00" -> "$1,500.00" (exact, no floating point). */
export function money(amount: string | number): string {
  const [whole, c = '00'] = String(amount).split('.')
  return `$${whole.replace(/\B(?=(\d{3})+(?!\d))/g, ',')}.${c.padEnd(2, '0').slice(0, 2)}`
}

export function masked(last4: string | null | undefined): string {
  return `••••${last4 ?? ''}`
}

// ---- dates (ISO yyyy-mm-dd strings, so time zones never shift a day) ----

export function dayOfWeek(iso: string): number {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d)).getUTCDay()
}

/** "Date Picker Restrictions": not before the earliest date, not a weekend, not a Federal Reserve holiday. */
export function isSelectableDate(iso: string, earliest: string, holidays: ReadonlySet<string>): boolean {
  const dow = dayOfWeek(iso)
  return iso >= earliest && dow !== 0 && dow !== 6 && !holidays.has(iso)
}

export function formatDate(iso: string): string {
  const [y, m, d] = iso.split('-').map(Number)
  return new Date(Date.UTC(y, m - 1, d)).toLocaleDateString('en-US', {
    weekday: 'short', month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC',
  })
}

export const FREQUENCIES: { value: string; label: string }[] = [
  { value: 'ONETIME', label: 'One time' },
  { value: 'WEEKLY', label: 'Every week' },
  { value: 'BIWEEKLY', label: 'Every 2 weeks' },
  { value: 'MONTHLY', label: 'Every month' },
]

export function frequencyLabel(value: string): string {
  return FREQUENCIES.find((f) => f.value === value)?.label ?? value
}

export function memoError(memo: string): string | null {
  return /^[A-Za-z0-9 .,&-]{0,10}$/.test(memo.trim()) ? null : 'The memo can be up to 10 letters, numbers and spaces.'
}

export function countError(frequency: string, count: string): string | null {
  if (frequency === 'ONETIME') return null
  const n = Number(count)
  return Number.isInteger(n) && n >= 2 && n <= 52 ? null : 'Choose how many transfers to send: 2 to 52.'
}
