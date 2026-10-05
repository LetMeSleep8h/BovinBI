import http from './http'
import type { UserInfo } from './types'

export const login = (username: string, password: string) =>
  http.post<never, any>('/auth/login', { username, password }) as Promise<{ token: string; id: number; username: string; nickname: string; role: string }>

export const me = () => http.get<never, UserInfo>('/auth/me')

export const listSessions = () => http.get<never, any[]>('/chat/sessions')

export const createSession = (datasetId: number) =>
  http.post<never, { sessionId: number }>('/chat/sessions', { datasetId })

export const deleteSession = (id: number) => http.delete(`/chat/sessions/${id}`)

export const listMessages = (sessionId: number) => http.get<never, any[]>(`/chat/sessions/${sessionId}/messages`)

export const ask = (sessionId: number, question: string, engine?: string) =>
  http.post<never, any>('/chat/ask', { sessionId, question, engine })

/**
 * 流式问答(SSE):实时回调每一步 AI 工作内容(意图识别/Schema召回/工具调用…),
 * 结束时 resolve 最终助手消息;失败 reject(调用方可降级回 ask)。
 */
export async function askStream(
  sessionId: number,
  question: string,
  onStep: (step: { seq: number; name: string; detail: string; ok: boolean }) => void,
  onApproval?: (a: { queryId: number; sql: string; explanation: string }) => Promise<boolean>,
  engine?: string
): Promise<any> {
  const token = localStorage.getItem('bovin_token')
  const resp = await fetch('/api/chat/ask/stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {})
    },
    body: JSON.stringify({ sessionId, question, engine })
  })
  if (!resp.ok || !resp.body) throw new Error(`stream HTTP ${resp.status}`)
  const reader = resp.body.getReader()
  const decoder = new TextDecoder()
  let buf = ''
  let final: any = null
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buf += decoder.decode(value, { stream: true })
    let idx: number
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const block = buf.slice(0, idx)
      buf = buf.slice(idx + 2)
      let event = 'message'
      let data = ''
      for (const line of block.split('\n')) {
        if (line.startsWith('event:')) event = line.slice(6).trim()
        else if (line.startsWith('data:')) data += line.slice(5).trim()
      }
      if (!data) continue
      const payload = JSON.parse(data)
      if (event === 'step') onStep(payload)
      else if (event === 'approval' && onApproval) {
        const ok = await onApproval(payload)
        await approveQuery(payload.queryId, ok)
      }
      else if (event === 'done') final = payload
      else if (event === 'error') throw new Error(payload.message || '查询失败')
    }
  }
  if (!final) throw new Error('stream ended without final message')
  return final
}

/** 两段式 text2sql:先 parse(生成/守护 SQL,不查库),再 execute(取回并执行) */
export const parseQuery = (sessionId: number, question: string) =>
  http.post<never, any>('/chat/parse', { sessionId, question })

export const executeQuery = (queryId: number, parseId: number, approved = true) =>
  http.post<never, any>('/chat/execute', { queryId, parseId, approved })

/** 逐步确认模式:回填执行/取消决策,放行或终止挂起中的流式问答 */
export const approveQuery = (queryId: number, approve: boolean) =>
  http.post(`/chat/approve/${queryId}`, { approve })

export const listDatasets = () => http.get<never, any[]>('/datasets')

export const listFields = (datasetId: number) => http.get<never, any[]>(`/datasets/${datasetId}/fields`)

export const updateField = (fieldId: number, data: { alias?: string; synonyms?: string; description?: string; aggType?: string }) =>
  http.put(`/datasets/fields/${fieldId}`, data)

export const listQueryLogs = (page: number, size: number) =>
  http.get<never, any>('/history/queries', { params: { page, size } })

export const historyStats = () => http.get<never, any>('/history/stats')

/** 每用户每日 token 用量(all=true 仅 ADMIN 生效:全员视角) */
export const tokenUsage = (days: number, all = false) =>
  http.get<never, any[]>('/usage/tokens', { params: { days, all } })

export const tokenUsageToday = (all = false) =>
  http.get<never, any>('/usage/tokens/today', { params: { all } })
