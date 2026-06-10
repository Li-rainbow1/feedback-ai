export const formatDateTime = (value: unknown): string => {
  if (value == null) return '-'
  const text = String(value).trim()
  if (!text) return '-'
  const normalized = text.replace('T', ' ')
  return normalized.length >= 19 ? normalized.substring(0, 19) : normalized
}
