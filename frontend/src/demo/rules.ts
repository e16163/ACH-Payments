// The demo's copy of BusinessRules.java (Ba's ACH Business Rules, 2026-11-12): the same check, messages and evaluation.

type Condition = { field: string; op: string; value: unknown }
export type Rule = { when: { all?: Condition[]; any?: Condition[] }; then: { set: string; value: unknown }[] }

const OPS = ['eq', 'gt', 'gte', 'in', 'lt', 'lte', 'ne']
const FIELDS: Record<string, string[]> = { ACH_RCV: ['amount', 'companyID', 'companyName', 'creditIndicator', 'stdEntryCode', 'tranCode'] }
const list = (xs: string[]) => `[${xs.join(', ')}]`

/** Throws an Error naming what's wrong, as BusinessRules.parse does. */
export function parseRule(json: string, systemName: string): Rule {
  let rule: Record<string, unknown>
  try {
    rule = JSON.parse(json)
  } catch {
    throw new Error("The JSON rule isn't valid JSON.")
  }
  const when = (rule?.when ?? {}) as Record<string, unknown>
  const conditions = 'all' in when ? when.all : when.any
  if (!Array.isArray(conditions) || conditions.length === 0 || Object.keys(when).length !== 1) {
    throw new Error('"when" needs one of "all" or "any" with a list of conditions.')
  }
  const fields = FIELDS[systemName]
  for (const c of conditions as Record<string, unknown>[]) {
    if (typeof c?.field !== 'string' || !c.field || !('value' in c)) throw new Error('Each condition needs "field", "op" and "value".')
    if (fields && !fields.includes(c.field)) throw new Error(`Unknown field "${c.field}"; use one of ${list(fields)}.`)
    if (!OPS.includes(String(c.op))) throw new Error(`Unknown op "${c.op ?? ''}"; use one of ${list(OPS)}.`)
    if ((c.op === 'in') !== Array.isArray(c.value)) throw new Error('"in" takes a list of values, and only "in" does.')
  }
  const then = rule.then
  if (!Array.isArray(then) || then.length === 0) throw new Error('"then" needs a list of {"set", "value"}.')
  for (const t of then) if (typeof t?.set !== 'string' || !t.set || !('value' in t)) throw new Error('Each "then" needs "set" and "value".')
  return rule as unknown as Rule
}

const same = (fact: unknown, value: unknown) => fact !== undefined && fact !== null && fact === value

function holds(c: Condition, fact: unknown): boolean {
  if (c.op === 'eq') return same(fact, c.value)
  if (c.op === 'ne') return !same(fact, c.value)
  if (c.op === 'in') return (c.value as unknown[]).some((v) => same(fact, v))
  if (typeof fact !== 'number' || typeof c.value !== 'number') return false
  return c.op === 'gt' ? fact > c.value : c.op === 'gte' ? fact >= c.value : c.op === 'lt' ? fact < c.value : fact <= c.value
}

/** What the rules set for these facts; a later rule (by name) wins. */
export function results(rules: Rule[], facts: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const r of rules) {
    const all = 'all' in r.when
    const conditions = (all ? r.when.all : r.when.any)!
    if (all ? conditions.every((c) => holds(c, facts[c.field])) : conditions.some((c) => holds(c, facts[c.field]))) {
      for (const t of r.then) out[t.set] = t.value
    }
  }
  return out
}
