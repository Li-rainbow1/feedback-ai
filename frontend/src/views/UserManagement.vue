<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import api from '../api'
import { ElMessage, ElMessageBox } from 'element-plus'
import { formatDateTime } from '../utils/dateTime'
import { user as currentUser } from '../store/useApp'

const loading = ref(false)
const users = ref<any[]>([])
const dialogVisible = ref(false)
const isEdit = ref(false)
const editId = ref<number | null>(null)

const form = reactive({
  username: '',
  password: '',
  nickname: '',
  role: 'admin',
  enabled: true
})

const enabledCount = computed(() => users.value.filter(item => item.enabled).length)

const isCurrentUser = (row: any) => {
  const current = currentUser.value || JSON.parse(localStorage.getItem('user') || 'null')
  return current?.id === row.id || current?.username === row.username
}

const canDelete = (row: any) => !isCurrentUser(row) && !(row.enabled && enabledCount.value <= 1)

const fetchList = async () => {
  loading.value = true
  try {
    const res = await api.get('/users')
    users.value = res.data || []
  } finally {
    loading.value = false
  }
}

const handleCreate = () => {
  isEdit.value = false
  editId.value = null
  Object.assign(form, { username: '', password: '', nickname: '', role: 'admin', enabled: true })
  dialogVisible.value = true
}

const handleEdit = async (id: number) => {
  isEdit.value = true
  editId.value = id
  const res = await api.get(`/users/${id}`)
  Object.assign(form, res.data)
  form.password = ''
  dialogVisible.value = true
}

const handleDelete = async (row: any) => {
  if (!canDelete(row)) {
    ElMessage.warning('当前账号或最后一个启用账号不能删除')
    return
  }
  await ElMessageBox.confirm(`确定删除用户 ${row.username}？`, '删除用户', { type: 'warning' })
  await api.delete(`/users/${row.id}`)
  ElMessage.success('删除成功')
  fetchList()
}

const handleSave = async () => {
  const data = { ...form, role: 'admin' }
  if (isEdit.value && editId.value) {
    await api.put(`/users/${editId.value}`, data)
  } else {
    await api.post('/users', data)
  }
  ElMessage.success('保存成功')
  dialogVisible.value = false
  fetchList()
}

onMounted(fetchList)
</script>

<template>
  <div>
    <div class="page-header">
      <h2>用户管理</h2>
      <el-button type="primary" @click="handleCreate">新增用户</el-button>
    </div>

    <el-card shadow="never">
      <el-table :data="users" v-loading="loading" stripe>
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column prop="nickname" label="昵称" width="150" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{ row }">
            <el-button text type="primary" @click="handleEdit(row.id)">编辑</el-button>
            <el-button v-if="canDelete(row)" text type="danger" @click="handleDelete(row)">删除</el-button>
            <span v-else class="muted-action">不可删除</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="480px">
      <el-form :model="form" label-width="96px">
        <el-form-item label="用户名" required>
          <el-input v-model="form.username" />
        </el-form-item>
        <el-form-item label="密码" :required="!isEdit">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="昵称">
          <el-input v-model="form.nickname" />
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

.muted-action {
  color: #909399;
  font-size: 14px;
}
</style>
