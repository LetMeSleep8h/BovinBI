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

export const ask = (sessionId: number, question: string) =>
  http.post<never, any>('/chat/ask', { sessionId, question })

export const listDatasets = () => http.get<never, any[]>('/datasets')

export const listFields = (datasetId: number) => http.get<never, any[]>(`/datasets/${datasetId}/fields`)

export const updateField = (fieldId: number, data: { alias?: string; synonyms?: string; description?: string; aggType?: string }) =>
  http.put(`/datasets/fields/${fieldId}`, data)

export const listQueryLogs = (page: number, size: number) =>
  http.get<never, any>('/history/queries', { params: { page, size } })

export const historyStats = () => http.get<never, any>('/history/stats')
