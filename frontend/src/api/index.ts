import axios from 'axios'
import { ElMessage } from 'element-plus'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 30000
})

const statusMessage = (status?: number) => {
  switch (status) {
    case 400:
      return '请求参数不正确'
    case 401:
      return '登录已失效，请重新登录'
    case 403:
      return '没有权限执行该操作'
    case 404:
      return '请求的资源不存在'
    case 409:
      return '数据状态已变化，请刷新后重试'
    case 422:
      return '请求内容无法处理'
    case 500:
      return '服务端内部错误'
    case 502:
      return '后端服务暂时不可用，请确认后端已启动'
    case 503:
      return '服务暂时不可用，请稍后重试'
    case 504:
      return '请求超时，请稍后重试'
    default:
      return status ? `请求失败，状态码 ${status}` : '网络连接失败，请检查服务是否可用'
  }
}

const responseMessage = (data: any, status?: number) => {
  if (data && typeof data === 'object' && data.message) {
    return data.message
  }
  if (typeof data === 'string') {
    const text = data.trim()
    if (text && !text.startsWith('<')) {
      return text
    }
  }
  return statusMessage(status)
}

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers = config.headers || {}
    config.headers['X-Token'] = token
  }
  return config
})

api.interceptors.response.use(
  (res) => {
    const data = res.data
    if (data.code === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      if (window.location.hash !== '#/login') {
        window.location.hash = '#/login'
      }
      ElMessage.error(data.message || '登录已失效')
      return Promise.reject(new Error(data.message || '登录已失效'))
    }
    if (data.code !== 200) {
      ElMessage.error(data.message || '请求失败')
      return Promise.reject(new Error(data.message || '请求失败'))
    }
    return data
  },
  (err) => {
    const status = err?.response?.status
    const message = responseMessage(err?.response?.data, status)
    if (status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('user')
      if (window.location.hash !== '#/login') {
        window.location.hash = '#/login'
      }
      ElMessage.error(message)
    } else {
      ElMessage.error(message || err.message)
    }
    return Promise.reject(err)
  }
)

export const authApi = {
  login: (data: { username: string; password: string }) => api.post('/auth/login', data),
  me: () => api.get('/auth/me'),
  logout: () => api.post('/auth/logout')
}

export const productApi = {
  list: () => api.get('/products'),
  get: (id: number) => api.get(`/products/${id}`),
  create: (data: any) => api.post('/products', data),
  update: (id: number, data: any) => api.put(`/products/${id}`, data),
  delete: (id: number) => api.delete(`/products/${id}`)
}

export const feedbackApi = {
  submit: (data: any) => api.post('/feedbacks/webhook', data),
  submitManual: (data: any) => api.post('/feedbacks/manual', data),
  batchSubmit: (data: any[]) => api.post('/feedbacks/webhook/batch', data),
  getStatus: (rawId: string) => api.get(`/feedbacks/status/${rawId}`),
  getDetail: (id: string) => api.get(`/feedbacks/${id}`),
  search: (params: any) => api.post('/feedbacks/search', params),
  getByIssueId: (issueId: string, page = 1, size = 20) =>
    api.get(`/feedbacks/by-issue/${issueId}`, { params: { page, size } })
}

export const issueApi = {
  list: (params: any) => api.get('/issues', { params }),
  detail: (id: string) => api.get(`/issues/${id}`),
  updateStatus: (id: string, status: string) =>
    api.patch(`/issues/${id}/status`, { status }),
  confirm: (id: string) =>
    api.patch(`/issues/${id}/confirm`),
  updateTriage: (id: string, data: { severity: string; priority: string; reason?: string }) =>
    api.patch(`/issues/${id}/triage`, data),
  linkIssue: (id: string, issueKey: string) =>
    api.post(`/issues/${id}/link-issue`, { issueKey }),
  mergeIssue: (id: string, targetIssueId: string) =>
    api.post(`/issues/${id}/merge`, { targetIssueId }),
  reassignClaim: (claimId: string, targetIssueId: string) =>
    api.post(`/issues/claims/${claimId}/reassign`, { targetIssueId })
}

export const praiseApi = {
  list: (params: any) => api.get('/feedbacks/praises', { params }),
  groups: (params: any) => api.get('/feedbacks/praises/groups', { params }),
  groupClaims: (params: any) => api.get('/feedbacks/praises/group-claims', { params })
}

export const dashboardApi = {
  overview: (productId: number, start?: string, end?: string) =>
    api.get('/dashboard/overview', { params: { productId, start, end } })
}

export const reportApi = {
  list: (productId: number) => api.get('/reports/weekly', { params: { productId } }),
  generate: (productId: number, weekStart?: string) =>
    api.post('/reports/weekly/generate', null, { params: { productId, weekStart } }),
  detail: (id: number) => api.get(`/reports/weekly/${id}`),
  send: (id: number) => api.post(`/reports/weekly/${id}/send`)
}

export const channelApi = {
  list: (productId?: number) => api.get('/channels', { params: { productId } }),
  get: (id: number) => api.get(`/channels/${id}`),
  create: (data: any) => api.post('/channels', data),
  update: (id: number, data: any) => api.put(`/channels/${id}`, data),
  delete: (id: number) => api.delete(`/channels/${id}`)
}

export const publicReviewApi = {
  list: (productId: number) => api.get('/public-review-sources', { params: { productId } }),
  get: (id: number) => api.get(`/public-review-sources/${id}`),
  create: (data: any) => api.post('/public-review-sources', data),
  update: (id: number, data: any) => api.put(`/public-review-sources/${id}`, data),
  delete: (id: number) => api.delete(`/public-review-sources/${id}`),
  initialize: (id: number) => api.post(`/public-review-sources/${id}/initialize`),
  collect: (id: number) => api.post(`/public-review-sources/${id}/collect`),
  runs: (id: number) => api.get(`/public-review-sources/${id}/runs`)
}

export default api
