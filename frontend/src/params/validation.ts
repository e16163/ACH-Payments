// Same rules as the backend's ParamValidator.java, so the admin sees errors before Save (UC-SYS-PARAM-01 EX-01).

export const PARAM_TYPES = ['STRING', 'INTEGER', 'DECIMAL', 'BOOLEAN', 'JSON'] as const
export type ParamType = (typeof PARAM_TYPES)[number]

export function validateValue(type: string, value: string): string | null {
  if (value === '') return 'Value is required.'
  if (value.length > 500) return 'Value must be at most 500 characters.'
  switch (type) {
    case 'INTEGER':
      return /^-?\d+$/.test(value) ? null : 'Value must be a valid integer.'
    case 'DECIMAL':
      return /^-?\d+(\.\d+)?$/.test(value) ? null : 'Value must be a valid decimal number.'
    case 'BOOLEAN':
      return value === 'TRUE' || value === 'FALSE' ? null : 'Value must be TRUE or FALSE.'
    case 'JSON':
      try {
        JSON.parse(value)
        return null
      } catch {
        return 'Value must be valid JSON.'
      }
    default:
      return null
  }
}

export function validateName(name: string): string | null {
  return name.trim() === '' || name.length > 50 ? 'Parameter name is required (max 50 characters).' : null
}

export function validateDesc(desc: string): string | null {
  return desc.length > 255 ? 'Description must be at most 255 characters.' : null
}
