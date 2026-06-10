<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { PieChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { init, use, type ECharts } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import { Refresh, StarFilled, TrendCharts, WarningFilled } from '@element-plus/icons-vue'
import api from '../api'
import { loadProducts, selectedProductId } from '../store/useApp'
import { formatDateTime } from '../utils/dateTime'
import {
  categoryLabel,
  issueStatusLabel,
  issueStatusTagType,
  priorityLabel,
  priorityTagType,
  severityLabel,
  severityTagType
} from '../utils/labels'

type RangeMode = 'thisWeek' | 'lastWeek' | 'last7' | 'last30' | 'custom'

use([PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

const router = useRouter()
const loading = ref(false)
const overview = ref<any>({})
const rangeMode = ref<RangeMode>('thisWeek')
const customRange = ref<[Date, Date] | null>(null)
const categoryChartRef = ref<HTMLDivElement | null>(null)

const pad = (value: number) => String(value).padStart(2, '0')

const toLocalDateTime = (date: Date) =>
  `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`

const startOfDay = (date: Date) => {
  const next = new Date(date)
  next.setHours(0, 0, 0, 0)
  return next
}

const endOfDay = (date: Date) => {
  const next = new Date(date)
  next.setHours(23, 59, 59, 999)
  return next
}

const startOfWeek = (date: Date) => {
  const next = startOfDay(date)
  const day = next.getDay() || 7
  next.setDate(next.getDate() - day + 1)
  return next
}

const resolveRange = () => {
  const now = new Date()
  if (rangeMode.value === 'custom' && customRange.value?.length === 2) {
    return { start: startOfDay(customRange.value[0]), end: endOfDay(customRange.value[1]) }
  }
  if (rangeMode.value === 'lastWeek') {
    const start = startOfWeek(now)
    start.setDate(start.getDate() - 7)
    const end = endOfDay(start)
    end.setDate(start.getDate() + 6)
    return { start, end }
  }
  if (rangeMode.value === 'last7') {
    const start = startOfDay(now)
    start.setDate(start.getDate() - 6)
    return { start, end: now }
  }
  if (rangeMode.value === 'last30') {
    const start = startOfDay(now)
    start.setDate(start.getDate() - 29)
    return { start, end: now }
  }
  return { start: startOfWeek(now), end: now }
}

const rangeParams = computed(() => {
  const range = resolveRange()
  return { start: toLocalDateTime(range.start), end: toLocalDateTime(range.end) }
})

const rangeLabel = computed(() => {
  if (rangeMode.value === 'lastWeek') return '上周'
  if (rangeMode.value === 'last7') return '最近 7 天'
  if (rangeMode.value === 'last30') return '最近 30 天'
  if (rangeMode.value === 'custom') return '所选范围'
  return '本周'
})

const rangeNewLabel = computed(() => `${rangeLabel.value}新增反馈`)

const getArray = (value: any): any[] => (Array.isArray(value) ? value : [])
const bugBoard = computed(() => overview.value?.bugBoard || {})
const suggestionBoard = computed(() => overview.value?.suggestionBoard || {})
const praiseBoard = computed(() => overview.value?.praiseBoard || {})
const categoryChartData = computed(() =>
  getArray(overview.value?.categoryBreakdown)
    .map((item) => ({
      name: categoryLabel(item.category ?? item.name ?? item.label),
      value: Number(item.count ?? item.value ?? 0)
    }))
    .filter((item) => item.value > 0)
)
const hasCategoryData = computed(() => categoryChartData.value.length > 0)

let categoryChart: ECharts | null = null

const renderCategoryChart = () => {
  if (!categoryChartRef.value || !hasCategoryData.value) {
    categoryChart?.dispose()
    categoryChart = null
    return
  }
  if (!categoryChart) {
    categoryChart = init(categoryChartRef.value)
  }
  categoryChart.setOption(
    {
      color: ['#ef4444', '#2563eb', '#f59e0b'],
      tooltip: {
        trigger: 'item',
        formatter: '{b}: {c} ({d}%)'
      },
      legend: {
        orient: 'vertical',
        right: 16,
        top: 'center',
        itemWidth: 12,
        itemHeight: 12,
        textStyle: {
          color: '#4b5563'
        }
      },
      series: [
        {
          name: '反馈分类',
          type: 'pie',
          radius: ['50%', '72%'],
          center: ['36%', '50%'],
          avoidLabelOverlap: true,
          label: {
            formatter: '{b}: {c}',
            color: '#374151'
          },
          labelLine: {
            length: 14,
            length2: 8
          },
          data: categoryChartData.value
        }
      ]
    },
    true
  )
}

const resizeCharts = () => {
  categoryChart?.resize()
}

const fetchOverview = async () => {
  if (!selectedProductId.value) return
  loading.value = true
  try {
    const res = await api.get('/dashboard/overview', {
      params: {
        productId: selectedProductId.value,
        start: rangeParams.value.start,
        end: rangeParams.value.end
      }
    })
    overview.value = res.data || {}
    await nextTick()
    renderCategoryChart()
  } finally {
    loading.value = false
  }
}

const goIssue = (id: string) => {
  if (id) router.push(`/issues/${id}`)
}

const handleRangeChange = () => {
  if (rangeMode.value !== 'custom' || customRange.value?.length === 2) {
    fetchOverview()
  }
}

let timer: ReturnType<typeof setInterval> | undefined

onMounted(async () => {
  await loadProducts()
  await fetchOverview()
  window.addEventListener('resize', resizeCharts)
  timer = setInterval(fetchOverview, 30000)
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  window.removeEventListener('resize', resizeCharts)
  categoryChart?.dispose()
  categoryChart = null
})

watch(selectedProductId, fetchOverview)
watch(categoryChartData, async () => {
  await nextTick()
  renderCategoryChart()
})
</script>

<template>
  <div v-loading="loading" class="dashboard">
    <div class="page-head">
      <div>
        <h2>数据看板</h2>
        <p>{{ formatDateTime(rangeParams.start) }} 至 {{ formatDateTime(rangeParams.end) }}</p>
      </div>
      <div class="range-tools">
        <el-radio-group v-model="rangeMode" size="small" @change="handleRangeChange">
          <el-radio-button value="thisWeek">本周</el-radio-button>
          <el-radio-button value="lastWeek">上周</el-radio-button>
          <el-radio-button value="last7">最近 7 天</el-radio-button>
          <el-radio-button value="last30">最近 30 天</el-radio-button>
          <el-radio-button value="custom">自定义</el-radio-button>
        </el-radio-group>
        <el-date-picker
          v-if="rangeMode === 'custom'"
          v-model="customRange"
          type="daterange"
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          size="small"
          style="width: 260px"
          @change="handleRangeChange"
        />
        <el-button :icon="Refresh" size="small" @click="fetchOverview">刷新</el-button>
      </div>
    </div>

    <el-row :gutter="16" class="summary-grid">
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never"><el-statistic title="今日新增" :value="overview.todayCount || 0" /></el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never"><el-statistic :title="rangeNewLabel" :value="overview.weekCount || 0" /></el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never"><el-statistic title="累计反馈" :value="overview.totalCount || 0" /></el-card>
      </el-col>
      <el-col :xs="12" :sm="12" :md="6">
        <el-card shadow="never"><el-statistic title="待处理 Bug" :value="overview.openIssueCount || 0" /></el-card>
      </el-col>
    </el-row>

    <el-card shadow="never" class="category-card">
      <template #header>反馈分类分布</template>
      <div v-show="hasCategoryData" ref="categoryChartRef" class="category-chart"></div>
      <el-empty v-if="!hasCategoryData" description="暂无分类数据" :image-size="80" />
    </el-card>

    <section class="board bug-board">
      <div class="board-head">
        <div>
          <h3><el-icon><WarningFilled /></el-icon>Bug 看板</h3>
          <p>分别查看严重问题、高频问题和本周新建问题。</p>
        </div>
        <div class="board-count">
          <span>Bug 片段</span>
          <strong>{{ bugBoard.claimCount || 0 }}</strong>
        </div>
      </div>

      <el-card shadow="never" header="严重 Bug Top10">
        <el-table :data="getArray(bugBoard.urgentIssues)" size="small" empty-text="暂无严重 Bug">
          <el-table-column prop="title" label="Bug" min-width="240" show-overflow-tooltip />
          <el-table-column prop="module" label="模块" width="130" show-overflow-tooltip />
          <el-table-column label="严重度" width="90">
            <template #default="{ row }">
              <el-tag :type="severityTagType(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="优先级" width="80">
            <template #default="{ row }">
              <el-tag :type="priorityTagType(row.priority)" size="small">{{ priorityLabel(row.priority) }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="issueStatusTagType(row.status)" size="small">
                {{ row.statusLabel || issueStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="weeklyFeedbackCount" :label="rangeNewLabel" width="110" />
          <el-table-column prop="reportCount" label="累计反馈" width="90" />
          <el-table-column label="操作" width="80">
            <template #default="{ row }">
              <el-button text type="primary" @click="goIssue(row.id)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-card>

      <el-row :gutter="16" class="table-row">
        <el-col :xs="24" :lg="12">
          <el-card shadow="never" header="高频 Bug Top10">
            <el-table :data="getArray(bugBoard.topIssues)" size="small" max-height="260" empty-text="暂无数据">
              <el-table-column prop="title" label="Bug" min-width="220" show-overflow-tooltip />
              <el-table-column prop="module" label="模块" width="120" show-overflow-tooltip />
              <el-table-column prop="windowCount" :label="rangeNewLabel" width="110" />
              <el-table-column label="严重度" width="80">
                <template #default="{ row }">
                  <el-tag :type="severityTagType(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="优先级" width="80">
                <template #default="{ row }">
                  <el-tag :type="priorityTagType(row.priority)" size="small">{{ priorityLabel(row.priority) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="reportCount" label="累计反馈" width="90" />
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button text type="primary" @click="goIssue(row.id)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
        <el-col :xs="24" :lg="12">
          <el-card shadow="never" header="本周新建 Bug">
            <el-table :data="getArray(bugBoard.recentIssues)" size="small" max-height="260" empty-text="暂无数据">
              <el-table-column prop="title" label="Bug" min-width="220" show-overflow-tooltip />
              <el-table-column prop="module" label="模块" width="120" show-overflow-tooltip />
              <el-table-column label="严重度" width="90">
                <template #default="{ row }">
                  <el-tag :type="severityTagType(row.severity)" size="small">{{ severityLabel(row.severity) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="优先级" width="80">
                <template #default="{ row }">
                  <el-tag :type="priorityTagType(row.priority)" size="small">{{ priorityLabel(row.priority) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="160">
                <template #default="{ row }">{{ formatDateTime(row.firstReportAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button text type="primary" @click="goIssue(row.id)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>
    </section>

    <section class="board suggestion-board">
      <div class="board-head">
        <div>
          <h3><el-icon><TrendCharts /></el-icon>建议看板</h3>
          <p>按同类建议聚合热度，用于需求判断和产品排期参考。</p>
        </div>
        <div class="board-count">
          <span>建议片段</span>
          <strong>{{ suggestionBoard.claimCount || 0 }}</strong>
        </div>
      </div>
      <el-row :gutter="16">
        <el-col :xs="24" :lg="12">
          <el-card shadow="never" header="高频建议 Top10">
            <el-table :data="getArray(suggestionBoard.topIssues)" size="small" max-height="260" empty-text="暂无建议">
              <el-table-column prop="title" label="建议" min-width="260" show-overflow-tooltip />
              <el-table-column prop="module" label="模块" width="130" show-overflow-tooltip />
              <el-table-column prop="windowCount" :label="rangeNewLabel" width="110" />
              <el-table-column prop="reportCount" label="累计反馈" width="90" />
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button text type="primary" @click="goIssue(row.id)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
        <el-col :xs="24" :lg="12">
          <el-card shadow="never" header="本周新建建议">
            <el-table :data="getArray(suggestionBoard.recentIssues)" size="small" max-height="260" empty-text="暂无数据">
              <el-table-column prop="title" label="建议" min-width="260" show-overflow-tooltip />
              <el-table-column prop="module" label="模块" width="130" show-overflow-tooltip />
              <el-table-column label="状态" width="100">
                <template #default="{ row }">
                  <el-tag :type="issueStatusTagType(row.status)" size="small">
                    {{ row.statusLabel || issueStatusLabel(row.status) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="160">
                <template #default="{ row }">{{ formatDateTime(row.firstReportAt) }}</template>
              </el-table-column>
              <el-table-column label="操作" width="80">
                <template #default="{ row }">
                  <el-button text type="primary" @click="goIssue(row.id)">查看</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-card>
        </el-col>
      </el-row>
    </section>

    <section class="board praise-board">
      <div class="board-head">
        <div>
          <h3><el-icon><StarFilled /></el-icon>好评看板</h3>
          <p>只统计有明确对象的正向反馈。</p>
        </div>
        <div class="board-count">
          <span>有效好评</span>
          <strong>{{ praiseBoard.recordedCount || 0 }}</strong>
        </div>
      </div>
      <el-card shadow="never" header="高频好评 Top10">
        <el-table :data="getArray(praiseBoard.highlights)" size="small" max-height="280" empty-text="暂无有效好评">
          <el-table-column prop="representativeSummary" label="代表好评" min-width="280" show-overflow-tooltip />
          <el-table-column prop="module" label="对象" width="150" show-overflow-tooltip />
          <el-table-column prop="count" label="好评次数" width="90" />
          <el-table-column prop="keywords" label="关键词" width="200" show-overflow-tooltip />
          <el-table-column label="最近时间" width="170">
            <template #default="{ row }">{{ formatDateTime(row.latestAt) }}</template>
          </el-table-column>
        </el-table>
      </el-card>
    </section>
  </div>
</template>

<style scoped>
.dashboard {
  min-width: 0;
}

.page-head,
.board-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-head h2,
.board-head h3 {
  margin: 0;
}

.page-head h2 {
  color: #1f2329;
  font-size: 22px;
  font-weight: 650;
}

.page-head p,
.board-head p {
  margin: 6px 0 0;
  color: #6b7280;
  font-size: 13px;
}

.range-tools {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 8px;
}

.summary-grid,
.table-row {
  margin-bottom: 16px;
}

.category-card {
  margin-bottom: 20px;
}

.category-chart {
  width: 100%;
  height: 280px;
}

.board {
  margin-top: 20px;
}

.board-head h3 {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #1f2329;
  font-size: 18px;
  font-weight: 650;
}

.bug-board .board-head h3 {
  color: #b42318;
}

.suggestion-board .board-head h3 {
  color: #075985;
}

.praise-board .board-head h3 {
  color: #8a5a00;
}

.board-count {
  min-width: 120px;
  text-align: right;
}

.board-count span {
  display: block;
  color: #6b7280;
  font-size: 13px;
}

.board-count strong {
  display: block;
  margin-top: 6px;
  color: #111827;
  font-size: 24px;
  line-height: 1;
  font-weight: 650;
}

:deep(.el-card) {
  border-radius: 8px;
}

:deep(.el-card__header) {
  padding: 12px 16px;
  color: #1f2329;
  font-weight: 600;
}

@media (max-width: 900px) {
  .page-head,
  .board-head {
    flex-direction: column;
  }

  .range-tools {
    justify-content: flex-start;
  }

  .board-count {
    text-align: left;
  }
}
</style>
