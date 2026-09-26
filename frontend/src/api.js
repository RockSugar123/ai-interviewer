// 统一请求封装：后端返回 {code, message, data}，code=0 为成功；JWT 走 Authorization: Bearer
export async function request(path, { method = 'GET', body } = {}) {
  const headers = { 'Content-Type': 'application/json' }
  const token = localStorage.getItem('token')
  if (token) headers.Authorization = `Bearer ${token}`

  let res
  try {
    res = await fetch(`/api${path}`, {
      method,
      headers,
      body: body ? JSON.stringify(body) : undefined
    })
  } catch {
    throw new Error('网络异常，请确认后端服务已启动')
  }

  if (res.status === 401) {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    location.hash = '#/login'
    throw new Error('登录已过期，请重新登录')
  }

  const json = await res.json().catch(() => ({ code: -1, message: '响应解析失败' }))
  if (json.code !== 0) throw new Error(json.message || `请求失败（HTTP ${res.status}）`)
  return json.data
}

export const authApi = {
  register: (body) => request('/auth/register', { method: 'POST', body }),
  login: (body) => request('/auth/login', { method: 'POST', body })
}

export const sessionApi = {
  create: (body) => request('/sessions', { method: 'POST', body }),
  page: (page = 1, size = 100) => request(`/sessions?page=${page}&size=${size}`),
  detail: (id) => request(`/sessions/${id}`),
  finish: (id) => request(`/sessions/${id}/finish`, { method: 'POST' }),
  remove: (id) => request(`/sessions/${id}`, { method: 'DELETE' })
}

export const resumeApi = {
  list: () => request('/resumes'),
  remove: (id) => request(`/resumes/${id}`, { method: 'DELETE' }),
  // multipart 单独处理（request 封装只走 JSON）
  upload: async (file) => {
    const form = new FormData()
    form.append('file', file)
    let res
    try {
      res = await fetch('/api/resumes', {
        method: 'POST',
        headers: { Authorization: `Bearer ${localStorage.getItem('token') || ''}` },
        body: form
      })
    } catch {
      throw new Error('网络异常，请确认后端服务已启动')
    }
    if (res.status === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('username')
      location.hash = '#/login'
      throw new Error('登录已过期，请重新登录')
    }
    const json = await res.json().catch(() => ({ code: -1, message: '上传失败' }))
    if (json.code !== 0) throw new Error(json.message || `上传失败（HTTP ${res.status}）`)
    return json.data
  }
}

export const messageApi = {
  // W3 起返回发送成功的用户消息（含真实 seq），面试官回复经 streamUrl 的 SSE 推送
  send: (id, content) => request(`/sessions/${id}/messages`, { method: 'POST', body: { content } }),
  list: (id, limit = 200) => request(`/sessions/${id}/messages?limit=${limit}`),
  // EventSource 无法携带 Authorization 头，仅此端点后端支持 token 查询参数
  streamUrl: (id, afterSeq) =>
    `/api/sessions/${id}/messages/stream?afterSeq=${afterSeq}&token=${encodeURIComponent(localStorage.getItem('token') || '')}`
}

export const reportApi = {
  // 阶段 5：报告经 MQ 异步生成，PENDING/RUNNING 状态需前端轮询
  get: (id) => request(`/sessions/${id}/report`),
  retry: (id) => request(`/sessions/${id}/report/retry`, { method: 'POST' })
}
