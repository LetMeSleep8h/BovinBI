<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { CopyDocument } from '@element-plus/icons-vue'
import EChart from './EChart.vue'
import type { AnswerPayload } from '@/api/types'

const props = defineProps<{ payload: AnswerPayload }>()

const engineLabel = computed(() => {
  const e = props.payload.engine || ''
  if (e === 'CACHE') return { text: '缓存命中', type: 'success' as const }
  if (e.startsWith('LLM')) return { text: `LLM生成${e.includes('降级') ? '(规则兜底)' : ''}`, type: 'primary' as const }
  return { text: '规则引擎', type: 'info' as const }
})

const bigNumber = computed(() => {
  const p = props.payload
  if (!p.rows || p.rows.length !== 1 || p.chart?.type !== 'none') return null
  const row = p.rows[0]
  for (const k of Object.keys(row)) {
    const v = row[k]
    if (typeof v === 'number') return { label: k, value: v }
  }
  return null
})

const tableColumns = computed(() => props.payload.columns?.map((c) => c.name) || [])

function fmtCell(v: any): string {
  if (v == null) return '-'
  if (typeof v === 'number') return v.toLocaleString('zh-CN', { maximumFractionDigits: 2 })
  return String(v)
}

async function copySql() {
  if (!props.payload.sql) return
  try {
    await navigator.clipboard.writeText(props.payload.sql)
    ElMessage.success('SQL 已复制')
  } catch {
    ElMessage.error('复制失败')
  }
}
</script>

<template>
  <div class="answer-card">
    <div class="answer-head">
      <div v-if="!payload.fallback" class="answer-explain">{{ payload.explanation || '查询完成' }}</div>
      <div class="answer-meta">
        <el-tag size="small" :type="engineLabel.type">{{ engineLabel.text }}</el-tag>
        <el-tag v-if="!payload.fallback" size="small" type="info">{{ payload.rowCount }} 行</el-tag>
        <el-tag v-if="!payload.fallback" size="small" type="info">{{ payload.tookMs }} ms</el-tag>
        <el-tag v-if="!payload.fallback && payload.chart && payload.chart.type !== 'none'" size="small">
          {{ payload.chart.type === 'line' ? '折线图' : payload.chart.type === 'pie' ? '饼图' : '柱状图' }} · {{ payload.chart.reason }}
        </el-tag>
      </div>
    </div>

    <el-alert v-if="payload.fallback" type="warning" :title="payload.fallbackHint || '暂时无法回答该问题'" :closable="false" style="margin: 8px 16px 14px" />

    <template v-else>
      <div v-if="bigNumber" class="big-number">
        <div class="num">{{ fmtCell(bigNumber.value) }}</div>
        <div class="label">{{ bigNumber.label }}</div>
      </div>

      <div v-if="payload.chart && payload.chart.type !== 'none'" class="answer-body">
        <EChart :spec="payload.chart" />
      </div>

      <div class="answer-body">
        <el-collapse>
          <el-collapse-item name="data" v-if="payload.rows && payload.rows.length">
            <template #title>查看数据({{ payload.rowCount }} 行)</template>
            <el-table :data="payload.rows" size="small" max-height="260" border stripe>
              <el-table-column v-for="col in tableColumns" :key="col" :prop="col" :label="col" min-width="120">
                <template #default="{ row }">{{ fmtCell(row[col]) }}</template>
              </el-table-column>
            </el-table>
          </el-collapse-item>
          <el-collapse-item name="sql" v-if="payload.sql">
            <template #title>
              查看生成 SQL
              <el-icon style="margin-left: 8px" @click.stop="copySql"><CopyDocument /></el-icon>
            </template>
            <div class="sql-block">{{ payload.sql }}</div>
          </el-collapse-item>
        </el-collapse>
      </div>
    </template>
  </div>
</template>
