<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import api, { feedbackApi } from '../api'
import { loadProducts, selectedProductId } from '../store/useApp'
import { formatDateTime } from '../utils/dateTime'
import { categoryLabel } from '../utils/labels'

const router = useRouter()
const route = useRoute()
const loading = ref(false)
const list = ref<any[]>([])
const total = ref(0)
const tableRef = ref()
const query = reactive({ keyword: '', page: 1, size: 20 })
const issueId = computed(() => (route.query.issueId as string) || '')
const isIssueView = computed(() => !!issueId.value)

const hasClaims = (row: any) => Array.isArray(row?.claims) && row.claims.length > 0
const rowClassName = ({ row }: { row: any }) => (hasClaims(row) ? '' : 'row-no-claims')
const handleExpandChange = (row: any) => {
  if (!hasClaims(row)) {
    tableRef.value?.toggleRowExpansion(row, false)
  }
}

const displayStatusTagType = (status: string) => {
  if (status === 'ignored') return 'info'
  if (status === 'low_quality') return 'warning'
  if (status === 'analyzed') return 'success'
  if (status === 'skipped') return ''
  return ''
}

const claimStatusTagType = (status: string) => {
  if (status === 'CREATED' || status === 'RECORDED') return 'success'
  if (status === 'MERGED') return 'warning'
  if (status === 'IGNORED') return 'info'
  return ''
}

const fetch = async () => {
  if (!selectedProductId.value) return
  loading.value = true
  try {
    if (isIssueView.value) {
      const res = await feedbackApi.getByIssueId(issueId.value, query.page, query.size)
      list.value = res.data?.content || []
      total.value = res.data?.totalElements || 0
      return
    }

    const res = await api.get('/feedbacks/raw-search', {
      params: {
        productId: selectedProductId.value,
        keyword: query.keyword || undefined,
        page: query.page,
        size: query.size
      }
    })
    list.value = res.data?.content || []
    total.value = res.data?.totalElements || 0
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  query.page = 1
  fetch()
}

const handlePage = (page: number) => {
  query.page = page
  fetch()
}

const dialogVisible = ref(false)
const form = reactive({
  channel: 'web',
  rawContent: '',
  userId: '',
  userName: '',
  appVersion: '',
  deviceInfo: '',
  star: null as number | null
})

const resetForm = () => {
  Object.assign(form, {
    channel: 'web',
    rawContent: '',
    userId: '',
    userName: '',
    appVersion: '',
    deviceInfo: '',
    star: null
  })
}

const submitFeedback = async () => {
  if (!selectedProductId.value) return
  await feedbackApi.submitManual({
    productId: selectedProductId.value,
    ...form
  })
  ElMessage.success('提交成功')
  dialogVisible.value = false
  resetForm()
  fetch()
}

const importVisible = ref(false)
const importFile = ref<File | null>(null)
const importLoading = ref(false)
const handleImportFileChange = (file: any) => {
  importFile.value = file?.raw || null
}

const excelColumns = [
  { name: '反馈内容', required: '必填', example: '华为 Mate60 升级后一直登录失败，验证码正确也会回到登录页' },
  { name: '用户ID', required: '可选', example: 'u1001' },
  { name: '用户名', required: '可选', example: '用户A' },
  { name: 'App版本', required: '可选', example: '5.8.1' },
  { name: '设备信息', required: '可选', example: 'Huawei Mate60 / Android 14' },
  { name: '满意度评分', required: '可选', example: '范围 1 到 5' },
  { name: '反馈时间', required: '可选', example: '2026-05-22 09:10:00' }
]

const handleImport = async () => {
  if (!importFile.value || !selectedProductId.value) return
  importLoading.value = true
  const fd = new FormData()
  fd.append('file', importFile.value)
  fd.append('productId', String(selectedProductId.value))
  fd.append('channel', 'excel')
  try {
    await api.post('/feedbacks/import/excel', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
    ElMessage.success('导入成功')
    importVisible.value = false
    fetch()
  } catch {
    ElMessage.error('导入失败')
  } finally {
    importLoading.value = false
  }
}

onMounted(async () => {
  await loadProducts()
  fetch()
})

watch(
  () => route.fullPath,
  () => {
    query.page = 1
    fetch()
  }
)
</script>

<template>
  <div>
    <div class="page-head">
      <div>
        <h2>{{ isIssueView ? '问题关联反馈' : '反馈明细' }}</h2>
        <div class="page-subtitle">
          {{ isIssueView ? '当前展示该问题关联的反馈分析结果' : '展示反馈原文、展示状态和拆分后的 claim 处理结果' }}
        </div>
      </div>
      <div v-if="!isIssueView">
        <el-button type="primary" @click="dialogVisible = true">手动提交</el-button>
        <el-button @click="importVisible = true">Excel 导入</el-button>
      </div>
    </div>

    <el-card v-if="!isIssueView" class="search-card" shadow="never">
      <el-form :model="query" inline>
        <el-form-item label="搜索">
          <el-input v-model="query.keyword" clearable placeholder="搜索反馈原文 / 用户" style="width: 240px" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">搜索</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-if="!isIssueView" shadow="never">
      <el-table
        ref="tableRef"
        v-loading="loading"
        :data="list"
        :row-class-name="rowClassName"
        stripe
        @expand-change="handleExpandChange"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div v-if="hasClaims(row)" class="claim-panel">
              <div class="claim-panel-title">拆分后的 claim</div>
              <el-table :data="row.claims" border size="small">
                <el-table-column label="摘要" min-width="220" prop="summary" show-overflow-tooltip />
                <el-table-column label="分类" width="90">
                  <template #default="{ row: claim }">
                    <el-tag size="small">{{ categoryLabel(claim.category) }}</el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="对象/模块" min-width="140" prop="module" show-overflow-tooltip />
                <el-table-column label="状态" width="100">
                  <template #default="{ row: claim }">
                    <el-tag :type="claimStatusTagType(claim.status)" size="small">
                      {{ claim.statusLabel || claim.status || '-' }}
                    </el-tag>
                  </template>
                </el-table-column>
                <el-table-column label="动作" prop="decisionAction" show-overflow-tooltip width="120" />
                <el-table-column label="原因" min-width="220" prop="decisionReason" show-overflow-tooltip />
                <el-table-column label="详情" width="90">
                  <template #default="{ row: claim }">
                    <el-button
                      :disabled="!claim.analyzedId"
                      text
                      type="primary"
                      @click="router.push('/feedbacks/' + claim.analyzedId)"
                    >
                      查看
                    </el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="反馈原文" min-width="300">
          <template #default="{ row }">
            <div class="raw-cell">
              <span class="raw-text">{{ row.rawContent || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="展示状态" width="110">
          <template #default="{ row }">
            <el-tag :type="displayStatusTagType(row.displayStatus)" size="small">
              {{ row.displayStatusLabel || row.statusLabel || '-' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态说明" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.displayReason || '-' }}</template>
        </el-table-column>
        <el-table-column label="AI 摘要" min-width="240" prop="aiSummary" show-overflow-tooltip />
        <el-table-column label="claim 数" prop="claimCount" width="90" />
        <el-table-column label="来源" prop="channel" width="100" />
        <el-table-column label="接收时间" width="175">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total,prev,pager,next"
          @current-change="handlePage"
        />
      </div>
    </el-card>

    <el-card v-else shadow="never">
      <el-table v-loading="loading" :data="list" stripe>
        <el-table-column label="分析摘要" min-width="260" prop="summary" show-overflow-tooltip />
        <el-table-column label="分类" width="100">
          <template #default="{ row }">
            <el-tag size="small">{{ categoryLabel(row.category) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="对象/模块" min-width="160" prop="module" show-overflow-tooltip />
        <el-table-column label="分析时间" width="175">
          <template #default="{ row }">{{ formatDateTime(row.analyzedAt || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="详情" width="90">
          <template #default="{ row }">
            <el-button text type="primary" @click="router.push('/feedbacks/' + row.id)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div class="pager">
        <el-pagination
          v-model:current-page="query.page"
          :page-size="query.size"
          :total="total"
          layout="total,prev,pager,next"
          @current-change="handlePage"
        />
      </div>
    </el-card>

    <el-dialog v-model="dialogVisible" title="手动提交反馈" width="520px">
      <el-form :model="form" label-width="90px">
        <el-form-item label="渠道">
          <el-select v-model="form.channel">
            <el-option label="Web" value="web" />
            <el-option label="外部来源" value="external" />
            <el-option label="工单" value="ticket" />
            <el-option label="社交媒体" value="social" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容">
          <el-input v-model="form.rawContent" :rows="4" type="textarea" />
        </el-form-item>
        <el-form-item label="用户 ID">
          <el-input v-model="form.userId" />
        </el-form-item>
        <el-form-item label="用户名">
          <el-input v-model="form.userName" />
        </el-form-item>
        <el-form-item label="App 版本">
          <el-input v-model="form.appVersion" />
        </el-form-item>
        <el-form-item label="设备信息">
          <el-input v-model="form.deviceInfo" />
        </el-form-item>
        <el-form-item label="满意度">
          <el-input-number v-model="form.star" :max="5" :min="1" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="submitFeedback">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="importVisible" title="Excel 导入" width="720px">
      <el-alert
        :closable="false"
        show-icon
        title="Excel 第一行必须是表头；反馈内容为必填，空白行会自动跳过。"
        type="info"
        style="margin-bottom: 12px"
      />
      <el-table :data="excelColumns" border size="small" style="margin-bottom: 12px">
        <el-table-column label="列名" prop="name" width="110" />
        <el-table-column label="要求" prop="required" width="80" />
        <el-table-column label="示例" prop="example" />
      </el-table>
      <el-form label-width="80px">
        <el-form-item label="文件">
          <el-upload :auto-upload="false" :limit="1" :on-change="handleImportFileChange" accept=".xlsx,.xls" drag>
            <div>拖拽 Excel 到此处</div>
          </el-upload>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button :loading="importLoading" type="primary" @click="handleImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0;
}

.page-subtitle {
  margin-top: 4px;
  color: #909399;
  font-size: 12px;
}

.search-card {
  margin-bottom: 16px;
}

.pager {
  margin-top: 16px;
  text-align: right;
}

.raw-cell {
  line-height: 1.6;
}

.raw-text {
  word-break: break-word;
}

.claim-panel {
  padding: 8px 18px 12px;
  background: #f8fafc;
}

.claim-panel-title {
  margin-bottom: 8px;
  color: #1f2329;
  font-size: 13px;
  font-weight: 600;
}

:deep(.row-no-claims .el-table__expand-icon) {
  visibility: hidden;
  pointer-events: none;
}
</style>
