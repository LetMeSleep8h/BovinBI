<script setup lang="ts">
import { onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { useRoute, useRouter } from 'vue-router'
import { DataAnalysis, ChatDotRound, Coin, Document, MagicStick, SwitchButton } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'

const router = useRouter()
const route = useRoute()
const store = useUserStore()

onMounted(() => {
  store.fetchMe().catch(() => undefined)
})

const http = (await import('@/api/http')).default

function modeLabel(mode: string) {
  const cur = store.user?.approvalMode || 'AUTO'
  return `${cur === mode ? '✅ ' : ''}${mode === 'AUTO' ? '完全允许(自动执行)' : '每一步过问(执行前确认)'}`
}

async function setMode(mode: string) {
  await http.put('/auth/approval-mode', { mode })
  await store.fetchMe()
  ElMessage.success(mode === 'AUTO' ? '已切换:完全允许' : '已切换:每一步过问')
}

function logout() {
  store.logout()
  router.push('/login')
}
</script>

<template>
  <div class="layout">
    <div class="layout-header">
      <div class="brand">
        <el-icon :size="22" class="logo"><DataAnalysis /></el-icon>
        <span>BovinBI · 对话式商业智能</span>
      </div>
      <el-dropdown>
        <span style="color: #fff; cursor: pointer; display: flex; align-items: center; gap: 6px">
          {{ store.user?.nickname || store.user?.username || '用户' }}
          <el-icon><ArrowDown /></el-icon>
        </span>
        <template #dropdown>
          <el-dropdown-menu>
            <el-dropdown-item divided disabled>AI 执行权限</el-dropdown-item>
            <el-dropdown-item @click="setMode('AUTO')">{{ modeLabel('AUTO') }}</el-dropdown-item>
            <el-dropdown-item @click="setMode('STEP')">{{ modeLabel('STEP') }}</el-dropdown-item>
            <el-dropdown-item divided @click="logout">
              <el-icon><SwitchButton /></el-icon>退出登录
            </el-dropdown-item>
          </el-dropdown-menu>
        </template>
      </el-dropdown>
    </div>
    <div class="layout-body">
      <div class="layout-aside">
        <el-menu :default-active="route.path" router style="border-right: none">
          <el-menu-item index="/chat">
            <el-icon><ChatDotRound /></el-icon>对话分析
          </el-menu-item>
          <el-menu-item index="/text2sql">
            <el-icon><MagicStick /></el-icon>Text2SQL
          </el-menu-item>
          <el-menu-item index="/datasets">
            <el-icon><Coin /></el-icon>数据集管理
          </el-menu-item>
          <el-menu-item index="/history">
            <el-icon><Document /></el-icon>查询历史
          </el-menu-item>
        </el-menu>
      </div>
      <div class="layout-main">
        <router-view />
      </div>
    </div>
  </div>
</template>
