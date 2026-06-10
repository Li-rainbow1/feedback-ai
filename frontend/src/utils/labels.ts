export const categoryOptions = [
  { label: 'Bug', value: 'BUG' },
  { label: '建议', value: 'SUGGESTION' },
  { label: '好评', value: 'PRAISE' }
]

export const severityOptions = [
  { label: '严重', value: 'CRITICAL' },
  { label: '高', value: 'HIGH' },
  { label: '中', value: 'MEDIUM' },
  { label: '低', value: 'LOW' }
]

export const priorityOptions = [
  { label: 'P1', value: 'P1' },
  { label: 'P2', value: 'P2' },
  { label: 'P3', value: 'P3' },
  { label: 'P4', value: 'P4' }
]

export const bugStatusOptions = [
  { label: '待处理', value: 'open' },
  { label: '修复中', value: 'fixing' },
  { label: '已解决', value: 'resolved' },
  { label: '已关闭', value: 'closed' }
]

export const suggestionStatusOptions = [
  { label: '待评估', value: 'evaluating' },
  { label: '已采纳', value: 'accepted' },
  { label: '规划中', value: 'planned' },
  { label: '已实现', value: 'implemented' },
  { label: '暂不采纳', value: 'not_accepted' }
]

const valueOf = (value: any): string => {
  if (value == null) return ''
  if (typeof value === 'object') {
    return String(value.code ?? value.name ?? value.value ?? value.label ?? '').trim()
  }
  return String(value).trim()
}

const findLabel = (options: Array<{ label: string; value: string }>, value: any) => {
  const raw = valueOf(value)
  if (!raw) return '-'
  const upper = raw.toUpperCase()
  const lower = raw.toLowerCase()
  return options.find(item =>
    item.value.toUpperCase() === upper ||
    item.value.toLowerCase() === lower ||
    item.label === raw
  )?.label || raw
}

export const categoryLabel = (value: any) => {
  const raw = valueOf(value)
  if (['缺陷', '故障', '问题'].includes(raw)) return 'Bug'
  if (['需求', '建议', '优化', '改进'].includes(raw)) return '建议'
  if (['好评', '表扬'].includes(raw)) return '好评'
  return findLabel(categoryOptions, value)
}

export const severityLabel = (value: any) => findLabel(severityOptions, value)

export const priorityLabel = (value: any) => findLabel(priorityOptions, value)

export const issueStatusLabel = (value: any) => findLabel([
  ...bugStatusOptions,
  ...suggestionStatusOptions,
  { label: '已确认', value: 'acknowledged' },
  { label: '已归并', value: 'merged' },
  { label: '已关闭', value: 'closed' }
], value)

export const severityTagType = (value: any) => {
  const severity = valueOf(value).toUpperCase()
  if (severity === 'CRITICAL' || severity === 'HIGH') return 'danger'
  if (severity === 'MEDIUM') return 'warning'
  return 'info'
}

export const priorityTagType = (value: any) => {
  const priority = valueOf(value).toUpperCase()
  if (priority === 'P1') return 'danger'
  if (priority === 'P2') return 'warning'
  if (priority === 'P3') return 'primary'
  return 'info'
}

export const issueStatusTagType = (value: any) => {
  const status = valueOf(value).toLowerCase()
  if (status === 'open') return 'danger'
  if (status === 'resolved' || status === 'closed' || status === 'implemented') return 'success'
  if (status === 'merged' || status === 'not_accepted') return 'info'
  return 'warning'
}
