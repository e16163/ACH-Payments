import { describe, expect, it } from 'vitest'
import achOriginationYaml from './ach-origination-system.yaml?raw'
import achReceivingYaml from './ach-receiving-system.yaml?raw'
import coreBankingYaml from './banking-core-services.yaml?raw'
import { parseSpec } from './spec'

const core = parseSpec(coreBankingYaml)
const orig = parseSpec(achOriginationYaml)
const recv = parseSpec(achReceivingYaml)
const op = (spec: typeof core, method: string, path: string) => spec.operations.find((o) => o.method === method && o.path === path)!

describe('Ba\'s API specs', () => {
  it('lists the 5 core banking operations from the request, in order', () => {
    expect(core.title).toBe('Banking Core Utility & Compliance API')
    expect(core.operations.map((o) => o.summary)).toEqual([
      'Validate Routing Transit Number (RTN)',
      'Get Bank Directory Profile',
      'Check US Federal Holiday Status',
      'Check US Banking Business Day Status',
      'Screen Individual or Business Entity against OFAC Lists',
    ])
  })

  it('lists all 11 origination and 4 receiving operations, including PUT and DELETE on one path', () => {
    expect(orig.operations.map((o) => `${o.method} ${o.path}`).slice(0, 2)).toEqual([ // Ba's order: Add before List
      'POST /v1/holders/{holderId}/external-accounts', 'GET /v1/holders/{holderId}/external-accounts',
    ])
    expect(orig.operations).toHaveLength(11)
    expect(recv.operations).toHaveLength(4)
    expect(orig.operations.filter((o) => o.path === '/v1/transfers/{transferId}').map((o) => o.method)).toEqual(['PUT', 'DELETE'])
  })

  it('reads a path parameter and a response, keeping values exactly as written', () => {
    const rtn = op(core, 'GET', '/v1/routing-numbers/{routingNumber}/validate')
    expect(rtn.parameters).toEqual([expect.objectContaining({
      name: 'routingNumber', in: 'path', type: 'string', required: true, example: '021000021',
      description: 'The 9-digit ABA routing transit number to validate.',
    })])
    expect(rtn.requestBody).toBeUndefined()
    expect(rtn.responses[0].body?.fields.map((f) => [f.name, f.type, f.example])).toEqual([
      ['routingNumber', 'string', '021000021'],
      ['isValid', 'boolean', 'true'],
      ['validationMessage', 'string', 'Valid ABA routing transit number.'],
    ])
    const eligibility = op(orig, 'POST', '/v1/holders/{holderId}/eligibility/outbound')
    expect(eligibility.responses[0].body?.fields.find((f) => f.name === 'maxTransferLimit')).toMatchObject({ type: 'number (double)', example: '25000.00' })
  })

  it('reads a request body with required fields, enums, defaults and nested arrays', () => {
    const ofac = op(core, 'POST', '/v1/compliance/ofac/screen')
    expect(ofac.parameters).toEqual([])
    expect(ofac.requestBody).toMatchObject({ required: true, type: 'object' })
    expect(ofac.requestBody?.fields.map((f) => [f.name, f.required])).toEqual([['entityName', true], ['entityType', true], ['matchThreshold', false]])
    expect(ofac.requestBody?.fields[1].enum).toEqual(['INDIVIDUAL', 'BUSINESS'])
    expect(ofac.requestBody?.fields[2].default).toBe('85')
    expect(ofac.responses[0].body?.fields.map((f) => [f.name, f.depth, f.type])).toEqual([
      ['screenId', 0, 'string'], ['isMatch', 0, 'boolean'], ['matchScore', 0, 'integer'], ['hits', 0, 'array of object'],
      ['sdnEntryId', 1, 'string'], ['matchedName', 1, 'string'], ['listType', 1, 'string'], ['score', 1, 'integer'],
    ])
  })

  it('reads nested objects, nullable fields, list responses and responses without a body', () => {
    const profile = op(core, 'GET', '/v1/routing-numbers/{routingNumber}')
    expect(profile.responses.map((r) => [r.status, r.description])).toEqual([
      ['200', 'Bank directory details retrieved.'], ['404', 'Routing number not found in directory.'],
    ])
    expect(profile.responses[1].body).toBeUndefined()
    expect(profile.responses[0].body?.fields.filter((f) => f.depth === 1).map((f) => f.name)).toEqual(['street', 'city', 'state', 'postalCode', 'country'])

    const outbound = op(orig, 'POST', '/v1/holders/{holderId}/transfers/outbound')
    expect(outbound.requestBody?.fields.filter((f) => f.depth === 1)).toEqual([
      expect.objectContaining({ name: 'frequency', required: true, enum: ['DAILY', 'WEEKLY', 'BIWEEKLY', 'MONTHLY'] }),
      expect.objectContaining({ name: 'totalOccurrences', required: true, type: 'integer' }),
    ])
    expect(outbound.requestBody?.fields.find((f) => f.name === 'recurrenceDetails')?.type).toBe('object, nullable')

    const scheduled = op(orig, 'GET', '/v1/holders/{holderId}/transfers/scheduled')
    expect(scheduled.parameters[0]).toMatchObject({ name: 'holderId', required: true }) // P1 fix in docs/API-VIEWER-PLAN.md
    expect(scheduled.responses[0].body?.type).toBe('array of object')
    expect(scheduled.responses[0].body?.fields.map((f) => f.name)).toEqual(['transferId', 'amount', 'nextExecutionDate', 'frequency'])
  })

  it('reads optional query parameters with defaults', () => {
    const search = op(recv, 'GET', '/v1/ach/receiving/transactions/search')
    expect(search.parameters.map((p) => [p.name, p.in, p.required])).toEqual([
      ['processedDate', 'query', false], ['processingStatus', 'query', false], ['minAmount', 'query', false],
      ['maxAmount', 'query', false], ['receivingAccountNumber', 'query', false], ['page', 'query', false], ['limit', 'query', false],
    ])
    expect(search.parameters.find((p) => p.name === 'limit')?.default).toBe('10')
  })
})
