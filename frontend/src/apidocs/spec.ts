import { parse } from 'yaml'

// Turns one of Ba's OpenAPI 3.0 YAML files into the rows the API pages show.
// The 'failsafe' schema keeps every value exactly as written in the YAML (25000.00 stays "25000.00", not 25000).

type Schema = {
  type?: string
  format?: string
  description?: string
  example?: string
  default?: string
  nullable?: string
  enum?: string[]
  required?: string[]
  properties?: Record<string, Schema>
  items?: Schema
}

/** One row of a fields table. `depth` indents the fields of nested objects and arrays. */
export type Field = {
  name: string
  depth: number
  type: string
  required: boolean
  description?: string
  example?: string
  default?: string
  enum?: string[]
}

export type Body = { type: string; fields: Field[] }
export type Response = { status: string; description: string; body?: Body }

export type Operation = {
  method: string
  path: string
  summary: string
  description: string
  parameters: (Field & { in: string })[]
  requestBody?: Body & { required: boolean }
  responses: Response[]
}

export type Spec = { title: string; version: string; description: string; operations: Operation[] }

const METHODS = ['get', 'post', 'put', 'patch', 'delete']

function typeLabel(s: Schema): string {
  if (s.type === 'array') return `array of ${s.items ? typeLabel(s.items) : 'any'}`
  const type = s.format ? `${s.type} (${s.format})` : (s.type ?? 'any')
  return s.nullable === 'true' ? `${type}, nullable` : type
}

/** The fields of an object (or of an array's items), with nested objects listed under their parent. */
function fields(s: Schema | undefined, depth: number): Field[] {
  if (s?.type === 'array') return fields(s.items, depth)
  return Object.entries(s?.properties ?? {}).flatMap(([name, p]) => [
    {
      name,
      depth,
      type: typeLabel(p),
      required: s?.required?.includes(name) ?? false,
      description: p.description,
      example: p.example,
      default: p.default,
      enum: p.enum,
    },
    ...fields(p, depth + 1),
  ])
}

function body(content: Record<string, { schema?: Schema }> | undefined): Body | undefined {
  const schema = content?.['application/json']?.schema
  return schema && { type: typeLabel(schema), fields: fields(schema, 0) }
}

export function parseSpec(yamlText: string): Spec {
  const doc = parse(yamlText, { schema: 'failsafe' })
  const operations: Operation[] = []
  for (const [path, item] of Object.entries<Record<string, any>>(doc.paths)) {
    for (const [method, op] of Object.entries<any>(item)) { // in the file's order, as Ba wrote them
      if (!METHODS.includes(method)) continue
      operations.push({
        method: method.toUpperCase(),
        path,
        summary: op.summary,
        description: op.description,
        parameters: (op.parameters ?? []).map((p: any) => ({
          name: p.name,
          in: p.in,
          depth: 0,
          type: typeLabel(p.schema ?? {}),
          required: p.required === 'true',
          description: p.description,
          example: p.schema?.example,
          default: p.schema?.default,
          enum: p.schema?.enum,
        })),
        requestBody: op.requestBody && { required: op.requestBody.required === 'true', ...body(op.requestBody.content)! },
        responses: Object.entries<any>(op.responses ?? {}).map(([status, r]) => ({
          status,
          description: r.description,
          body: body(r.content),
        })),
      })
    }
  }
  return { title: doc.info.title, version: doc.info.version, description: doc.info.description, operations }
}
