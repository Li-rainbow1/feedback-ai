<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { productApi } from '../api'
import { loadProducts } from '../store/useApp'

const loading = ref(false)
const products = ref<any[]>([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)
const feishuWebhookMask = '********'

const form = reactive({
  name: '',
  description: '',
  teamName: '',
  enabled: true,
  zentaoProductId: null as number | null,
  feishuEnabled: false,
  feishuWebhookUrl: '',
  clearFeishuWebhook: false,
  feishuConfigured: false
})

const resetForm = () => {
  Object.assign(form, {
    name: '',
    description: '',
    teamName: '',
    enabled: true,
    zentaoProductId: null,
    feishuEnabled: false,
    feishuWebhookUrl: '',
    clearFeishuWebhook: false,
    feishuConfigured: false
  })
}

const feishuTagType = (status: string) => {
  if (status === 'ENABLED') return 'success'
  if (status === 'MISSING_WEBHOOK') return 'warning'
  return 'info'
}

const currentFeishuStatusLabel = computed(() => {
  if (!form.feishuEnabled) return '已关闭'
  if (form.clearFeishuWebhook) return '未配置地址'
  if (form.feishuWebhookUrl.trim()) return '已启用'
  if (form.feishuConfigured) return '已启用'
  return '未配置地址'
})

const fetchList = async () => {
  loading.value = true
  try {
    const res = await productApi.list()
    products.value = res.data
    await loadProducts()
  } finally {
    loading.value = false
  }
}

const handleCreate = () => {
  isEdit.value = false
  editId.value = null
  resetForm()
  dialogVisible.value = true
}

const handleEdit = async (id: number) => {
  isEdit.value = true
  editId.value = id
  const res = await productApi.get(id)
  Object.assign(form, res.data, {
    feishuWebhookUrl: res.data?.feishuConfigured ? feishuWebhookMask : '',
    clearFeishuWebhook: false
  })
  dialogVisible.value = true
}

const handleDelete = async (id: number) => {
  await ElMessageBox.confirm('确定删除该产品？相关反馈和问题也会被删除。', '警告', { type: 'warning' })
  await productApi.delete(id)
  ElMessage.success('删除成功')
  await loadProducts()
  fetchList()
}

const handleSave = async () => {
  const payload = {
    ...form,
    feishuWebhookUrl: form.feishuWebhookUrl === feishuWebhookMask ? '' : form.feishuWebhookUrl
  }
  if (isEdit.value && editId.value) {
    await productApi.update(editId.value, payload)
  } else {
    await productApi.create(payload)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  await loadProducts()
  fetchList()
}

onMounted(fetchList)
</script>

<template>
  <div>
    <div class="page-head">
      <h2>产品管理</h2>
      <el-button type="primary" @click="handleCreate">新增产品</el-button>
    </div>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="products" stripe>
        <el-table-column label="产品名称" prop="name" width="180" />
        <el-table-column label="描述" min-width="200" prop="description" show-overflow-tooltip />
        <el-table-column label="负责团队" prop="teamName" width="140" />
        <el-table-column label="禅道产品 ID" width="120">
          <template #default="{ row }">{{ row.zentaoProductId || '-' }}</template>
        </el-table-column>
        <el-table-column label="飞书通知" width="120">
          <template #default="{ row }">
            <el-tag :type="feishuTagType(row.feishuStatus)">
              {{ row.feishuStatusLabel || '已关闭' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="Webhook Token" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <code class="token-cell">{{ row.webhookToken }}</code>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button text type="primary" @click="handleEdit(row.id)">编辑</el-button>
            <el-button text type="danger" @click="handleDelete(row.id)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑产品' : '新增产品'" width="520px">
      <el-form :model="form" label-width="100px">
        <el-form-item label="产品名称" required>
          <el-input v-model="form.name" />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="form.description" :rows="2" type="textarea" />
        </el-form-item>
        <el-form-item label="负责团队">
          <el-input v-model="form.teamName" />
        </el-form-item>
        <el-form-item label="禅道产品 ID">
          <el-input-number v-model="form.zentaoProductId" :min="1" placeholder="禅道对应产品 ID" style="width: 100%" />
        </el-form-item>
        <el-form-item label="飞书通知">
          <el-switch v-model="form.feishuEnabled" />
          <span class="form-note">仅用于周报推送</span>
        </el-form-item>
        <el-form-item label="机器人地址">
          <el-input
            v-model="form.feishuWebhookUrl"
            :placeholder="form.feishuConfigured ? '已配置，留空保持现有地址' : '填写飞书自定义机器人 Webhook 地址'"
            show-password
            type="password"
          />
          <div class="form-help">当前状态：{{ currentFeishuStatusLabel }}</div>
          <div class="form-help">开关打开但地址为空时，不会发送周报。</div>
        </el-form-item>
        <el-form-item v-if="form.feishuConfigured" label="清空地址">
          <el-checkbox v-model="form.clearFeishuWebhook">移除当前产品的机器人地址</el-checkbox>
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
.page-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 16px;
}

.page-head h2 {
  margin: 0;
}

.token-cell {
  font-size: 12px;
  word-break: break-all;
}

.form-note {
  margin-left: 10px;
  color: #909399;
  font-size: 12px;
}

.form-help {
  margin-top: 6px;
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
}
</style>
