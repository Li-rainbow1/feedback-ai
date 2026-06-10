<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { reportApi } from '../api'
import { loadProducts, selectedProduct, selectedProductId } from '../store/useApp'
import { formatDateTime } from '../utils/dateTime'
import { markdownExcerpt, renderMarkdown } from '../utils/markdown'

const loading = ref(false)
const generating = ref(false)
const reports = ref<any[]>([])
const detailVisible = ref(false)
const currentReport = ref<any>({})
const renderedReport = computed(() => renderMarkdown(currentReport.value?.content))
const selectedProductName = computed(() => selectedProduct()?.name || '当前产品')
const currentReportTitle = computed(() => {
  const productName = currentReport.value?.productName || selectedProductName.value
  const weekStart = currentReport.value?.weekStart
  const weekEnd = currentReport.value?.weekEnd
  if (weekStart && weekEnd) {
    return `${productName} ${weekStart} ~ ${weekEnd} 周报`
  }
  return `${productName} 周报详情`
})
const feishuReady = computed(() => selectedProduct()?.feishuStatus === 'ENABLED')

const thisWeekStart = () => {
  const date = new Date()
  date.setDate(date.getDate() - date.getDay() + 1)
  return date.toISOString().substring(0, 10)
}

const hasThisWeek = computed(() => reports.value.some((report: any) => report.weekStart === thisWeekStart()))

const fetchList = async () => {
  if (!selectedProductId.value) {
    await loadProducts()
    if (!selectedProductId.value) return
  }
  loading.value = true
  try {
    const res = await reportApi.list(selectedProductId.value)
    reports.value = res.data || []
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

onMounted(fetchList)
watch(
  () => selectedProductId.value,
  () => {
    fetchList()
  }
)

const handleGenerate = async () => {
  if (!selectedProductId.value) {
    ElMessage.warning('请先选择产品')
    return
  }
  if (generating.value) return
  try {
    await ElMessageBox.confirm(`确定生成 ${selectedProductName.value} 本周周报吗？`, '提示', { type: 'info' })
    generating.value = true
    await reportApi.generate(selectedProductId.value)
    ElMessage.success('生成成功')
    await fetchList()
  } catch (error: any) {
    if (error !== 'cancel' && error !== 'close') {
      console.error(error)
      ElMessage.error(error?.message || '生成失败，请稍后重试')
    }
  } finally {
    generating.value = false
  }
}

const handleView = (row: any) => {
  currentReport.value = row
  detailVisible.value = true
}

const handleSend = async (row: any) => {
  if (!feishuReady.value) {
    ElMessage.warning('请先在产品管理中启用飞书通知并配置机器人地址')
    return
  }
  try {
    await reportApi.send(row.id)
    ElMessage.success(row.isSent ? '重新推送成功' : '推送成功')
    await fetchList()
  } catch (error: any) {
    console.error(error)
    ElMessage.error(error?.message || '推送失败，请稍后重试')
  }
}
</script>

<template>
  <div>
    <div class="page-head">
      <h2>{{ selectedProductName }}周报管理</h2>
      <el-button :loading="generating" type="primary" @click="handleGenerate">
        {{ hasThisWeek ? '重新生成周报' : '生成本周周报' }}
      </el-button>
    </div>
    <el-card shadow="never">
      <el-table v-loading="loading" :data="reports" stripe>
        <el-table-column label="周报标题" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ `${row.productName || selectedProductName} ${row.weekStart} ~ ${row.weekEnd} 周报` }}</template>
        </el-table-column>
        <el-table-column label="起始日" prop="weekStart" width="120" />
        <el-table-column label="结束日" prop="weekEnd" width="120" />
        <el-table-column label="内容预览" min-width="300" show-overflow-tooltip>
          <template #default="{ row }">{{ markdownExcerpt(row.content) || '-' }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.isSent ? 'success' : 'info'">{{ row.isSent ? '已推送' : '未推送' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="生成时间" width="175">
          <template #default="{ row }">{{ formatDateTime(row.generatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button text type="primary" @click="handleView(row)">查看</el-button>
            <el-button text type="success" @click="handleSend(row)">
              {{ row.isSent ? '重新推送' : '推送' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="detailVisible" :title="currentReportTitle" top="5vh" width="760px">
      <div v-if="currentReport?.weekStart" class="report-meta">
        {{ currentReport.productName || selectedProductName }} · {{ currentReport.weekStart }} 至 {{ currentReport.weekEnd }}
      </div>
      <div class="report-scroll">
        <article class="report-content" v-html="renderedReport"></article>
      </div>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0;
}

.report-meta {
  color: #64748b;
  font-size: 13px;
  margin: 0 0 16px;
}

.report-scroll {
  max-height: 68vh;
  overflow-y: auto;
  padding: 0 18px 0 2px;
}

.report-content {
  color: #1f2937;
  font-size: 14px;
  line-height: 1.75;
}

.report-content :deep(h1),
.report-content :deep(h2),
.report-content :deep(h3) {
  color: #111827;
  font-weight: 600;
  letter-spacing: 0;
  margin: 24px 0 12px;
}

.report-content :deep(h1:first-child),
.report-content :deep(h2:first-child),
.report-content :deep(h3:first-child) {
  margin-top: 0;
}

.report-content :deep(h2) {
  border-bottom: 1px solid #e5e7eb;
  font-size: 18px;
  padding-bottom: 9px;
}

.report-content :deep(h3) {
  font-size: 16px;
}

.report-content :deep(p) {
  margin: 8px 0 14px;
}

.report-content :deep(ul),
.report-content :deep(ol) {
  margin: 6px 0 16px;
  padding-left: 22px;
}

.report-content :deep(li) {
  margin: 6px 0;
}

.report-content :deep(strong) {
  color: #111827;
  font-weight: 600;
}

.report-content :deep(blockquote) {
  background: #f6f8fb;
  border-left: 3px solid #409eff;
  color: #475569;
  margin: 12px 0;
  padding: 8px 14px;
}

.report-content :deep(table) {
  border-collapse: collapse;
  margin: 12px 0;
  width: 100%;
}

.report-content :deep(th),
.report-content :deep(td) {
  border: 1px solid #e5e7eb;
  padding: 8px 10px;
  text-align: left;
}

.report-content :deep(th) {
  background: #f6f8fb;
}
</style>
