<script setup lang="ts">
import { onMounted, reactive, ref, watch } from 'vue'
import { praiseApi } from '../api'
import { loadProducts, selectedProductId } from '../store/useApp'
import { formatDateTime } from '../utils/dateTime'

const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ page: 1, size: 20 })

const drawerVisible = ref(false)
const drawerLoading = ref(false)
const activeGroupLabel = ref('')
const claimList = ref<any[]>([])

const fetch = async () => {
  if (!selectedProductId.value) return
  loading.value = true
  try {
    const res = await praiseApi.groups({
      productId: selectedProductId.value,
      page: query.page,
      size: query.size
    })
    list.value = res.data?.content || []
    total.value = res.data?.totalElements || 0
  } finally {
    loading.value = false
  }
}

const openGroup = async (row: any) => {
  activeGroupLabel.value = row.module || row.representativeSummary || '未命名好评组'
  drawerVisible.value = true
  drawerLoading.value = true
  try {
    const res = await praiseApi.groupClaims({
      productId: selectedProductId.value,
      groupId: row.groupId
    })
    claimList.value = res.data || []
  } finally {
    drawerLoading.value = false
  }
}

watch(selectedProductId, () => {
  query.page = 1
  fetch()
})

onMounted(async () => {
  await loadProducts()
  fetch()
})
</script>

<template>
  <div>
    <h2 class="page-title">好评记录</h2>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column label="对象/模块" prop="module" width="180">
          <template #default="{ row }">
            <el-link type="primary" @click="openGroup(row)">{{ row.module || '-' }}</el-link>
          </template>
        </el-table-column>
        <el-table-column label="反馈数" prop="count" width="90" />
        <el-table-column label="代表好评" min-width="260" prop="representativeSummary" show-overflow-tooltip />
        <el-table-column label="关键词" min-width="180" prop="keywords" show-overflow-tooltip>
          <template #default="{ row }">{{ row.keywords || '-' }}</template>
        </el-table-column>
        <el-table-column label="最近时间" width="175">
          <template #default="{ row }">{{ formatDateTime(row.latestAt) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total,prev,pager,next"
          @current-change="(page: number) => { query.page = page; fetch() }"
        />
      </div>
    </el-card>

    <el-drawer v-model="drawerVisible" :title="`${activeGroupLabel} 的好评原文`" size="520px">
      <div v-loading="drawerLoading">
        <div v-for="item in claimList" :key="item.id" class="claim-item">
          <div class="claim-summary">{{ item.summary || item.content || '-' }}</div>
          <div v-if="item.content && item.content !== item.summary" class="claim-content">{{ item.content }}</div>
          <div class="claim-meta">
            <span>{{ item.keywords || '-' }}</span>
            <span>{{ formatDateTime(item.createdAt || item.feedbackTime) }}</span>
          </div>
          <div v-if="item.rawContent" class="raw-content">{{ item.rawContent }}</div>
        </div>
        <el-empty v-if="!claimList.length" description="暂无好评片段" />
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
}

.pager {
  margin-top: 16px;
  text-align: right;
}

.claim-item {
  padding: 14px 0;
  border-bottom: 1px solid #ebeef5;
}

.claim-item:first-child {
  padding-top: 0;
}

.claim-summary {
  font-weight: 600;
  line-height: 1.6;
}

.claim-content,
.raw-content {
  margin-top: 8px;
  color: #606266;
  line-height: 1.6;
  word-break: break-word;
}

.raw-content {
  padding: 8px 10px;
  background: #f7f8fa;
  border-radius: 4px;
}

.claim-meta {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}
</style>
