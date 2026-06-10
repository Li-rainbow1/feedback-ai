<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../api'
import { loadProducts, user } from '../store/useApp'

const router = useRouter()
const username = ref('admin')
const password = ref('')
const loading = ref(false)

const handleLogin = async () => {
  loading.value = true
  try {
    const res = await authApi.login({ username: username.value, password: password.value })
    const currentUser = res.data
    localStorage.setItem('token', currentUser.token)
    localStorage.setItem('user', JSON.stringify(currentUser))
    user.value = currentUser
    await loadProducts()
    router.push('/dashboard')
  } catch (error: any) {
    ElMessage.error(error?.message || '登录失败')
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2>智能反馈分析系统</h2>
      <el-form label-width="72px">
        <el-form-item label="用户名">
          <el-input v-model="username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            autocomplete="current-password"
            show-password
            type="password"
            @keyup.enter="handleLogin"
          />
        </el-form-item>
        <el-form-item>
          <el-button :loading="loading" class="login-button" type="primary" @click="handleLogin">登录</el-button>
        </el-form-item>
      </el-form>
      <div class="login-tip">请使用系统初始化后的账号登录。</div>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  background: #f5f6fa;
}

.login-card {
  width: 400px;
  border-radius: 8px;
}

.login-card h2 {
  margin: 0 0 24px;
  text-align: center;
  color: #1f2329;
  font-size: 22px;
}

.login-button {
  width: 100%;
}

.login-tip {
  color: #8a8f99;
  font-size: 12px;
  text-align: center;
}
</style>
