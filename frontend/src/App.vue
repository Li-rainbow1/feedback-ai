<script setup lang="ts">
import { onMounted } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { user, selectedProductId, products, loadProducts } from './store/useApp'

const router = useRouter()
const route = useRoute()

onMounted(() => { if (user.value) loadProducts() })

const handleLogin = (u: any) => {
  user.value = u
  localStorage.setItem('user', JSON.stringify(u))
  loadProducts()
  router.push('/dashboard')
}

const persistSelectedProduct = (v: number) => {
  if (v) {
    localStorage.setItem('selectedProductId', String(v))
  }
}

const handleLogout = () => {
  localStorage.clear()
  user.value = null
  router.push('/login')
}

const getMenu = () => {
  return [
    { path: '/dashboard', label: '数据看板' },
    {
      label: '问题中心',
      children: [
        { path: '/issues/bugs', label: 'Bug 问题' },
        { path: '/issues/suggestions', label: '建议池' },
        { path: '/praises', label: '好评记录' }
      ]
    },
    { path: '/feedbacks', label: '反馈明细' },
    { path: '/reports', label: '周报管理' },
    { path: '/channels', label: 'Webhook 接入' },
    { path: '/public-reviews', label: '评论采集' },
    { path: '/products', label: '产品管理' },
    { path: '/users', label: '用户管理' },
  ]
}
</script>

<template>
  <router-view v-if="!user" @login="handleLogin" />
  <el-container v-else style="min-height:100vh">
    <el-aside width="200px" style="background:#1d1e2b;display:flex;flex-direction:column">
      <div style="padding:18px;color:#fff;font-size:17px;font-weight:bold;text-align:center;border-bottom:1px solid #2d2e3b">用户反馈智能分析</div>
      <div class="product-switcher">
        <div class="product-switcher-label">当前产品</div>
        <el-select v-model="selectedProductId" placeholder="选择产品" size="small" style="width:100%" @change="persistSelectedProduct">
          <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id"/>
        </el-select>
      </div>
      <el-menu :default-active="route.path" background-color="#1d1e2b" text-color="#a6a7b3" active-text-color="#409eff" style="border-right:none;flex:1" @select="(idx: string) => router.push(idx)">
        <template v-for="m in getMenu()" :key="m.path || m.label">
          <el-sub-menu v-if="m.children" :index="m.label">
            <template #title><span>{{ m.label }}</span></template>
            <el-menu-item v-for="child in m.children" :key="child.path" :index="child.path">
              <span>{{ child.label }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :index="m.path"><span>{{ m.label }}</span></el-menu-item>
        </template>
      </el-menu>
      <div style="padding:10px 14px;border-top:1px solid #2d2e3b;color:#a6a7b3;font-size:12px">
        {{ user?.nickname }} | <el-button text size="small" style="color:#a6a7b3" @click="handleLogout">退出</el-button>
      </div>
    </el-aside>
    <el-main style="background:#f5f6fa;padding:24px"><router-view :key="$route.fullPath" /></el-main>
  </el-container>
</template>

<style scoped>
.product-switcher {
  padding: 10px 14px 12px;
  border-bottom: 1px solid #2d2e3b;
}

.product-switcher-label {
  margin-bottom: 6px;
  color: #8f94a8;
  font-size: 12px;
  line-height: 1;
}
</style>
