import { describe, expect, it } from 'vitest'
import { validateDesc, validateName, validateValue } from './validation'

describe('validateValue', () => {
  it.each([
    ['INTEGER', '3'],
    ['INTEGER', '-12'],
    ['DECIMAL', '1000000.00'],
    ['BOOLEAN', 'TRUE'],
    ['BOOLEAN', 'FALSE'],
    ['JSON', '{"windows":["10:30","14:45"]}'],
    ['STRING', '17:00:00'],
  ])('accepts %s %s', (type, value) => {
    expect(validateValue(type, value)).toBeNull()
  })

  it.each([
    ['INTEGER', 'five', 'Value must be a valid integer.'],
    ['INTEGER', '1.5', 'Value must be a valid integer.'],
    ['DECIMAL', '1,000.00', 'Value must be a valid decimal number.'],
    ['BOOLEAN', 'true', 'Value must be TRUE or FALSE.'],
    ['JSON', '{windows:', 'Value must be valid JSON.'],
    ['STRING', '', 'Value is required.'],
    ['STRING', 'x'.repeat(501), 'Value must be at most 500 characters.'],
  ])('rejects %s %s', (type, value, message) => {
    expect(validateValue(type, value)).toBe(message)
  })
})

describe('names and descriptions', () => {
  it('requires a name of at most 50 characters', () => {
    expect(validateName(' ')).not.toBeNull()
    expect(validateName('x'.repeat(51))).not.toBeNull()
    expect(validateName('MAX_RETRY_COUNT')).toBeNull()
  })

  it('limits descriptions to 255 characters', () => {
    expect(validateDesc('x'.repeat(256))).not.toBeNull()
    expect(validateDesc('')).toBeNull()
  })
})
