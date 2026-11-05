import { describe, expect, it } from 'vitest'
import { buildNachaFile, type NachaFile } from './nacha'
import golden from '../../../src/test/resources/nacha/eod-golden.ach?raw'
import input from '../../../src/test/resources/nacha/eod-golden-input.json'

describe('the demo NACHA builder', () => {
  it('matches the same golden file as the Java server', () => {
    expect(buildNachaFile(input as NachaFile)).toBe(golden)
  })

  it('pads to a multiple of 10 records of 94 characters', () => {
    const lines = buildNachaFile(input as NachaFile).trimEnd().split('\n')
    expect(lines).toHaveLength(10)
    expect(lines.every((l) => l.length === 94)).toBe(true)
    expect(lines[9]).toBe('9'.repeat(94))
  })
})
