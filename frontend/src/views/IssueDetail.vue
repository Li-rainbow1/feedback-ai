<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { issueApi } from '../api'
import { formatDateTime } from '../utils/dateTime'
import {
  bugStatusOptions,
  categoryLabel,
  issueStatusLabel,
  issueStatusTagType,
  priorityLabel,
  priorityOptions,
  priorityTagType,
  severityLabel,
  severityOptions,
  severityTagType,
  suggestionStatusOptions
} from '../utils/labels'

const route = useRoute()
const router = useRouter()
const loading = ref(false)
const changingIssueId = ref('')
const issue = ref<any>({})
const feedbacks = ref<any[]>([])

const isBugIssue = computed(() => String(issue.value?.category || issue.value?.categoryLabel || '').toUpperCase() === 'BUG')
const isSuggestionIssue = computed(() => String(issue.value?.category || issue.value?.categoryLabel || '').toUpperCase() === 'SUGGESTION')
const statusOptions = computed(() => (isBugIssue.value ? bugStatusOptions : isSuggestionIssue.value ? suggestionStatusOptions : []))
const canUpdateStatus = computed(() => statusOptions.value.length > 0 && issue.value?.status !== 'merged')
const canConfirmBug = computed(() => isBugIssue.value && !issue.value?.confirmed)

const showTypicalContent = computed(() => {
  const summary = String(issue.value?.aiSummary || '').trim()
  const typical = String(issue.value?.typicalContent || '').trim()
  return !!typical && typical !== summary
})

const mergeEvidence = computed(() => {
  const items = issue.value?.mergeEvidence || issue.value?.suspectedDuplicates
  return Array.isArray(items) ? items.filter((item) => item?.issueId) : []
})

const isInternalMetaValue = (value: any) => {
  const text = String(value || '').trim().toLowerCase()
  return !text || text.includes('smoke') || text.includes('test')
}

const sampleMeta = (row: any) => {
  const values = [
    row.appVersion ? `版本 ${row.appVersion}` : '',
    isInternalMetaValue(row.deviceInfo) ? '' : row.deviceInfo,
    row.feedbackTime ? formatDateTime(row.feedbackTime) : row.analyzedAt ? formatDateTime(row.analyzedAt) : ''
  ].filter(Boolean)
  return values.join(' / ')
}

const scoreText = (score: any) => {
  const value = Number(score)
  return Number.isFinite(value) ? value.toFixed(4) : '-'
}

const triageSourceDisplay = computed(() => issue.value?.triageSourceLabel || issue.value?.triageSource || '')
const triageReasonDisplay = computed(() => issue.value?.triageReasonDisplay || issue.value?.triageReason || '')

const fetchData = async () => {
  loading.value = true
  try {
    const issueRes = await issueApi.detail(route.params.id as string)
    issue.value = issueRes.data
    feedbacks.value = issueRes.data.sampleFeedbacks || []
  } finally {
    loading.value = false
  }
}

const linkDialogVisible = ref(false)
const linkIssueKey = ref('')
const handleLinkIssue = async () => {
  if (!linkIssueKey.value.trim()) return
  await issueApi.linkIssue(route.params.id as string, linkIssueKey.value.trim())
  ElMessage.success('关联成功')
  linkDialogVisible.value = false
  await fetchData()
}

const statusDialogVisible = ref(false)
const newStatus = ref('')
const openStatusDialog = () => {
  newStatus.value = issue.value.status || 'open'
  statusDialogVisible.value = true
}
const handleStatusUpdate = async () => {
  if (!newStatus.value) return
  await issueApi.updateStatus(route.params.id as string, newStatus.value)
  ElMessage.success('状态已更新')
  statusDialogVisible.value = false
  await fetchData()
}

const handleConfirmIssue = async () => {
  await issueApi.confirm(route.params.id as string)
  ElMessage.success('Bug 已确认')
  await fetchData()
}

const triageDialogVisible = ref(false)
const triageForm = reactive({ severity: 'MEDIUM', priority: 'P3', reason: '' })
const openTriageDialog = () => {
  triageForm.severity = issue.value.severity || 'MEDIUM'
  triageForm.priority = issue.value.priority || 'P3'
  triageForm.reason = ''
  triageDialogVisible.value = true
}
const handleTriageUpdate = async () => {
  if (!triageForm.severity || !triageForm.priority) return
  await issueApi.updateTriage(route.params.id as string, {
    severity: triageForm.severity,
    priority: triageForm.priority,
    reason: triageForm.reason
  })
  ElMessage.success('等级已更新')
  triageDialogVisible.value = false
  await fetchData()
}

const handleReassignCandidate = async (candidate: any) => {
  const targetIssueId = candidate?.issueId
  const claimId = candidate?.claimId
  if (!targetIssueId || !claimId || candidate.current) return
  await ElMessageBox.confirm(
    `确认将该反馈片段调整到 ${candidate.title || '目标问题'} 吗？`,
    '调整相似候选',
    { type: 'warning', confirmButtonText: '确认调整', cancelButtonText: '取消' }
  )
  changingIssueId.value = targetIssueId
  try {
    await issueApi.reassignClaim(claimId, targetIssueId)
    ElMessage.success('归属已调整')
    await fetchData()
  } finally {
    changingIssueId.value = ''
  }
}

onMounted(fetchData)
</script>

<template>
  <div v-loading="loading">
    <el-page-header class="page-header" @back="router.back()">
      <template #content>
        <span class="page-title">问题详情</span>
      </template>
    </el-page-header>

    <el-row v-if="issue.id" :gutter="16">
      <el-col :span="14">
        <el-card header="基本信息" shadow="never">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="标题">
              <strong>{{ issue.title }}</strong>
            </el-descriptions-item>
            <el-descriptions-item label="聚合摘要">{{ issue.aiSummary || '-' }}</el-descriptions-item>
            <el-descriptions-item v-if="showTypicalContent" label="典型反馈">{{ issue.typicalContent || '-' }}</el-descriptions-item>
            <el-descriptions-item label="分类">
              <el-tag>{{ categoryLabel(issue.categoryLabel || issue.category) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="模块">{{ issue.module || '-' }}</el-descriptions-item>
            <el-descriptions-item v-if="isBugIssue" label="严重度">
              <el-tag :type="severityTagType(issue.severity)">
                {{ issue.severityLabel || severityLabel(issue.severity) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item v-if="isBugIssue" label="优先级">
              <el-tag :type="priorityTagType(issue.priority)">
                {{ priorityLabel(issue.priority) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item v-if="isBugIssue && triageSourceDisplay" label="定级来源">
              {{ triageSourceDisplay }}
            </el-descriptions-item>
            <el-descriptions-item v-if="isBugIssue && triageReasonDisplay" label="定级理由">
              {{ triageReasonDisplay }}
            </el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="issueStatusTagType(issue.status)">
                {{ issue.statusLabel || issueStatusLabel(issue.status) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item v-if="isBugIssue" label="是否确认">
              <el-tag :type="issue.confirmed ? 'success' : 'info'">
                {{ issue.confirmedLabel || (issue.confirmed ? '已确认' : '未确认') }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="反馈数">{{ issue.reportCount }}</el-descriptions-item>
            <el-descriptions-item label="影响版本">{{ issue.affectVersions || '-' }}</el-descriptions-item>
            <el-descriptions-item label="首次发现">{{ formatDateTime(issue.firstReportAt) }}</el-descriptions-item>
            <el-descriptions-item label="最近反馈">{{ formatDateTime(issue.latestReportAt) }}</el-descriptions-item>
            <el-descriptions-item v-if="isBugIssue" label="关联禅道 Bug">
              <template v-if="issue.relatedIssue">
                <el-link type="primary">{{ issue.relatedIssue }}</el-link>
              </template>
              <template v-else>
                <el-button size="small" @click="linkDialogVisible = true">关联禅道 Bug</el-button>
              </template>
            </el-descriptions-item>
            <el-descriptions-item label="解决时间">{{ formatDateTime(issue.resolvedAt) }}</el-descriptions-item>
          </el-descriptions>

          <div class="actions">
            <el-button v-if="canUpdateStatus" type="primary" @click="openStatusDialog">更新状态</el-button>
            <el-button v-if="canConfirmBug" type="success" @click="handleConfirmIssue">确认 Bug</el-button>
            <el-button v-if="isBugIssue" @click="openTriageDialog">修改等级</el-button>
            <el-button v-if="isBugIssue && !issue.relatedIssue" @click="linkDialogVisible = true">关联禅道 Bug</el-button>
          </div>
        </el-card>

        <el-card v-if="mergeEvidence.length" class="section-card" header="相似候选" shadow="never">
          <div v-for="candidate in mergeEvidence" :key="candidate.issueId" class="evidence-row">
            <div class="evidence-main">
              <div class="evidence-title">
                <el-link type="primary" @click="router.push(`/issues/${candidate.issueId}`)">
                  {{ candidate.title || '未命名问题' }}
                </el-link>
                <el-tag v-if="candidate.current" type="success" size="small">当前归属</el-tag>
              </div>
              <div class="evidence-meta">
                <el-tag size="small">相似度 {{ scoreText(candidate.score) }}</el-tag>
                <el-tag v-if="candidate.module" size="small" type="info">{{ candidate.module }}</el-tag>
                <el-tag v-if="candidate.severity && isBugIssue" :type="severityTagType(candidate.severity)" size="small">
                  {{ severityLabel(candidate.severity) }}
                </el-tag>
                <span>反馈数 {{ candidate.reportCount || 0 }}</span>
              </div>
            </div>
            <el-button
              v-if="!candidate.current"
              :disabled="!candidate.claimId"
              :loading="changingIssueId === candidate.issueId"
              plain
              type="primary"
              @click="handleReassignCandidate(candidate)"
            >
              改到此
            </el-button>
          </div>
        </el-card>
      </el-col>

      <el-col :span="10">
        <el-card header="时间线" shadow="never">
          <el-timeline>
            <el-timeline-item
              v-for="item in issue.timeline || []"
              :key="`${item.eventType}-${item.createdAt}`"
              :timestamp="formatDateTime(item.createdAt)"
              :type="item.eventType === 'created' ? 'primary' : 'info'"
            >
              {{ item.detail }}
            </el-timeline-item>
          </el-timeline>
        </el-card>

        <el-card class="section-card" shadow="never">
          <template #header>
            <div class="sample-card-header">
              <span>关联反馈原文</span>
              <el-link type="primary" @click="router.push(`/feedbacks?issueId=${issue.id}`)">
                查看全部 {{ issue.reportCount || 0 }} 条
              </el-link>
            </div>
          </template>

          <el-scrollbar v-if="feedbacks.length" max-height="420px">
            <div
              v-for="row in feedbacks"
              :key="row.id"
              class="feedback-sample"
              @click="router.push(`/feedbacks/${row.id}`)"
            >
              <div class="sample-topline">
                <strong class="sample-summary">{{ row.summary || '未生成摘要' }}</strong>
              </div>
              <div class="sample-content">{{ row.rawContent || row.summary || '-' }}</div>
              <div class="sample-meta">{{ sampleMeta(row) || '-' }}</div>
            </div>
          </el-scrollbar>
          <el-empty v-else description="暂无关联反馈原文" />
        </el-card>
      </el-col>
    </el-row>

    <el-empty v-else description="问题不存在" />

    <el-dialog v-model="linkDialogVisible" title="关联禅道 Bug" width="400px">
      <el-form>
        <el-form-item label="禅道 Bug">
          <el-input v-model="linkIssueKey" placeholder="例如：ZT-BUG-123" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="linkDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleLinkIssue">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="statusDialogVisible" title="更新状态" width="400px">
      <el-form>
        <el-form-item label="新状态">
          <el-select v-model="newStatus" class="full-width">
            <el-option v-for="item in statusOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="statusDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleStatusUpdate">确定</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="triageDialogVisible" title="修改等级" width="460px">
      <el-form label-width="86px">
        <el-form-item label="严重度">
          <el-select v-model="triageForm.severity" class="full-width">
            <el-option v-for="item in severityOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="triageForm.priority" class="full-width">
            <el-option v-for="item in priorityOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="原因">
          <el-input v-model="triageForm.reason" :rows="3" maxlength="200" show-word-limit type="textarea" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="triageDialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleTriageUpdate">确定</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.page-header {
  margin-bottom: 20px;
}

.page-title {
  font-size: 18px;
}

.section-card {
  margin-top: 16px;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 16px;
}

.evidence-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
}

.evidence-row:first-child {
  padding-top: 0;
}

.evidence-row:last-child {
  padding-bottom: 0;
  border-bottom: 0;
}

.evidence-main {
  min-width: 0;
}

.evidence-title {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  line-height: 1.5;
}

.evidence-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-top: 8px;
  color: #606266;
  font-size: 12px;
}

.sample-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.feedback-sample {
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
  cursor: pointer;
}

.feedback-sample:first-child {
  padding-top: 0;
}

.feedback-sample:last-child {
  border-bottom: 0;
  padding-bottom: 0;
}

.feedback-sample:hover .sample-summary {
  color: #409eff;
}

.sample-summary {
  min-width: 0;
  font-size: 14px;
  line-height: 1.5;
}

.sample-content {
  display: -webkit-box;
  margin-top: 6px;
  overflow: hidden;
  color: #606266;
  font-size: 13px;
  line-height: 1.6;
  word-break: break-word;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.sample-meta {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
  line-height: 1.4;
}

.full-width {
  width: 100%;
}
</style>
