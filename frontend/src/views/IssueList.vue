<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import api from '../api'
import { loadProducts, selectedProductId } from '../store/useApp'
import { formatDateTime } from '../utils/dateTime'
import {
  bugStatusOptions,
  categoryLabel,
  issueStatusLabel,
  issueStatusTagType,
  priorityLabel,
  priorityTagType,
  severityLabel,
  severityOptions,
  severityTagType,
  suggestionStatusOptions
} from '../utils/labels'

const props = defineProps<{
  fixedCategory: 'BUG' | 'SUGGESTION'
  pageTitle: string
}>()

const router = useRouter()
const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const query = reactive({ severity: '', status: '', module: '', page: 1, size: 20 })

const isBugPage = computed(() => props.fixedCategory === 'BUG')
const statusOptions = computed(() => (isBugPage.value ? bugStatusOptions : suggestionStatusOptions))

const fetch = async () => {
  if (!selectedProductId.value) return
  loading.value = true
  try {
    const params: any = {
      productId: selectedProductId.value,
      category: props.fixedCategory,
      page: query.page,
      size: query.size
    }
    if (isBugPage.value && query.severity) params.severity = query.severity
    if (query.status) params.status = query.status
    if (query.module) params.module = query.module
    const res = await api.get('/issues', { params })
    list.value = res.data?.content || []
    total.value = res.data?.totalElements || 0
  } finally {
    loading.value = false
  }
}

const updateStatus = async (id: string, status: string) => {
  if (isBugPage.value && String(status).toLowerCase() === 'acknowledged') {
    await confirmIssue(id)
    return
  }
  await api.patch(`/issues/${id}/status`, { status })
  fetch()
}

const confirmIssue = async (id: string) => {
  await api.patch(`/issues/${id}/confirm`)
  fetch()
}

watch(
  () => props.fixedCategory,
  () => {
    query.severity = ''
    query.status = ''
    query.page = 1
    fetch()
  }
)

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
    <h2 class="page-title">{{ pageTitle }}</h2>
    <el-card class="filter-card" shadow="never">
      <el-form :model="query" inline>
        <el-form-item v-if="isBugPage" label="严重度">
          <el-select v-model="query.severity" clearable placeholder="全部" style="width: 100px">
            <el-option v-for="item in severityOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" style="width: 120px">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="模块">
          <el-input v-model="query.module" clearable placeholder="模块" style="width: 140px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="query.page = 1; fetch()">搜索</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column :label="isBugPage ? 'Bug' : '建议'" min-width="240" prop="title" show-overflow-tooltip />
        <el-table-column label="分类" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ categoryLabel(row.categoryLabel || row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isBugPage" label="严重度" width="90">
          <template #default="{ row }">
            <el-tag :type="severityTagType(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isBugPage" label="优先级" width="80">
          <template #default="{ row }">
            <el-tag :type="priorityTagType(row.priority)" size="small">{{ priorityLabel(row.priority) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="issueStatusTagType(row.status)" size="small">
              {{ row.statusLabel || issueStatusLabel(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column v-if="isBugPage" label="是否确认" width="100">
          <template #default="{ row }">
            <el-tag :type="row.confirmed ? 'success' : 'info'" size="small">
              {{ row.confirmedLabel || (row.confirmed ? '已确认' : '未确认') }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="反馈数" prop="reportCount" width="80" />
        <el-table-column label="首次发现" width="175">
          <template #default="{ row }">{{ formatDateTime(row.firstReportAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="220">
          <template #default="{ row }">
            <el-button text type="primary" @click="router.push('/issues/' + row.id)">详情</el-button>
            <el-dropdown style="margin-left: 8px" @command="(command: string) => updateStatus(row.id, command)">
              <el-button text type="warning">状态</el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item
                    v-for="item in statusOptions"
                    :key="item.value"
                    :command="item.value"
                    :disabled="item.value === row.status"
                  >
                    {{ item.label }}
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
            <el-button
              v-if="isBugPage && !row.confirmed"
              text
              type="success"
              @click="confirmIssue(row.id)"
            >
              确认
            </el-button>
          </template>
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
  </div>
</template>

<style scoped>
.page-title {
  margin: 0 0 16px;
}

.filter-card {
  margin-bottom: 16px;
}

.pager {
  margin-top: 16px;
  text-align: right;
}
</style>
