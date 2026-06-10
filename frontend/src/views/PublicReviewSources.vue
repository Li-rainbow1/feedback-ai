<script setup lang="ts">
import { reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { publicReviewApi } from '../api'
import { selectedProductId } from '../store/useApp'
import { formatDateTime } from '../utils/dateTime'

const loading = ref(false)
const actionLoading = ref<number | null>(null)
const sources = ref<any[]>([])
const editorVisible = ref(false)
const runsVisible = ref(false)
const runs = ref<any[]>([])
const currentSource = ref<any>({})
const editId = ref<number | null>(null)

const defaultForm = () => ({
  name: '',
  platform: 'STEAM',
  appId: '',
  region: null,
  language: 'schinese',
  initializationLimit: 100,
  enabled: true,
  scheduledEnabled: true
})
const form = reactive(defaultForm())

const platformLabel = (value: string) => value === 'STEAM' ? 'Steam' : (value || '-')
const runTypeLabel = (value: string) => ({
  INITIALIZE: '首次初始化',
  MANUAL: '立即采集',
  SCHEDULED: '定时采集'
}[value] || value)
const runStatusLabel = (value: string) => ({
  RUNNING: '抓取中',
  PROCESSING: '分析中',
  SUCCESS: '完成',
  FAILED: '失败'
}[value] || value)
const runStatusType = (value: string) => value === 'SUCCESS' ? 'success' : value === 'FAILED' ? 'danger' : 'warning'

const fetchList = async () => {
  if (!selectedProductId.value) {
    sources.value = []
    return
  }
  loading.value = true
  try {
    const res = await publicReviewApi.list(selectedProductId.value)
    sources.value = res.data || []
  } finally {
    loading.value = false
  }
}

watch(() => selectedProductId.value, fetchList, { immediate: true })

const createSource = () => {
  editId.value = null
  Object.assign(form, defaultForm())
  editorVisible.value = true
}

const editSource = async (row: any) => {
  editId.value = row.id
  const res = await publicReviewApi.get(row.id)
  Object.assign(form, defaultForm(), res.data)
  editorVisible.value = true
}

const saveSource = async () => {
  if (!selectedProductId.value || !form.name.trim() || !form.appId.trim()) {
    ElMessage.warning('请填写来源名称和应用 ID')
    return
  }
  const payload = { ...form, productId: selectedProductId.value }
  if (editId.value) {
    await publicReviewApi.update(editId.value, payload)
  } else {
    await publicReviewApi.create(payload)
  }
  ElMessage.success('保存成功')
  editorVisible.value = false
  await fetchList()
}

const initialize = async (row: any) => {
  if (row.busy) return
  await ElMessageBox.confirm(`将采集最近 ${row.initializationLimit} 条评论并进入分析流程，确定继续？`, '首次初始化', { type: 'warning' })
  actionLoading.value = row.id
  try {
    await publicReviewApi.initialize(row.id)
    ElMessage.success('采集任务已开始，可在记录中查看进度')
    await fetchList()
    await openRuns(row)
  } finally {
    actionLoading.value = null
  }
}

const collect = async (row: any) => {
  if (row.busy) return
  actionLoading.value = row.id
  try {
    await publicReviewApi.collect(row.id)
    ElMessage.success('采集任务已开始，可在记录中查看进度')
    await fetchList()
    await openRuns(row)
  } finally {
    actionLoading.value = null
  }
}

const toggleSource = async (row: any) => {
  if (row.busy) {
    ElMessage.warning('该来源正在采集或分析中，请稍后再操作')
    return
  }
  const nextEnabled = !row.enabled
  if (!nextEnabled) {
    await ElMessageBox.confirm(`停用后不会再采集 ${row.name} 的新评论，历史数据会保留，确定继续？`, '停用来源', { type: 'warning' })
  }
  actionLoading.value = row.id
  try {
    await publicReviewApi.update(row.id, {
      name: row.name,
      productId: row.productId,
      platform: row.platform,
      appId: row.appId,
      region: row.region,
      language: row.language,
      initializationLimit: row.initializationLimit,
      enabled: nextEnabled,
      scheduledEnabled: row.scheduledEnabled
    })
    ElMessage.success(nextEnabled ? '已启用' : '已停用')
    await fetchList()
  } finally {
    actionLoading.value = null
  }
}

const deleteSource = async (row: any) => {
  if (row.hasReviewData) {
    ElMessage.warning('该来源已有评论数据，请停用后保留记录')
    return
  }
  await ElMessageBox.confirm(`确定删除来源 ${row.name}？`, '删除来源', { type: 'warning' })
  await publicReviewApi.delete(row.id)
  ElMessage.success('删除成功')
  await fetchList()
}

const openRuns = async (row: any) => {
  currentSource.value = row
  const res = await publicReviewApi.runs(row.id)
  runs.value = res.data || []
  runsVisible.value = true
}
</script>

<template>
  <div>
    <div class="page-header">
      <h2>评论采集</h2>
      <el-button type="primary" @click="createSource">新增来源</el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="sources" v-loading="loading" stripe>
        <el-table-column prop="name" label="来源名称" min-width="190" />
        <el-table-column label="平台" width="100">
          <template #default="{ row }"><el-tag>{{ platformLabel(row.platform) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="appId" label="应用 ID" width="130" />
        <el-table-column label="初始化" width="105">
          <template #default="{ row }">
            <el-tag :type="row.initialized ? 'success' : 'info'">{{ row.initialized ? '已完成' : '未执行' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近采集" width="170">
          <template #default="{ row }">{{ formatDateTime(row.lastCollectedAt) }}</template>
        </el-table-column>
        <el-table-column label="最近新增" width="86">
          <template #default="{ row }">{{ row.lastNewCount ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="任务状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.busy" type="warning">{{ runStatusLabel(row.activeRunStatus) }}</el-tag>
            <el-tag v-else :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '可采集' : '已停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!row.initialized"
              text
              type="primary"
              :disabled="row.busy || !row.enabled"
              :loading="actionLoading === row.id"
              @click="initialize(row)"
            >
              初始化
            </el-button>
            <el-button
              v-else
              text
              type="primary"
              :disabled="row.busy || !row.enabled"
              :loading="actionLoading === row.id"
              @click="collect(row)"
            >
              立即采集
            </el-button>
            <el-button text @click="openRuns(row)">记录</el-button>
            <el-button text @click="editSource(row)">编辑</el-button>
            <el-button
              v-if="row.hasReviewData"
              text
              :type="row.enabled ? 'warning' : 'success'"
              :disabled="row.busy"
              :loading="actionLoading === row.id"
              @click="toggleSource(row)"
            >
              {{ row.enabled ? '停用' : '启用' }}
            </el-button>
            <el-button
              v-if="!row.hasReviewData && !row.busy"
              text
              type="danger"
              @click="deleteSource(row)"
            >
              删除
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="editorVisible" :title="editId ? '编辑评论来源' : '新增评论来源'" width="560px">
      <el-form :model="form" label-width="104px">
        <el-form-item label="来源名称" required>
          <el-input v-model="form.name" placeholder="例如：黑神话悟空 Steam 评论" />
        </el-form-item>
        <el-form-item label="平台" required>
          <el-tag>Steam</el-tag>
        </el-form-item>
        <el-form-item label="应用 ID" required>
          <el-input v-model="form.appId" placeholder="Steam App ID，例如 2358720" />
        </el-form-item>
        <el-form-item label="评论语言">
          <el-select v-model="form.language" style="width:100%">
            <el-option label="简体中文" value="schinese" />
            <el-option label="全部语言" value="all" />
          </el-select>
        </el-form-item>
        <el-form-item label="首次采集量">
          <el-select v-model="form.initializationLimit" style="width:100%">
            <el-option :value="50" label="最近 50 条" />
            <el-option :value="100" label="最近 100 条" />
            <el-option :value="200" label="最近 200 条" />
          </el-select>
        </el-form-item>
        <el-form-item label="定时采集">
          <el-switch v-model="form.scheduledEnabled" />
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editorVisible = false">取消</el-button>
        <el-button type="primary" @click="saveSource">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="runsVisible" :title="`${currentSource.name || ''} 执行记录`" width="900px">
      <el-table :data="runs" max-height="430" stripe>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ runTypeLabel(row.runType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }"><el-tag :type="runStatusType(row.status)">{{ runStatusLabel(row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="fetchedCount" label="获取" width="70" />
        <el-table-column prop="newCount" label="新增" width="70" />
        <el-table-column prop="duplicateCount" label="重复" width="70" />
        <el-table-column prop="affectedIssueCount" label="影响问题" width="95" />
        <el-table-column label="开始时间" width="172">
          <template #default="{ row }">{{ formatDateTime(row.startedAt) }}</template>
        </el-table-column>
        <el-table-column prop="errorMessage" label="错误信息" min-width="160" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-header h2 {
  margin: 0;
}
</style>
