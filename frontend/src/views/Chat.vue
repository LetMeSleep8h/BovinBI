<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ChatDotRound, Delete, Promotion } from '@element-plus/icons-vue'
import {
  ask, askStream, createSession, deleteSession, listDatasets, listLlmModels,
  listMessages, listSessions, setApprovalMode
} from '@/api'
import { useUserStore } from '@/store/user'
import type { ChatMessage, ChatSession, Dataset } from '@/api/types'
import AnswerCard from '@/components/AnswerCard.vue'

const sessions = ref<ChatSession[]>([])
const currentId = ref<number | null>(null)
const messages = ref<ChatMessage[]>([])
const question = ref('')
const sending = ref(false)
const datasets = ref<Dataset[]>([])
const userStore = useUserStore()

/** 提问引擎:python(Python Agent,离线也能答电商)/ java(底座链路),可随时切换 */
const engine = ref<'java' | 'python'>('python')

/** 模型选择(V4 Flash/V4 Pro/标准):空=后端配置默认,按请求生效 */
const models = ref<{ id: string; label: string }[]>([])
const model = ref('')

/** 权限划分(每用户独立存储):AUTO 完全允许 / STEP 每一步过问 */
const approvalMode = ref('AUTO')
async function onModeChange(mode: string) {
  await setApprovalMode(mode)
  ElMessage.success(mode === 'AUTO' ? '已切换:完全允许' : '已切换:每一步过问')
}

/** 实时工作流:流式问答过程中收到的 AI 工作步骤 */
const liveSteps = ref<{ seq: number; name: string; detail: string; ok: boolean }[]>([])

const RECOMMEND = [
  '销售额Top10商品类目',
  '各客户州销售额',
  '每月销售额趋势',
  '总销售额是多少',
  '各商品类目订单数',
  '各城市销售额占比',
  '运费最高的商品类目',
  '订单数最多的客户城市'
]

/** 默认数据集:优先电商真实数据(展示主场景),无则退回第一个 */
const defaultDataset = () =>
  datasets.value.find(d => d.name.includes('电商')) ?? datasets.value[0]

const msgScroll = ref<HTMLDivElement>()

async function loadSessions() {
  sessions.value = await listSessions()
}

async function selectSession(id: number) {
  currentId.value = id
  messages.value = await listMessages(id)
  await scrollBottom()
}

async function newChat() {
  const ds = defaultDataset()
  if (!ds) {
    ElMessage.warning('暂无数据集')
    return
  }
  const { sessionId } = await createSession(ds.id)
  await loadSessions()
  await selectSession(sessionId)
}

async function removeSession(id: number) {
  await deleteSession(id)
  if (currentId.value === id) {
    currentId.value = null
    messages.value = []
  }
  await loadSessions()
}

async function send(q?: string) {
  const text = (q ?? question.value).trim()
  if (!text) return
  if (!currentId.value) await newChat()
  question.value = ''
  messages.value.push({
    id: Date.now(), sessionId: currentId.value!, role: 'USER',
    content: text, payload: null, createdAt: ''
  } as ChatMessage)
  await scrollBottom()
  sending.value = true
  liveSteps.value = []
  try {
    // 流式:每一步 AI 工作内容实时上屏,结束拿到完整助手消息
    const bot = await askStream(currentId.value!, text, (s) => {
      liveSteps.value.push(s)
      scrollBottom()
    }, async (a) => {
      // 逐步确认模式:展示待执行 SQL,用户决定放行或取消
      try {
        await ElMessageBox.confirm(
          `${a.explanation || ''}\n\n${a.sql}`,
          '逐步确认 · 是否执行这条 SQL?',
          { confirmButtonText: '确认执行', cancelButtonText: '取消', customClass: 'sql-confirm' }
        )
        return true
      } catch {
        return false
      }
    }, engine.value, model.value || undefined)
    messages.value.push(bot)
  } catch (e) {
    // 流式失败降级回一次性问答,演示不中断
    try {
      const bot = await ask(currentId.value!, text, engine.value, model.value || undefined)
      messages.value.push(bot)
    } catch (ignore) {
      // 两条路都失败:静默(全局拦截器已提示)
    }
  } finally {
    sending.value = false
    liveSteps.value = []
    await scrollBottom()
  }
}

async function scrollBottom() {
  await nextTick()
  msgScroll.value?.scrollTo({ top: msgScroll.value.scrollHeight, behavior: 'smooth' })
}

onMounted(async () => {
  datasets.value = await listDatasets()
  listLlmModels().then(list => { models.value = list || [] }).catch(() => undefined)
  userStore.fetchMe().then(u => { approvalMode.value = u?.approvalMode || 'AUTO' }).catch(() => undefined)
  await loadSessions()
  if (sessions.value.length) await selectSession(sessions.value[0].id)
  else await newChat()
})
</script>

<template>
  <div class="chat-page">
    <div class="session-panel">
      <div class="panel-head">
        <el-button type="primary" style="width: 100%" :icon="ChatDotRound" @click="newChat">新对话</el-button>
      </div>
      <div class="session-list">
        <div v-for="s in sessions" :key="s.id" class="session-item"
             :class="{ active: s.id === currentId }" @click="selectSession(s.id)">
          <span class="title">{{ s.title || '新对话' }}</span>
          <el-icon style="color:#c0c4cc" @click.stop="removeSession(s.id)"><Delete /></el-icon>
        </div>
      </div>
      <div class="muted" style="padding: 10px 14px">数据集:{{ defaultDataset()?.name || '-' }}</div>
    </div>

    <div class="chat-main">
      <div ref="msgScroll" class="msg-scroll">
        <div v-if="messages.length === 0" class="welcome">
          <h2>你好,我是 BovinBI 数据分析助手 📊</h2>
          <p>基于「{{ defaultDataset()?.name }}」数据集,用大白话问数据,我来自动生成 SQL 并绘制图表</p>
          <div class="chip-grid">
            <el-tag v-for="r in RECOMMEND" :key="r" class="chip" effect="plain" size="large" @click="send(r)">
              {{ r }}
            </el-tag>
          </div>
        </div>

        <template v-for="m in messages" :key="m.id">
          <div v-if="m.role === 'USER'" class="msg-row user">
            <div class="user-bubble">{{ m.content }}</div>
          </div>
          <div v-else class="msg-row">
            <AnswerCard :payload="m.payload!" />
          </div>
        </template>

        <div v-if="sending" class="msg-row">
          <el-card style="max-width: 880px; width: 100%">
            <div class="live-steps">
              <div v-for="s in liveSteps" :key="s.seq" class="live-step">
                <span class="dot" :class="s.ok ? 'ok' : 'fail'"></span>
                <b>{{ s.name }}</b>
                <span class="muted">{{ s.detail }}</span>
              </div>
            </div>
            <el-skeleton v-if="liveSteps.length === 0" :rows="3" animated />
            <div class="muted" style="margin-top: 8px">
              {{ liveSteps.length ? 'AI 正在工作,实时步骤如上…' : '正在理解问题 → 召回Schema → 生成SQL → 执行查询…' }}
            </div>
          </el-card>
        </div>
      </div>

      <div class="chat-input-area">
        <div class="input-box">
          <el-input v-model="question" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" resize="none"
                    class="input-textarea"
                    placeholder="问点什么,或随便聊聊 —— 销售额Top10商品类目 / 今天心情不好陪我聊两句(Enter 发送,Shift+Enter 换行)"
                    @keydown.enter.exact.prevent="send()" />
          <div class="input-bottom">
            <div class="input-opts">
              <el-radio-group v-model="engine" size="small">
                <el-radio-button value="java">☕ Java</el-radio-button>
                <el-radio-button value="python">🐍 Python</el-radio-button>
              </el-radio-group>
              <el-select v-model="model" size="small" placeholder="默认模型" class="opt-select wide">
                <el-option v-for="m in models" :key="m.id" :label="m.label" :value="m.id" />
              </el-select>
              <el-select v-model="approvalMode" size="small" class="opt-select" @change="onModeChange">
                <el-option value="AUTO" label="⚡ 完全允许" />
                <el-option value="STEP" label="🔒 每步确认" />
              </el-select>
            </div>
            <button class="send-btn" :disabled="sending || !question.trim()" @click="send()">
              <el-icon :size="18"><Promotion /></el-icon>
            </button>
          </div>
        </div>
        <div class="input-hint">Enter 发送 · Shift+Enter 换行 · 引擎/模型/权限随问随切</div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.input-opts {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}
.opt-select { width: 128px; }
.opt-select.wide { width: 160px; }

/* ChatGPT 式大输入框:圆角容器 + 无边框 textarea + 内嵌底栏 */
.input-box {
  max-width: 880px;
  margin: 0 auto;
  border: 1.5px solid #dcdfe6;
  border-radius: 16px;
  background: #fff;
  padding: 6px 10px 8px;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.06);
  transition: border-color 0.2s, box-shadow 0.2s;
}
.input-box:focus-within {
  border-color: #409eff;
  box-shadow: 0 2px 16px rgba(64, 158, 255, 0.18);
}
.input-box :deep(.input-textarea .el-textarea__inner) {
  border: none;
  box-shadow: none;
  padding: 8px 10px 4px;
  font-size: 15px;
  line-height: 1.6;
  background: transparent;
}
.input-bottom {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 10px;
  padding: 6px 4px 0;
}
.send-btn {
  flex: none;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  border: none;
  background: #409eff;
  color: #fff;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.2s, transform 0.1s;
}
.send-btn:hover:not(:disabled) { background: #337ecc; }
.send-btn:active:not(:disabled) { transform: scale(0.95); }
.send-btn:disabled { background: #c0c4cc; cursor: not-allowed; }
.input-hint {
  max-width: 880px;
  margin: 8px auto 0;
  text-align: center;
  font-size: 12px;
  color: #c0c4cc;
}
.live-steps {
  display: flex;
  flex-direction: column;
  gap: 6px;
  margin-bottom: 8px;
}
.live-step {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
}
.live-step .dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: none;
}
.live-step .dot.ok {
  background: #67c23a;
}
.live-step .dot.fail {
  background: #f56c6c;
}
.live-step b {
  min-width: 72px;
}
.live-step .muted {
  color: #909399;
}
</style>
