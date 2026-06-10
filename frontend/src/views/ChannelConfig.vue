<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { channelApi } from '../api'
import { selectedProduct, selectedProductId } from '../store/useApp'

const loading = ref(false)
const channels = ref<any[]>([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)

const defaultForm = () => ({
  name: '',
  sourceKey: '',
  enabled: true
})

const form = reactive(defaultForm())

const webhookUrl = () => `${window.location.origin}/api/v1/feedbacks/webhook`
const webhookFields = [
  { name: 'webhookToken', required: '必填', note: '产品级鉴权 Token，也兼容 token、webhook_token' },
  { name: 'sourceKey', required: '必填', note: '入口标识，对应当前 Webhook 配置项的 sourceKey' },
  { name: 'channel', required: '必填', note: '业务来源渠道，例如 customer_service、in_app、survey' },
  { name: 'rawContent', required: '必填', note: '反馈原文，最长 2000 字，也兼容 content、text、feedback、description' },
  { name: 'userId', required: '可选', note: '外部系统用户 ID，也兼容 externalUserId、openId' },
  { name: 'userName', required: '可选', note: '用户昵称，也兼容 username、nickname' },
  { name: 'appVersion', required: '可选', note: 'App 或 Web 版本，也兼容 version' },
  { name: 'deviceInfo', required: '可选', note: '设备、系统、浏览器，也兼容 device、environment' },
  { name: 'star', required: '可选', note: '满意度评分，范围 1 到 5，也兼容 rating、score' },
  { name: 'feedbackTime', required: '可选', note: '推荐 yyyy-MM-dd HH:mm:ss，也兼容 ISO 时间' }
]

const webhookExample = computed(() => `{
  "webhookToken": "${selectedProduct()?.webhookToken || '产品 Webhook Token'}",
  "sourceKey": "${form.sourceKey || 'mobile-app-webhook'}",
  "channel": "customer_service",
  "rawContent": "用户反馈登录后页面一直转圈，无法进入首页",
  "userId": "u1001",
  "userName": "用户A",
  "appVersion": "5.8.1",
  "deviceInfo": "Huawei Mate60 / Android 14",
  "feedbackTime": "2026-05-22 09:10:00"
}`)

const fetchList = async () => {
  if (!selectedProductId.value) return
  loading.value = true
  try {
    const res = await channelApi.list(selectedProductId.value)
    channels.value = res.data || []
  } catch (error) {
    console.error(error)
  } finally {
    loading.value = false
  }
}

watch(
  () => selectedProductId.value,
  fetchList,
  { immediate: true }
)

const resetForm = () => {
  Object.assign(form, defaultForm())
}

const handleCreate = () => {
  isEdit.value = false
  editId.value = null
  resetForm()
  dialogVisible.value = true
}

const validateForm = () => {
  if (!form.name.trim()) {
    ElMessage.warning('请输入入口名称')
    return false
  }
  if (!form.sourceKey.trim()) {
    ElMessage.warning('请输入 sourceKey')
    return false
  }
  const duplicated = channels.value.some((channel) => channel.id !== editId.value && channel.sourceKey === form.sourceKey)
  if (duplicated) {
    ElMessage.warning('该 sourceKey 已存在，请避免重复配置同一个反馈入口')
    return false
  }
  return true
}

const handleSave = async () => {
  if (!validateForm()) return
  const data = {
    name: form.name,
    type: 'push',
    sourceType: 'webhook',
    sourceKey: form.sourceKey.trim(),
    enabled: form.enabled,
    productId: selectedProductId.value,
    credentials: JSON.stringify({ sourceType: 'webhook' })
  }
  if (isEdit.value && editId.value) {
    await channelApi.update(editId.value, data)
  } else {
    await channelApi.create(data)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  fetchList()
}

const handleEdit = async (id: number) => {
  isEdit.value = true
  editId.value = id
  resetForm()
  const res = await channelApi.get(id)
  const data = res.data
  Object.assign(form, {
    name: data.name || '',
    sourceKey: data.sourceKey || '',
    enabled: data.enabled ?? true
  })
  dialogVisible.value = true
}

const handleDelete = async (id: number) => {
  await ElMessageBox.confirm('确定删除该 Webhook 入口吗？', '提示', { type: 'warning' })
  await channelApi.delete(id)
  ElMessage.success('删除成功')
  fetchList()
}

const copyText = async (text: string) => {
  try {
    await navigator.clipboard.writeText(text)
    ElMessage.success('已复制')
  } catch {
    const textarea = document.createElement('textarea')
    textarea.value = text
    document.body.appendChild(textarea)
    textarea.select()
    document.execCommand('copy')
    document.body.removeChild(textarea)
    ElMessage.success('已复制')
  }
}
</script>

<template>
  <div>
    <div class="page-header">
      <h2>Webhook 接入</h2>
      <el-button type="primary" @click="handleCreate">新增入口</el-button>
    </div>

    <el-alert
      :closable="false"
      class="page-tip"
      show-icon
      title="外部系统请按统一请求格式主动推送反馈。页面文档展示的字段，就是后端真实校验的字段。"
      type="info"
    />

    <el-card class="contract-card" shadow="never">
      <template #header>Webhook 接入约定</template>
      <div class="contract-line">
        <span>请求方式：POST</span>
        <code>{{ webhookUrl() }}</code>
        <el-button size="small" text @click="copyText(webhookUrl())">复制地址</el-button>
      </div>
      <div class="contract-line">
        <span>鉴权方式：webhookToken + sourceKey</span>
        <code>{{ selectedProduct()?.webhookToken || '' }}</code>
        <el-button size="small" text @click="copyText(selectedProduct()?.webhookToken || '')">复制 Token</el-button>
      </div>
      <div class="contract-help">
        <div><code>/api/v1/feedbacks/webhook</code>：异步单条提交</div>
        <div><code>/api/v1/feedbacks/webhook/sync</code>：同步单条提交</div>
        <div><code>/api/v1/feedbacks/webhook/batch</code>：异步批量提交</div>
        <div><code>/api/v1/feedbacks/webhook/batch-sync</code>：同步批量提交</div>
      </div>
      <el-table :data="webhookFields" border size="small">
        <el-table-column label="字段" prop="name" width="140" />
        <el-table-column label="要求" prop="required" width="80" />
        <el-table-column label="说明" prop="note" />
      </el-table>
      <pre class="json-example">{{ webhookExample }}</pre>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="channels" stripe>
        <el-table-column label="名称" prop="name" width="180" />
        <el-table-column label="接入类型" width="120">
          <template #default>
            <el-tag size="small" type="success">Webhook</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Webhook 地址" min-width="320">
          <template #default>
            <code class="muted-code">{{ webhookUrl() }}</code>
            <el-button size="small" text @click="copyText(webhookUrl())">复制</el-button>
          </template>
        </el-table-column>
        <el-table-column label="sourceKey" prop="sourceKey" width="180" />
        <el-table-column label="Token" width="280">
          <template #default>
            <code class="muted-code">{{ selectedProduct()?.webhookToken || '' }}</code>
            <el-button size="small" text @click="copyText(selectedProduct()?.webhookToken || '')">复制</el-button>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">
              {{ row.enabled ? '启用' : '停用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column fixed="right" label="操作" width="180">
          <template #default="{ row }">
            <el-button text type="primary" @click="handleEdit(row.id)">编辑</el-button>
            <el-button text type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑入口' : '新增入口'" width="640px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="名称" required>
          <el-input v-model="form.name" placeholder="例如：移动端反馈 Webhook" />
        </el-form-item>
        <el-form-item label="sourceKey" required>
          <el-input v-model="form.sourceKey" placeholder="例如：mobile-app-webhook" />
        </el-form-item>
        <el-form-item label="Webhook 地址">
          <el-input :model-value="webhookUrl()" readonly>
            <template #append>
              <el-button @click="copyText(webhookUrl())">复制</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="Token">
          <el-input :model-value="selectedProduct()?.webhookToken || ''" readonly>
            <template #append>
              <el-button @click="copyText(selectedProduct()?.webhookToken || '')">复制</el-button>
            </template>
          </el-input>
        </el-form-item>
        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" @click="handleSave">保存</el-button>
      </template>
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

.page-tip {
  margin-bottom: 16px;
}

.contract-card {
  margin-bottom: 16px;
}

.contract-line {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  color: #606266;
}

.contract-help {
  margin: 4px 0 12px;
  color: #606266;
  font-size: 12px;
  line-height: 1.8;
}

.json-example {
  margin: 12px 0 0;
  padding: 12px;
  border-radius: 6px;
  background: #f6f8fa;
  color: #303133;
  white-space: pre-wrap;
  font-size: 12px;
  line-height: 1.5;
}

.muted-code {
  color: #606266;
  font-size: 12px;
  word-break: break-all;
}
</style>
