<template>
  <!-- 任务调度中心：任务列表、调度控制、结果查看、模板管理 -->
  <div class="task-view">
    <el-tabs v-model="activeTab" type="border-card">
      <!-- 任务列表 -->
      <el-tab-pane label="采集任务" name="tasks">
        <div class="toolbar">
          <el-input
            v-model="keyword"
            placeholder="搜索任务名称/网址"
            clearable
            style="width: 260px"
            @keyup.enter="loadTasks(1)"
          />
          <el-select v-model="statusFilter" placeholder="全部状态" clearable style="width: 140px">
            <el-option label="草稿" value="DRAFT" />
            <el-option label="待运行" value="READY" />
            <el-option label="运行中" value="RUNNING" />
            <el-option label="已暂停" value="PAUSED" />
            <el-option label="已终止" value="STOPPED" />
            <el-option label="已完成" value="FINISHED" />
            <el-option label="失败" value="FAILED" />
          </el-select>
          <el-button @click="loadTasks(1)">查询</el-button>
          <el-button type="primary" @click="openCreate">新建任务</el-button>
          <el-button :loading="loading" @click="loadTasks()">刷新</el-button>
        </div>

        <el-table :data="tasks" v-loading="loading" border size="small" style="margin-top: 12px">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="taskName" label="任务名称" min-width="150" show-overflow-tooltip />
          <el-table-column prop="targetUrl" label="目标网址" min-width="200" show-overflow-tooltip />
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.status)" size="small">
                {{ statusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="进度" width="120" align="center">
            <template #default="{ row }">
              <span v-if="runtimeOf(row.id)">
                第 {{ runtimeOf(row.id).currentPage }} / {{ runtimeOf(row.id).maxPages }} 页
              </span>
              <span v-else-if="row.checkpointCursor">
                断点 {{ row.checkpointCursor }}
              </span>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="成功/失败" width="110" align="center">
            <template #default="{ row }">
              <span class="ok-count">{{ row.successCount || 0 }}</span>
              /
              <span class="fail-count">{{ row.failCount || 0 }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="speedLevel" label="速度" width="80" align="center" />
          <el-table-column label="操作" width="330" fixed="right">
            <template #default="{ row }">
              <el-button
                v-if="canStart(row.status)"
                type="success"
                link
                size="small"
                @click="onStart(row)"
              >启动</el-button>
              <el-button
                v-if="canPause(row.status)"
                type="warning"
                link
                size="small"
                @click="onPause(row)"
              >暂停</el-button>
              <el-button
                v-if="row.status === 'PAUSED'"
                type="primary"
                link
                size="small"
                @click="onResume(row)"
              >继续</el-button>
              <el-button
                v-if="canStop(row.status)"
                type="danger"
                link
                size="small"
                @click="onStop(row)"
              >终止</el-button>
              <el-button
                link
                size="small"
                :disabled="row.status === 'RUNNING' || row.status === 'READY'"
                @click="openEdit(row)"
              >编辑</el-button>
              <el-button link size="small" @click="openResults(row)">结果</el-button>
              <el-button link size="small" @click="openClean(row)">清洗</el-button>
              <el-button link size="small" @click="onSaveTemplate(row)">存模板</el-button>
              <el-button
                type="danger"
                link
                size="small"
                :disabled="row.status === 'RUNNING' || row.status === 'READY'"
                @click="onDelete(row)"
              >删除</el-button>
            </template>
          </el-table-column>
        </el-table>

        <el-pagination
          v-model:current-page="page"
          v-model:page-size="size"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next"
          style="margin-top: 12px; justify-content: flex-end"
          @current-change="loadTasks"
          @size-change="loadTasks(1)"
        />
      </el-tab-pane>

      <!-- 任务模板 -->
      <el-tab-pane label="任务模板" name="templates">
        <div class="toolbar">
          <el-text type="info" size="small">
            模板保存任务配置与字段规则，可快速复用；敏感配置以密文保存，不会出现明文
          </el-text>
          <el-button :loading="templateLoading" @click="loadTemplates">刷新</el-button>
        </div>
        <el-table :data="templates" v-loading="templateLoading" border size="small" style="margin-top: 12px">
          <el-table-column prop="id" label="ID" width="70" />
          <el-table-column prop="templateName" label="模板名称" min-width="180" />
          <el-table-column prop="fieldCount" label="字段数" width="90" align="center" />
          <el-table-column prop="remark" label="备注" min-width="160" show-overflow-tooltip />
          <el-table-column prop="createTime" label="创建时间" width="170" />
          <el-table-column label="操作" width="220" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link size="small" @click="onImportTemplate(row)">导入为任务</el-button>
              <el-button link size="small" @click="onExportTemplate(row)">导出JSON</el-button>
              <el-button type="danger" link size="small" @click="onDeleteTemplate(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <!-- 任务表单 -->
    <TaskFormDialog
      v-model="formVisible"
      :task-id="editingTaskId"
      :prefill="prefill"
      @saved="onTaskSaved"
    />

    <!-- 清洗规则配置 -->
    <CleanRuleDialog
      v-model="cleanVisible"
      :task-id="cleanTaskId"
      :field-names="cleanFieldNames"
    />

    <!-- 结果抽屉 -->
    <el-drawer v-model="resultVisible" :title="`采集结果 - ${resultTaskName}`" size="65%">
      <!-- 导出工具栏 -->
      <div class="export-bar">
        <el-select v-model="exportFormat" size="small" style="width: 110px">
          <el-option label="CSV 表格" value="csv" />
          <el-option label="Excel 表格" value="xlsx" />
          <el-option label="JSON 数据" value="json" />
        </el-select>
        <el-checkbox v-model="exportApplyClean" size="small">应用清洗规则</el-checkbox>
        <el-button size="small" type="primary" :loading="exporting" @click="onExport">导出到本机</el-button>
        <el-button size="small" link type="primary" @click="openClean({ id: resultTaskId, taskName: resultTaskName })">清洗规则</el-button>
      </div>
      <el-alert
        v-if="exportInfo"
        :title="`已导出 ${exportInfo.rows} 行 → ${exportInfo.filePath}`"
        type="success"
        :closable="false"
        style="margin-bottom: 10px"
      />
      <el-table :data="resultRows" v-loading="resultLoading" border size="small">
        <el-table-column
          v-for="key in resultColumns"
          :key="key"
          :prop="key"
          :label="key"
          min-width="130"
          show-overflow-tooltip
        />
      </el-table>
      <el-empty v-if="!resultLoading && resultRows.length === 0" description="暂无采集结果" />
      <el-pagination
        v-model:current-page="resultPage"
        :page-size="resultSize"
        :total="resultTotal"
        layout="total, prev, pager, next"
        style="margin-top: 12px; justify-content: flex-end"
        @current-change="loadResults"
      />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import TaskFormDialog from '../components/TaskFormDialog.vue'
import CleanRuleDialog from '../components/CleanRuleDialog.vue'
import {
  listTasks, startTask, pauseTask, resumeTask, stopTask, deleteTask,
  getTaskStatus, getTaskResults, saveTaskAsTemplate,
  listTemplates, importTemplate, exportTemplate, deleteTemplate, getTask
} from '../api/task'
import { exportTask } from '../api/clean'

const activeTab = ref('tasks')
const tasks = ref([])
const loading = ref(false)
const page = ref(1)
const size = ref(10)
const total = ref(0)
const keyword = ref('')
const statusFilter = ref('')

// 运行时状态快照：taskId -> runtime
const runtimes = ref({})
let pollTimer = null

// 表单
const formVisible = ref(false)
const editingTaskId = ref(null)
const prefill = ref(null)

// 结果
const resultVisible = ref(false)
const resultLoading = ref(false)
const resultTaskName = ref('')
const resultRows = ref([])
const resultColumns = ref([])
const resultPage = ref(1)
const resultSize = ref(20)
const resultTotal = ref(0)
let resultTaskId = null

// 模板
const templates = ref([])
const templateLoading = ref(false)

// 清洗规则
const cleanVisible = ref(false)
const cleanTaskId = ref(0)
const cleanFieldNames = ref([])

// 导出
const exportFormat = ref('csv')
const exportApplyClean = ref(true)
const exporting = ref(false)
const exportInfo = ref(null)

const statusMap = {
  DRAFT: '草稿',
  READY: '待运行',
  RUNNING: '运行中',
  PAUSED: '已暂停',
  STOPPED: '已终止',
  FINISHED: '已完成',
  FAILED: '失败'
}
const tagMap = {
  DRAFT: 'info',
  READY: 'warning',
  RUNNING: 'primary',
  PAUSED: 'warning',
  STOPPED: 'info',
  FINISHED: 'success',
  FAILED: 'danger'
}

const statusText = (s) => statusMap[s] || s
const statusTagType = (s) => tagMap[s] || 'info'
const runtimeOf = (id) => runtimes.value[id] || null
const canStart = (s) => ['DRAFT', 'PAUSED', 'STOPPED', 'FINISHED', 'FAILED'].includes(s)
const canPause = (s) => ['RUNNING', 'READY'].includes(s)
const canStop = (s) => ['RUNNING', 'READY', 'PAUSED'].includes(s)

onMounted(async () => {
  await loadTasks(1)
  startPolling()
})

onUnmounted(() => stopPolling())

/** 加载任务列表 */
async function loadTasks(p = page.value) {
  page.value = p
  loading.value = true
  try {
    const data = await listTasks({
      page: page.value,
      size: size.value,
      keyword: keyword.value,
      status: statusFilter.value
    })
    tasks.value = data.records || []
    total.value = data.total || 0
    // 同步刷新活动任务的实时状态
    await refreshActiveRuntimes()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 刷新所有运行中/待运行任务的实时状态 */
async function refreshActiveRuntimes() {
  const active = tasks.value.filter(t => ['RUNNING', 'READY'].includes(t.status))
  const next = { ...runtimes.value }
  await Promise.all(active.map(async (t) => {
    try {
      const rt = await getTaskStatus(t.id)
      next[t.id] = rt
      // 运行结束后同步列表状态
      if (rt.status && rt.status !== t.status && ['FINISHED', 'FAILED', 'STOPPED', 'PAUSED'].includes(rt.status)) {
        t.status = rt.status
        t.successCount = rt.successCount
        t.failCount = rt.failCount
      }
    } catch (e) {
      // 单个任务状态读取失败不影响列表
    }
  }))
  runtimes.value = next
}

/** 轮询：仅在页面可见且有活动任务时请求 */
function startPolling() {
  stopPolling()
  pollTimer = setInterval(async () => {
    const active = tasks.value.filter(t => ['RUNNING', 'READY'].includes(t.status))
    if (active.length === 0) return
    await refreshActiveRuntimes()
  }, 2000)
}

function stopPolling() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

/** 打开新建 */
function openCreate() {
  editingTaskId.value = null
  prefill.value = null
  formVisible.value = true
}

/** 打开编辑 */
function openEdit(row) {
  editingTaskId.value = row.id
  prefill.value = null
  formVisible.value = true
}

function onTaskSaved() {
  loadTasks()
}

/** 调度操作统一包装 */
async function runAction(action, row, successText) {
  try {
    await action(row.id)
    ElMessage.success(successText)
    await loadTasks()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

const onStart = (row) => runAction(startTask, row, '任务已启动')
const onPause = (row) => runAction(pauseTask, row, '已请求暂停，当前页结束后生效')
const onResume = (row) => runAction(resumeTask, row, '任务已继续，将从断点页开始')
const onStop = (row) => runAction(stopTask, row, '已请求终止')

/** 删除任务 */
async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定删除任务「${row.taskName}」吗？已采集的数据会保留。`,
      '删除确认',
      { type: 'warning' }
    )
  } catch (e) {
    return
  }
  try {
    await deleteTask(row.id)
    ElMessage.success('任务已删除')
    await loadTasks()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 保存为模板 */
async function onSaveTemplate(row) {
  try {
    const { value } = await ElMessageBox.prompt('请输入模板名称', '保存为模板', {
      inputValue: row.taskName,
      confirmButtonText: '保存',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '模板名称不能为空')
    })
    await saveTaskAsTemplate(row.id, { templateName: value.trim(), remark: row.remark || '' })
    ElMessage.success('模板已保存')
    if (activeTab.value === 'templates') await loadTemplates()
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') ElMessage.error(e.message)
  }
}

/** 查看结果 */
async function openResults(row) {
  resultTaskId = row.id
  resultTaskName.value = row.taskName
  resultPage.value = 1
  resultRows.value = []
  exportInfo.value = null
  resultVisible.value = true
  await loadResults()
}

/** 打开清洗规则配置：字段名取自任务详情 */
async function openClean(row) {
  try {
    const detail = await getTask(row.id)
    cleanTaskId.value = row.id
    cleanFieldNames.value = (detail.fields || []).map(f => f.fieldName).filter(Boolean)
    cleanVisible.value = true
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 导出任务结果到本机目录 */
async function onExport() {
  if (!resultTaskId) return
  exporting.value = true
  try {
    exportInfo.value = await exportTask(resultTaskId, exportFormat.value, exportApplyClean.value)
    ElMessage.success(`导出成功，共 ${exportInfo.value.rows} 行`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    exporting.value = false
  }
}

async function loadResults(p = resultPage.value) {
  if (!resultTaskId) return
  resultPage.value = p
  resultLoading.value = true
  try {
    const data = await getTaskResults(resultTaskId, resultPage.value, resultSize.value)
    resultTotal.value = data.total || 0
    const rows = (data.records || []).map(record => {
      try {
        return JSON.parse(record.dataJson)
      } catch (e) {
        return { 原始数据: record.dataJson }
      }
    })
    resultRows.value = rows
    // 列取自所有行的 key 并集，保持出现顺序
    const columns = []
    rows.forEach(row => {
      Object.keys(row).forEach(key => {
        if (!columns.includes(key)) columns.push(key)
      })
    })
    resultColumns.value = columns
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    resultLoading.value = false
  }
}

/** 加载模板 */
async function loadTemplates() {
  templateLoading.value = true
  try {
    templates.value = await listTemplates()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    templateLoading.value = false
  }
}

/** 模板导入 */
async function onImportTemplate(row) {
  try {
    const taskId = await importTemplate(row.id)
    ElMessage.success(`已导入为新任务（ID ${taskId}），请确认配置后启动`)
    activeTab.value = 'tasks'
    await loadTasks(1)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 导出模板为本地 JSON 文件 */
async function onExportTemplate(row) {
  try {
    const data = await exportTemplate(row.id)
    const content = JSON.stringify(data, null, 2)
    const blob = new Blob([content], { type: 'application/json;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `${row.templateName || 'task-template'}.json`
    a.click()
    URL.revokeObjectURL(url)
    ElMessage.success('模板已导出')
  } catch (e) {
    ElMessage.error(e.message)
  }
}

/** 删除模板 */
async function onDeleteTemplate(row) {
  try {
    await ElMessageBox.confirm(`确定删除模板「${row.templateName}」吗？`, '删除确认', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await deleteTemplate(row.id)
    ElMessage.success('模板已删除')
    await loadTemplates()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

// 切换到模板页时自动加载
watch(activeTab, (val) => {
  if (val === 'templates') {
    loadTemplates()
  }
})
</script>

<style scoped>
.task-view {
  height: 100%;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.ok-count {
  color: #67c23a;
  font-weight: 600;
}
.fail-count {
  color: #f56c6c;
}
.export-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 10px;
}
</style>
