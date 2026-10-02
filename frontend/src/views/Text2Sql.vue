<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { CaretRight, MagicStick, Position } from '@element-plus/icons-vue'
import { createSession, executeQuery, listDatasets, listFields, parseQuery } from '@/api'
import type { AnswerPayload, ChatParseResp, Dataset, DatasetField } from '@/api/types'
import AnswerCard from '@/components/AnswerCard.vue'

/**
 * Text2SQL 工作台:两种用法共享同一条"生成 SQL → 审 SQL → 执行"链路
 * - 拼请求(引导式):按字段元数据点选 指标/维度/形态/时间,拼出规范化问题 ——
 *   不会写问题也能查,且拼出的问题命中规则引擎/意图语料的概率远高于随手打字;
 * - 直接询问(自由式):一句话自然语言。
 */

const datasets = ref<Dataset[]>([])
const datasetId = ref<number | null>(null)
const fields = ref<DatasetField[]>([])

const mode = ref<'guide' | 'free'>('guide')

// ---- 引导式表单 ----
const analysis = ref<'value' | 'group' | 'topn' | 'trend' | 'ratio'>('group')
const metric = ref('')
const dimension = ref('')
const topN = ref(10)
const timeRange = ref('')

const TIME_OPTIONS = [
  { label: '全部时间', value: '' },
  { label: '今年', value: '今年' },
  { label: '去年', value: '去年' },
  { label: '近30天', value: '近30天' },
  { label: '近12个月', value: '近12个月' },
  { label: '上个月', value: '上个月' }
]

const metrics = computed(() => fields.value.filter(f => f.fieldType === 'METRIC'))
const dimensions = computed(() => fields.value.filter(
  f => f.fieldType === 'DIMENSION' && f.dataType !== 'DATE' && !f.isHidden))

const needDim = computed(() => ['group', 'topn', 'ratio'].includes(analysis.value))

/** 拼出的规范化问题(实时预览):模板与规则引擎/时间解析器的识别词表对齐 */
const builtQuestion = computed(() => {
  if (!metric.value) return ''
  const t = timeRange.value
  const dim = dimension.value
  switch (analysis.value) {
    case 'value': return `${t}${metric.value}是多少`
    case 'group': return dim ? `${t}各${dim}的${metric.value}` : `${t}${metric.value}是多少`
    case 'topn': return dim ? `${t}${metric.value}Top${topN.value}的${dim}` : `${t}${metric.value}是多少`
    case 'trend': return `${t}每月${metric.value}趋势`
    case 'ratio': return dim ? `${t}各${dim}${metric.value}占比` : `${t}${metric.value}是多少`
  }
  return ''
})

// ---- 自由式 ----
const freeQuestion = ref('')

// ---- 执行链路状态 ----
const parsing = ref(false)
const executing = ref(false)
const parseResp = ref<ChatParseResp | null>(null)
const askedQuestion = ref('')
const result = ref<AnswerPayload | null>(null)
const sessionCache = new Map<number, number>()

async function ensureSession(dsId: number): Promise<number> {
  if (sessionCache.has(dsId)) return sessionCache.get(dsId)!
  const { sessionId } = await createSession(dsId)
  sessionCache.set(dsId, sessionId)
  return sessionId
}

async function loadFields() {
  parseResp.value = null
  result.value = null
  if (!datasetId.value) return
  fields.value = await listFields(datasetId.value)
  metric.value = metrics.value[0]?.alias ?? ''
  dimension.value = dimensions.value[0]?.alias ?? ''
}

async function doParse(q: string) {
  if (!q.trim() || !datasetId.value) return
  askedQuestion.value = q.trim()
  parseResp.value = null
  result.value = null
  parsing.value = true
  try {
    const sessionId = await ensureSession(datasetId.value)
    parseResp.value = await parseQuery(sessionId, q.trim())
    if (parseResp.value.state === 'FAILED') {
      ElMessage.warning(parseResp.value.errorMsg || '未能理解该问题')
    }
  } finally {
    parsing.value = false
  }
}

async function doExecute(parseId: number) {
  if (!parseResp.value) return
  executing.value = true
  try {
    result.value = await executeQuery(parseResp.value.queryId, parseId)
  } finally {
    executing.value = false
  }
}

watch(datasetId, loadFields)

onMounted(async () => {
  datasets.value = await listDatasets()
  if (datasets.value.length) {
    datasetId.value = datasets.value[0].id
  }
})
</script>

<template>
  <div class="t2s-page">
    <el-card shadow="never" class="t2s-card">
      <template #header>
        <div class="t2s-head">
          <span class="title"><el-icon><MagicStick /></el-icon> Text2SQL 工作台</span>
          <el-select v-model="datasetId" placeholder="选择数据集" style="width: 260px">
            <el-option v-for="d in datasets" :key="d.id" :label="d.name" :value="d.id" />
          </el-select>
        </div>
      </template>

      <el-radio-group v-model="mode">
        <el-radio-button value="guide">🧩 拼请求</el-radio-button>
        <el-radio-button value="free">💬 直接询问</el-radio-button>
      </el-radio-group>

      <!-- 模式一:引导式拼装 -->
      <div v-if="mode === 'guide'" class="builder">
        <div class="form-row">
          <div class="form-item">
            <label>分析形态</label>
            <el-select v-model="analysis" style="width: 150px">
              <el-option label="单值指标" value="value" />
              <el-option label="分组统计" value="group" />
              <el-option label="TopN 排行" value="topn" />
              <el-option label="月度趋势" value="trend" />
              <el-option label="占比构成" value="ratio" />
            </el-select>
          </div>
          <div class="form-item">
            <label>指标</label>
            <el-select v-model="metric" style="width: 180px">
              <el-option v-for="m in metrics" :key="m.id" :label="m.alias" :value="m.alias">
                <span>{{ m.alias }}</span>
                <span class="muted" style="float:right;font-size:12px">{{ m.aggType }}</span>
              </el-option>
            </el-select>
          </div>
          <div v-if="needDim" class="form-item">
            <label>分组维度</label>
            <el-select v-model="dimension" placeholder="选择维度" style="width: 180px">
              <el-option v-for="d in dimensions" :key="d.id" :label="d.alias" :value="d.alias" />
            </el-select>
          </div>
          <div v-if="analysis === 'topn'" class="form-item">
            <label>Top N</label>
            <el-select v-model="topN" style="width: 100px">
              <el-option v-for="n in [5, 10, 20]" :key="n" :label="`前 ${n}`" :value="n" />
            </el-select>
          </div>
          <div class="form-item">
            <label>时间范围</label>
            <el-select v-model="timeRange" style="width: 130px">
              <el-option v-for="t in TIME_OPTIONS" :key="t.value" :label="t.label" :value="t.value" />
            </el-select>
          </div>
        </div>

        <div class="preview">
          <span class="muted">拼出的问题:</span>
          <code class="q">{{ builtQuestion || '(请选择指标' + (needDim ? '与维度' : '') + ')' }}</code>
          <el-button type="primary" :icon="Position" :loading="parsing"
                     :disabled="!builtQuestion" @click="doParse(builtQuestion)">
            生成 SQL
          </el-button>
        </div>
      </div>

      <!-- 模式二:自由询问 -->
      <div v-else class="free-ask">
        <el-input v-model="freeQuestion" type="textarea" :rows="2" resize="none"
                  :placeholder="`基于「${datasets.find(d => d.id === datasetId)?.name || ''}」用一句话提问,如:销售额Top10的商品类目`" />
        <el-button type="primary" :icon="Position" :loading="parsing"
                   :disabled="!freeQuestion.trim()" @click="doParse(freeQuestion)">
          生成 SQL
        </el-button>
      </div>
    </el-card>

    <!-- 生成结果:先审 SQL,再执行 -->
    <el-card v-if="parseResp && parseResp.state === 'COMPLETED'" shadow="never" class="t2s-card">
      <template #header>
        <div class="t2s-head">
          <span>生成的 SQL <el-tag size="small" effect="plain">{{ parseResp.candidates[0]?.engine }}</el-tag></span>
        </div>
      </template>
      <div v-for="c in parseResp.candidates" :key="c.parseId" class="candidate">
        <div class="muted" style="margin-bottom: 6px">{{ c.explanation }}</div>
        <pre class="sql">{{ c.sql }}</pre>
        <el-button type="success" :icon="CaretRight" :loading="executing" @click="doExecute(c.parseId)">
          执行查询
        </el-button>
      </div>
    </el-card>

    <el-card v-if="parseResp && parseResp.state === 'FAILED'" shadow="never" class="t2s-card">
      <el-alert type="warning" :closable="false" :title="parseResp.errorMsg || '未能理解该问题'"
                description="试试用「拼请求」模式,或换一种问法" show-icon />
    </el-card>

    <div v-if="result" class="t2s-result">
      <AnswerCard :payload="result" />
    </div>
  </div>
</template>

<style scoped>
.t2s-page {
  max-width: 980px;
  margin: 0 auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.t2s-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.t2s-head .title {
  font-weight: 600;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.builder { margin-top: 14px; }
.form-row {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
}
.form-item { display: flex; flex-direction: column; gap: 4px; }
.form-item label { font-size: 12px; color: #909399; }
.preview {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 10px;
}
.preview .q {
  flex: 1;
  background: var(--el-fill-color-light);
  padding: 8px 12px;
  border-radius: 6px;
  font-size: 14px;
}
.free-ask {
  margin-top: 14px;
  display: flex;
  gap: 10px;
  align-items: flex-end;
}
.free-ask .el-textarea { flex: 1; }
.candidate .sql {
  background: #1e1e2e;
  color: #cdd6f4;
  padding: 12px 14px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.6;
  overflow-x: auto;
  margin: 0 0 10px;
  white-space: pre-wrap;
}
</style>
