<script setup lang="ts">
import { computed, ref, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import { feedbackApi } from '../api'
import { categoryLabel } from '../utils/labels'
import { formatDateTime } from '../utils/dateTime'

const route = useRoute()
const detail = ref<any>({})
const loading = ref(false)
const sourceMetadata = computed(() => {
  if (!detail.value.sourceMetadata) return {}
  try {
    return JSON.parse(detail.value.sourceMetadata)
  } catch {
    return {}
  }
})
const sourceLabel = computed(() => {
  if (detail.value.sourceType === 'STEAM') return 'Steam'
  return detail.value.channel || '-'
})
onMounted(async () => {
  loading.value = true
  try {
    const res = await feedbackApi.getDetail(route.params.id as string)
    detail.value = res.data
  } finally {
    loading.value = false
  }
})
</script>

<template>
  <div v-loading="loading">
    <el-page-header @back="$router.back()" style="margin-bottom: 20px">
      <template #content>
        <span style="font-size: 18px">反馈片段详情</span>
      </template>
    </el-page-header>

    <el-card v-if="detail.id">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="ID">{{ detail.id }}</el-descriptions-item>
        <el-descriptions-item label="片段摘要">{{ detail.summary }}</el-descriptions-item>
        <el-descriptions-item label="分类">
          <el-tag>{{ categoryLabel(detail.category) }}</el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="模块">{{ detail.module }}</el-descriptions-item>
        <el-descriptions-item label="关键词">{{ detail.keywords }}</el-descriptions-item>
        <el-descriptions-item label="关联问题">
          <el-link v-if="detail.issueId" type="primary" @click="$router.push(`/issues/${detail.issueId}`)">
            {{ detail.issueId }}
          </el-link>
        </el-descriptions-item>
        <el-descriptions-item label="分析时间">{{ formatDateTime(detail.analyzedAt) }}</el-descriptions-item>
        <el-descriptions-item label="原始ID">{{ detail.rawId }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ sourceLabel }}</el-descriptions-item>
        <el-descriptions-item label="反馈时间">{{ formatDateTime(detail.feedbackTime) }}</el-descriptions-item>
        <el-descriptions-item label="外部评论ID">{{ detail.externalReviewId || '-' }}</el-descriptions-item>
        <el-descriptions-item label="来源信息">
          <template v-if="detail.sourceType === 'STEAM'">
            {{ sourceMetadata.recommended ? '推荐' : '不推荐' }}，游玩 {{ sourceMetadata.playtimeMinutes || 0 }} 分钟
          </template>
          <template v-else>-</template>
        </el-descriptions-item>
        <el-descriptions-item label="反馈原文" :span="2">{{ detail.rawContent || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-card>
    <el-empty v-else description="未找到反馈数据" />
  </div>
</template>
