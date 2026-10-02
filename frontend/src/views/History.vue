<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { historyStats, listQueryLogs, tokenUsage, tokenUsageToday } from '@/api'
import type { QueryLog } from '@/api/types'

const records = ref<QueryLog[]>([])
const stats = ref<any>({})
const usageRows = ref<any[]>([])
const usageToday = ref<any>({})
const usageAll = ref(false)
const total = ref(0)
const page = ref(1)
const size = 15
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    const resp: any = await listQueryLogs(page.value, size)
    records.value = resp.records || []
    total.value = Number(resp.total || 0)
  } finally {
    loading.value = false
  }
  stats.value = await historyStats()
  await loadUsage()
}

async function loadUsage() {
  usageRows.value = await tokenUsage(14, usageAll.value)
  usageToday.value = await tokenUsageToday(usageAll.value)
}

onMounted(load)
</script>

<template>
  <div class="page">
    <h3>查询历史与运行状态</h3>

    <el-card shadow="never" style="margin-bottom: 16px">
      <template #header>
        <div style="display:flex;justify-content:space-between;align-items:center">
          <span>我的 Token 用量(每用户每日独立存储)</span>
          <el-switch v-model="usageAll" active-text="全员视角" @change="loadUsage" />
        </div>
      </template>
      <el-row :gutter="16" style="margin-bottom: 12px">
        <el-col :span="12"><el-statistic title="今日 tokens" :value="usageToday.tokens || 0" /></el-col>
        <el-col :span="12"><el-statistic title="今日调用次数" :value="usageToday.requests || 0" /></el-col>
      </el-row>
      <el-table :data="usageRows" size="small" max-height="260">
        <el-table-column prop="usageDate" label="日期" width="110" />
        <el-table-column prop="username" label="用户" width="120" />
        <el-table-column prop="promptTokens" label="上行 tokens" width="120" />
        <el-table-column prop="completionTokens" label="下行 tokens" width="120" />
        <el-table-column prop="totalTokens" label="合计" width="110" />
        <el-table-column prop="requests" label="调用次数" />
      </el-table>
      <div v-if="usageRows.length === 0" class="muted" style="padding: 8px 0">
        暂无记录(离线规则引擎不消耗 token;接入 LLM 后每次调用自动累计)
      </div>
    </el-card>

    <el-row :gutter="16" class="stat-row">
      <el-col :span="6">
        <el-card shadow="never"><el-statistic title="累计查询" :value="stats.totalQueries || 0" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never"><el-statistic title="成功率" :value="stats.successRate || '0%'" /></el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never">
          <el-statistic title="缓存命中次数" :value="stats.cacheHits || 0" />
          <div class="muted">共 {{ stats.cacheRequests || 0 }} 次请求</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="never"><el-statistic title="成功查询" :value="stats.successQueries || 0" /></el-card>
      </el-col>
    </el-row>

    <el-table :data="records" v-loading="loading" border stripe>
      <el-table-column prop="id" label="#" width="70" />
      <el-table-column prop="createdAt" label="时间" width="170" />
      <el-table-column prop="question" label="用户问题" min-width="220" />
      <el-table-column prop="engine" label="引擎" width="130">
        <template #default="{ row }">
          <el-tag size="small" :type="row.engine === 'CACHE' ? 'success' : row.engine === 'LLM' ? 'primary' : 'info'">
            {{ row.engine }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="status" label="状态" width="100">
        <template #default="{ row }">
          <el-tag size="small" :type="row.status === 'SUCCESS' ? 'success' : 'danger'">{{ row.status }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="rowCount" label="行数" width="80" />
      <el-table-column prop="costMs" label="耗时(ms)" width="100" />
      <el-table-column label="缓存" width="80">
        <template #default="{ row }">{{ row.cacheHit ? '✓' : '' }}</template>
      </el-table-column>
      <el-table-column type="expand" width="60">
        <template #default="{ row }">
          <div style="padding: 10px 20px">
            <div class="sql-block" v-if="row.finalSql">{{ row.finalSql }}</div>
            <div v-else class="muted">无 SQL(失败:{{ row.errorMsg || '-' }})</div>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination style="margin-top: 16px; justify-content: flex-end" background layout="prev, pager, next"
                   :total="total" :page-size="size" v-model:current-page="page" @current-change="load" />
  </div>
</template>
