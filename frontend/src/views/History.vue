<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { historyStats, listQueryLogs } from '@/api'
import type { QueryLog } from '@/api/types'

const records = ref<QueryLog[]>([])
const stats = ref<any>({})
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
}

onMounted(load)
</script>

<template>
  <div class="page">
    <h3>查询历史与运行状态</h3>

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
