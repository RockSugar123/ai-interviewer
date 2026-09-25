<script setup>
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { authApi } from '../api'

const router = useRouter()
const mode = ref('login')
const loading = ref(false)
const formRef = ref()
const form = reactive({ username: '', password: '' })

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { pattern: /^\w{4,32}$/, message: '4-32 位字母/数字/下划线', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 64, message: '长度需在 6-64 之间', trigger: 'blur' }
  ]
}

function switchMode(m) {
  mode.value = m
  formRef.value?.clearValidate()
}

async function submit() {
  try {
    await formRef.value.validate()
  } catch {
    return
  }
  loading.value = true
  try {
    if (mode.value === 'register') {
      await authApi.register({ username: form.username, password: form.password })
    }
    const data = await authApi.login({ username: form.username, password: form.password })
    localStorage.setItem('token', data.token)
    localStorage.setItem('username', data.username)
    if (mode.value === 'register') ElMessage.success('注册成功')
    router.push('/')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="logo">面</div>
        <h1>AI 模拟面试官</h1>
        <p class="slogan">粘贴 JD，来一场真实的模拟面试</p>
      </div>

      <div class="mode-tabs">
        <button :class="{ active: mode === 'login' }" @click="switchMode('login')">登录</button>
        <button :class="{ active: mode === 'register' }" @click="switchMode('register')">注册</button>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="submit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            placeholder="密码"
            :prefix-icon="Lock"
          />
        </el-form-item>
        <el-button type="primary" class="submit" size="large" :loading="loading" @click="submit">
          {{ mode === 'login' ? '登录' : '注册并登录' }}
        </el-button>
      </el-form>

      <p class="tips">{{ mode === 'login' ? '没有账号？点击上方「注册」' : '用户名 4-32 位字母/数字/下划线，密码至少 6 位' }}</p>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  background:
    radial-gradient(600px 320px at 50% 18%, rgba(77, 107, 254, 0.14), transparent 70%),
    var(--bg-app);
}

.login-card {
  width: 400px;
  background: var(--bg-card);
  border: 1px solid var(--border);
  border-radius: 16px;
  padding: 36px 36px 24px;
}

.brand { text-align: center; margin-bottom: 26px; }

.logo {
  width: 52px;
  height: 52px;
  margin: 0 auto 14px;
  border-radius: 14px;
  background: linear-gradient(135deg, #4d6bfe, #7c3aed);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
  font-weight: 600;
  color: #fff;
}

.brand h1 { font-size: 20px; margin: 0 0 6px; }

.slogan { margin: 0; color: var(--text-2); font-size: 13px; }

.mode-tabs {
  display: flex;
  background: var(--bg-app);
  border-radius: 10px;
  padding: 4px;
  margin-bottom: 20px;
}

.mode-tabs button {
  flex: 1;
  border: none;
  background: transparent;
  color: var(--text-2);
  height: 34px;
  border-radius: 8px;
  cursor: pointer;
  font-size: 14px;
}

.mode-tabs button.active {
  background: var(--bg-bubble);
  color: var(--text-1);
  font-weight: 500;
}

.submit { width: 100%; margin-top: 4px; border-radius: 10px; }

.tips {
  text-align: center;
  color: var(--text-2);
  font-size: 12px;
  margin: 14px 0 0;
}
</style>
