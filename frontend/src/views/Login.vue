<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/store/user'

const router = useRouter()
const store = useUserStore()
const loading = ref(false)
const form = reactive({ username: 'admin', password: '' })

async function submit() {
  if (!form.username || !form.password) {
    ElMessage.warning('请输入用户名和密码')
    return
  }
  loading.value = true
  try {
    await store.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push('/chat')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-bg">
    <el-card class="login-card">
      <div class="login-title">
        <el-icon :size="26" color="#409eff"><DataAnalysis /></el-icon>
        <span>BovinBI · 对话式商业智能</span>
      </div>
      <div class="login-sub">自然语言提问,一键生成 SQL 与可视化图表</div>
      <el-form @keyup.enter="submit">
        <el-form-item>
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item>
          <el-input v-model="form.password" type="password" placeholder="密码" :prefix-icon="Lock" size="large" show-password />
        </el-form-item>
        <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="submit">
          登 录
        </el-button>
      </el-form>
      <div class="login-hint">演示账号:admin / bovin123</div>
    </el-card>
  </div>
</template>

<style scoped>
.login-bg {
  height: 100vh;
  display: flex; align-items: center; justify-content: center;
  background: linear-gradient(135deg, #1f2d3d 0%, #2b4a6f 100%);
}
.login-card { width: 380px; padding: 10px 6px; }
.login-title { display: flex; align-items: center; justify-content: center; gap: 10px; font-size: 18px; font-weight: 600; }
.login-sub { text-align: center; color: #909399; font-size: 12px; margin: 10px 0 26px; }
.login-hint { text-align: center; color: #c0c4cc; font-size: 12px; margin-top: 16px; }
</style>
