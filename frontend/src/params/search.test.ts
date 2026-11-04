import { describe, expect, it } from 'vitest'
import type { AppParam } from './ParameterPage'
import { searchParams } from './search'

const row = (paramName: string, paramDesc: string | null, paramRawValue: string): AppParam => ({
  systemName: 'ACH_ORIG', paramName, paramDesc, paramRawType: 'STRING', paramRawValue, lastModTm: '', lastModBy: '',
})
const rows = [row('FED_FILE_CUTOFF_TIME', 'Cutoff time for building daily Fed file', '17:00:00'),
  row('credit_limit', null, '5000.00')]

describe('searchParams', () => {
  it('returns everything for an empty or blank query', () => {
    expect(searchParams(rows, '')).toEqual(rows)
    expect(searchParams(rows, '   ')).toEqual(rows)
  })
  it('matches the name, ignoring case', () => {
    expect(searchParams(rows, 'cutoff').map((p) => p.paramName)).toEqual(['FED_FILE_CUTOFF_TIME'])
    expect(searchParams(rows, 'CREDIT').map((p) => p.paramName)).toEqual(['credit_limit'])
  })
  it('matches the description and the value', () => {
    expect(searchParams(rows, 'daily fed').map((p) => p.paramName)).toEqual(['FED_FILE_CUTOFF_TIME'])
    expect(searchParams(rows, '5000').map((p) => p.paramName)).toEqual(['credit_limit'])
  })
  it('handles a missing description and no matches', () => {
    expect(searchParams(rows, 'nothing-like-this')).toEqual([])
  })
})
