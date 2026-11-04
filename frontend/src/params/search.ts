import type { AppParam } from './ParameterPage'

/** UC-SYS-PARAM-01 goal "view, search, insert, or update": case-insensitive match on name, description or value. */
export function searchParams(params: AppParam[], query: string): AppParam[] {
  const q = query.trim().toLowerCase()
  if (!q) return params
  return params.filter((p) =>
    [p.paramName, p.paramDesc ?? '', p.paramRawValue].some((field) => field.toLowerCase().includes(q)),
  )
}
