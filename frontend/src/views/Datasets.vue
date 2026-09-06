<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listDatasets, listFields, updateField } from '@/api'
import type { Dataset, DatasetField } from '@/api/types'

const datasets = ref<Dataset[]>([])
const fields = ref<DatasetField[]>([])
const activeDataset = ref<Dataset | null>(null)
const loading = ref(false)
const editing = ref<number | null>(null)
const editForm = ref({ alias: '', synonyms: '', description: '' })

async function loadFields(ds: Dataset) {
  activeDataset.value = ds
  loading.value = true
  try {
    fields.value = await listFields(ds.id)
  } finally {
    loading.value = false
  }
}

function startEdit(row: DatasetField) {
  editing.value = row.id
  editForm.value = { alias: row.alias, synonyms: row.synonyms, description: row.description }
}

async function saveEdit(row: DatasetField) {
  await updateField(row.id, { ...editForm.value })
  row.alias = editForm.value.alias
  row.synonyms = editForm.value.synonyms
  row.description = editForm.value.description
  editing.value = null
  ElMessage.success('口径已更新,新问题即时生效')
}

onMounted(async () => {
  datasets.value = await listDatasets()
  if (datasets.value.length) await loadFields(datasets.value[0])
})
</script>

<template>
  <div class="page">
    <h3>数据集管理</h3>

    <el-alert type="info" :closable="false" style="margin-bottom: 16px"
              title="语义层:这里的字段别名/同义词/口径描述,就是 ChatBI 理解业务术语的词典。修改同义词后,相关问题的召回命中率会立即变化。" />

    <el-row :gutter="16" style="margin-bottom: 16px">
      <el-col v-for="ds in datasets" :key="ds.id" :span="8">
        <el-card shadow="hover" @click="loadFields(ds)"
                 :style="activeDataset?.id === ds.id ? 'border-color:#409eff;cursor:pointer' : 'cursor:pointer'">
          <div style="font-weight: 600">{{ ds.name }}</div>
          <div class="muted" style="margin: 8px 0">{{ ds.description }}</div>
          <div><el-tag size="small">物理表:{{ ds.dwhTables }}</el-tag></div>
        </el-card>
      </el-col>
    </el-row>

    <el-table :data="fields" v-loading="loading" border stripe>
      <el-table-column prop="tableName" label="物理表" width="180" />
      <el-table-column prop="columnName" label="物理列" width="140" />
      <el-table-column prop="fieldType" label="类型" width="100">
        <template #default="{ row }">
          <el-tag :type="row.fieldType === 'METRIC' ? 'warning' : 'success'" size="small">
            {{ row.fieldType === 'METRIC' ? '指标' : '维度' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="业务别名" width="150">
        <template #default="{ row }">
          <el-input v-if="editing === row.id" v-model="editForm.alias" size="small" />
          <span v-else>{{ row.alias }}</span>
        </template>
      </el-table-column>
      <el-table-column label="同义词(逗号分隔)" min-width="240">
        <template #default="{ row }">
          <el-input v-if="editing === row.id" v-model="editForm.synonyms" size="small" />
          <span v-else class="field-syn-tip">{{ row.synonyms }}</span>
        </template>
      </el-table-column>
      <el-table-column label="口径说明" min-width="220">
        <template #default="{ row }">
          <el-input v-if="editing === row.id" v-model="editForm.description" size="small" />
          <span v-else class="field-syn-tip">{{ row.description }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="aggType" label="聚合" width="130" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="{ row }">
          <template v-if="editing === row.id">
            <el-button type="primary" size="small" @click="saveEdit(row)">保存</el-button>
            <el-button size="small" @click="editing = null">取消</el-button>
          </template>
          <el-button v-else size="small" @click="startEdit(row)">编辑</el-button>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>
