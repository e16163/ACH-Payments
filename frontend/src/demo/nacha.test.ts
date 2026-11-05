import { describe, expect, it } from 'vitest'
import { buildNachaFile, type NachaFile } from './nacha'
import golden from '../../../src/test/resources/nacha/eod-golden.ach?raw'
import input from '../../../src/test/resources/nacha/eod-golden-input.json'

describe('the demo NACHA builder', () => {
  it('matches the same golden file as the Java server', () => {
    expect(buildNachaFile(input as NachaFile)).toBe(golden)
  })

  it('adds no padding to a full block, and one more record starts another', () => {
    const file = input as NachaFile
    const batch = file.batches[0]
    const withEntries = (n: number) => ({ ...file, batches: [{ ...batch, entries: Array.from({ length: n }, () => batch.entries[0]) }] })
    const six = buildNachaFile(withEntries(6)).trimEnd().split('\n')
    expect(six).toHaveLength(10)
    expect(six[9].startsWith('9000001000001')).toBe(true)
    const seven = buildNachaFile(withEntries(7)).trimEnd().split('\n')
    expect(seven).toHaveLength(20)
    expect(seven[10].startsWith('9000001000002')).toBe(true)
  })

  it('pads to a multiple of 10 records of 94 characters', () => {
    const lines = buildNachaFile(input as NachaFile).trimEnd().split('\n')
    expect(lines).toHaveLength(10)
    expect(lines.every((l) => l.length === 94)).toBe(true)
    expect(lines[9]).toBe('9'.repeat(94))
  })
})
