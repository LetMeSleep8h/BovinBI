<script setup lang="ts">
import { nextTick, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound, Delete, Promotion } from '@element-plus/icons-vue'
import {
  ask, createSession, deleteSession, listDatasets, listMessages, listSessions
} from '@/api'
import type { ChatMessage, ChatSession, Dataset } from '@/api/types'
import AnswerCard from '@/components/AnswerCard.vue'

const sessions = ref<ChatSession[]>([])
const currentId = ref<number | null>(null)
const messages = ref<ChatMessage[]>([])
const question = ref('')
const sending = ref(false)
const datasets = ref<Dataset[]>([])

const RECOMMEND = [
  '近12个月每月产奶量趋势',
  '产奶量Top10牧场',
  '上个月各品种产奶量占比',
  '今年总产奶量',
  '近3个月每月各牧场产奶量',
  '今年各季度乳脂率',
  '各地区泌乳牛数对比',
  '上个月产奶量环比'
]

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
  const ds = datasets.value[0]
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
  try {
    const bot = await ask(currentId.value!, text)
    messages.value.push(bot)
  } finally {
    sending.value = false
    await scrollBottom()
  }
}

async function scrollBottom() {
  await nextTick()
  msgScroll.value?.scrollTo({ top: msgScroll.value.scrollHeight, behavior: 'smooth' })
}

onMounted(async () => {
  datasets.value = await listDatasets()
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
      <div class="muted" style="padding: 10px 14px">数据集:{{ datasets[0]?.name || '-' }}</div>
    </div>

    <div class="chat-main">
      <div ref="msgScroll" class="msg-scroll">
        <div v-if="messages.length === 0" class="welcome">
          <h2>你好,我是 BovinBI 数据分析助手 📊</h2>
          <p>基于「{{ datasets[0]?.name }}」数据集,用大白话问数据,我来自动生成 SQL 并绘制图表</p>
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
            <el-skeleton :rows="3" animated />
            <div class="muted" style="margin-top: 8px">正在理解问题 → 召回Schema → 生成SQL → 执行查询…</div>
          </el-card>
        </div>
      </div>

      <div class="chat-input-area">
        <div class="input-wrap">
          <el-input v-model="question" type="textarea" :rows="2" resize="none"
                    placeholder="试试问:近12个月每月产奶量趋势(Enter 发送)"
                    @keydown.enter.exact.prevent="send()" />
          <el-button type="primary" size="large" :icon="Promotion" :loading="sending" @click="send()">
            发送
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>
