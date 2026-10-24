<template>
  <!-- 可视化采集主页：URL 栏 + 网页预览 + 字段列表 + 测试采集 -->
  <div class="capture-view">
    <!-- 顶部 URL 栏 -->
    <el-card shadow="never" class="url-bar">
      <el-form inline>
        <el-form-item label="目标网址">
          <el-input
            v-model="url"
            placeholder="https://example.com"
            style="width: 380px"
            clearable
            @keyup.enter="onRender"
          />
        </el-form-item>
        <el-form-item label="速度">
          <el-select v-model="speedLevel" style="width: 120px">
            <el-option label="慢速（推荐）" value="SLOW" />
            <el-option label="普通" value="NORMAL" />
            <el-option label="快速" value="FAST" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-checkbox v-model="simulateHuman">模拟真人</el-checkbox>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="rendering" @click="onRender">加载预览</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 登录 Cookie：需要登录才能看到内容的站点用，折叠隐藏 -->
    <el-card shadow="never" class="cookie-bar">
      <el-collapse v-model="cookiePanel">
        <el-collapse-item name="cookie">
          <template #title>
            <span>登录 Cookie（可选）</span>
            <el-tag
              v-if="cookie.trim()"
              size="small"
              type="success"
              effect="plain"
              style="margin-left: 8px"
            >已填写</el-tag>
          </template>
          <el-input
            v-model="cookie"
            type="textarea"
            :rows="2"
            placeholder="从已登录的浏览器复制，格式：a=1; b=2"
          />
          <el-input
            v-model="waitSelector"
            placeholder="等待元素（可选）：商品卡加载出来再预览/采集，如淘宝用 a.item-link"
            style="margin-top: 8px"
          />
          <div class="cookie-hint">
            用于淘宝等需要登录或懒加载的站点；Cookie 仅保存在本机，预览、测试采集与任务执行都会注入。
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <!-- 主体：左预览 + 右字段 -->
    <div class="main-body">
      <el-card shadow="never" class="preview-card">
        <template #header>
          <div class="card-header">
            <span>网页预览</span>
            <el-text type="info" size="small">点击页面元素即可添加采集字段</el-text>
          </div>
        </template>
        <div class="preview-area">
          <PreviewFrame
            v-if="html"
            :html="html"
            @element-click="onElementClick"
          />
          <el-empty v-else description="输入网址后点击「加载预览」" />
        </div>
      </el-card>

      <el-card shadow="never" class="field-card">
        <template #header>
          <div class="card-header">
            <span>采集字段（{{ fields.length }}）</span>
          </div>
        </template>
        <FieldList :fields="fields" @delete="onDeleteField" />
      </el-card>
    </div>

    <!-- 底部操作栏 -->
    <el-card shadow="never" class="action-bar">
      <el-space>
        <el-button
          type="success"
          :disabled="fields.length === 0"
          :loading="testing"
          @click="onTestExtract"
        >
          测试采集
        </el-button>
        <el-button
          type="primary"
          :disabled="fields.length === 0 || !url.trim()"
          @click="onSaveTask"
        >
          保存为任务
        </el-button>
        <el-text type="info" size="small">
          测试将调用模块三引擎对当前页面执行一次真实采集
        </el-text>
      </el-space>
    </el-card>

    <!-- 元素确认弹窗 -->
    <ElementInspector
      v-model="inspectorVisible"
      :info="clickedElement"
      @confirm="onAddField"
    />

    <!-- 保存为任务弹窗（复用任务表单） -->
    <TaskFormDialog
      v-model="taskFormVisible"
      :prefill="taskPrefill"
      @saved="onTaskSaved"
    />

    <!-- 采集结果抽屉 -->
    <el-drawer v-model="resultVisible" title="采集结果" size="60%">
      <el-descriptions v-if="result" :column="3" border size="small">
        <el-descriptions-item label="页面 URL">{{ result.pageUrl }}</el-descriptions-item>
        <el-descriptions-item label="标题">{{ result.title }}</el-descriptions-item>
        <el-descriptions-item label="耗时">{{ result.elapsedMs }} ms</el-descriptions-item>
        <el-descriptions-item label="数据行数" :span="3">
          {{ result.rows ? result.rows.length : 0 }} 行
        </el-descriptions-item>
      </el-descriptions>
      <el-table
        v-if="result && result.rows && result.rows.length"
        :data="result.rows"
        border
        size="small"
        style="margin-top: 12px"
      >
        <el-table-column
          v-for="key in fieldKeys"
          :key="key"
          :prop="key"
          :label="key"
          show-overflow-tooltip
          min-width="120"
        />
      </el-table>
      <el-empty
        v-else-if="result"
        description="未采集到数据，请检查选择器是否正确"
      />
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { renderPreview, testExtract } from '../api/crawler'
import PreviewFrame from '../components/PreviewFrame.vue'
import FieldList from '../components/FieldList.vue'
import ElementInspector from '../components/ElementInspector.vue'
import TaskFormDialog from '../components/TaskFormDialog.vue'

// 顶部输入
const url = ref('')
const speedLevel = ref('SLOW')
const simulateHuman = ref(false)
const rendering = ref(false)
const testing = ref(false)
// 登录 Cookie：需要登录的站点用（如淘宝），仅保存在本机
const cookie = ref('')
// 等待元素：懒加载站点（如淘宝）先等商品卡出现，再进行预览/采集
const waitSelector = ref('')
const cookiePanel = ref([])

// 预览与字段
const html = ref('')
const fields = ref([])
const clickedElement = ref(null)
const inspectorVisible = ref(false)

// 采集结果
const result = ref(null)
const resultVisible = ref(false)

// 保存为任务
const taskFormVisible = ref(false)
const taskPrefill = ref(null)

/** 打开任务表单，带入当前网址与字段 */
function onSaveTask() {
  taskPrefill.value = {
    taskName: '',
    targetUrl: url.value.trim(),
    cookie: cookie.value.trim(),
    waitSelector: waitSelector.value.trim(),
    fields: fields.value.map(f => ({
      fieldName: f.fieldName,
      fieldType: f.fieldType,
      locatorType: f.locatorType,
      locatorExpression: f.locatorExpression,
      extractAttr: f.extractAttr
    }))
  }
  taskFormVisible.value = true
}

function onTaskSaved() {
  ElMessage.success('任务已保存，可到「任务管理」中启动')
}

// 结果表格的列（取第一行的所有 key）
const fieldKeys = computed(() => {
  if (!result.value || !result.value.rows || result.value.rows.length === 0) return []
  return Object.keys(result.value.rows[0])
})

/** 加载预览：调用后端 Playwright 渲染页面 */
async function onRender() {
  if (!url.value.trim()) {
    ElMessage.warning('请输入目标网址')
    return
  }
  rendering.value = true
  try {
    const payload = {
      url: url.value.trim(),
      simulateHuman: simulateHuman.value,
      rotateUa: true
    }
    // 仅在填写时提交，避免空串被后端当作无效 Cookie
    if (cookie.value.trim()) payload.cookie = cookie.value.trim()
    if (waitSelector.value.trim()) payload.waitSelector = waitSelector.value.trim()
    const data = await renderPreview(payload)
    html.value = data.html
    ElMessage.success(`页面已加载：${data.title || '无标题'}`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    rendering.value = false
  }
}

/** iframe 中点击了元素：打开确认弹窗 */
function onElementClick(info) {
  clickedElement.value = info
  inspectorVisible.value = true
}

/** 确认添加字段 */
function onAddField(field) {
  fields.value.push(field)
  ElMessage.success(`已添加字段：${field.fieldName}`)
}

/** 删除字段 */
function onDeleteField(index) {
  fields.value.splice(index, 1)
}

/** 测试采集：调用模块三引擎执行一次真实采集 */
async function onTestExtract() {
  if (fields.value.length === 0) {
    ElMessage.warning('请先添加采集字段')
    return
  }
  testing.value = true
  try {
    const payload = {
      url: url.value.trim(),
      fields: fields.value.map(f => ({
        fieldName: f.fieldName,
        fieldType: f.fieldType,
        locatorType: f.locatorType,
        locatorExpression: f.locatorExpression,
        extractAttr: f.extractAttr
      })),
      speedLevel: speedLevel.value,
      simulateHuman: simulateHuman.value,
      rotateUa: true
    }
    if (cookie.value.trim()) payload.cookie = cookie.value.trim()
    if (waitSelector.value.trim()) {
      // 走高阶参数通道：等商品卡出现后再提取，避免抓到骨架屏
      payload.advanced = { waitSelector: waitSelector.value.trim() }
    }
    const data = await testExtract(payload)
    result.value = data
    resultVisible.value = true
    ElMessage.success(`采集成功，共 ${data.rows ? data.rows.length : 0} 行数据`)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    testing.value = false
  }
}
</script>

<style scoped>
.capture-view {
  display: flex;
  flex-direction: column;
  gap: 12px;
  height: 100%;
}
.url-bar :deep(.el-card__body) { padding: 12px 16px; }
/* 登录 Cookie 折叠栏：保持紧凑 */
.cookie-bar :deep(.el-card__body) { padding: 4px 16px; }
.cookie-hint {
  margin-top: 6px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.main-body {
  display: flex;
  gap: 12px;
  flex: 1;
  min-height: 0;
}
.preview-card {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.preview-card :deep(.el-card__body) {
  flex: 1;
  padding: 0;
  overflow: hidden;
}
.preview-area {
  width: 100%;
  height: 100%;
}
.field-card {
  width: 480px;
  display: flex;
  flex-direction: column;
}
.action-bar :deep(.el-card__body) { padding: 12px 16px; }
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
