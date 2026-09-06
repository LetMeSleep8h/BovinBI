// 与后端 dto 对齐的类型定义
export interface ColInfo {
  name: string
  type: string
}

export interface ChartSeries {
  name: string
  values: number[]
}

export interface ChartSpec {
  type: 'line' | 'bar' | 'barH' | 'pie' | 'none'
  xAxisName: string | null
  xValues: string[] | null
  series: ChartSeries[] | null
  reason: string | null
}

export interface AnswerPayload {
  sql: string | null
  explanation: string | null
  columns: ColInfo[]
  rows: Record<string, any>[]
  rowCount: number
  chart: ChartSpec | null
  tookMs: number
  cacheHit: boolean
  engine: string
  fallback: boolean
  fallbackHint: string | null
}

export interface ChatMessage {
  id: number
  sessionId: number
  role: 'USER' | 'ASSISTANT'
  content: string | null
  payload: AnswerPayload | null
  createdAt: string
}

export interface ChatSession {
  id: number
  datasetId: number
  datasetName: string
  title: string | null
  createdAt: string
  updatedAt: string
}

export interface Dataset {
  id: number
  name: string
  description: string
  dwhTables: string
  status: string
}

export interface DatasetField {
  id: number
  datasetId: number
  tableName: string
  columnName: string
  alias: string
  fieldType: 'DIMENSION' | 'METRIC'
  dataType: string
  aggType: string
  synonyms: string
  description: string
  isHidden: number
}

export interface QueryLog {
  id: number
  userId: number
  datasetId: number
  question: string
  finalSql: string | null
  engine: string
  status: string
  rowCount: number
  costMs: number
  cacheHit: number
  errorMsg: string | null
  createdAt: string
}

export interface UserInfo {
  id: number
  username: string
  nickname: string
  role: string
}
