<template>
  <!-- 打码方案管理对话框：列表 + 编辑 + 测试连接（高阶面板） -->
  <el-dialog
    :model-value="modelValue"
    title="第三方打码方案"
    width="900px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="loadList"
  >
    <el-alert
      type="info"
      :closable="false"
      title="软件不内置验证码识别模型，仅把图片按模板转发给你配置的第三方打码平台；密钥加密存储，打码请求由本机直接发起。"
      style="margin-bottom: 12px"
    />
    <div class="captcha-layout">
      <!-- 左：方案列表 -->
      <div class="config-list">
        <div class="config-list__header">
          <span>方案列表</span>
          <el-button size="small" type="primary" plain @click="openCreate">新建方案</el-button>
        </div>
        <el-table :data="configs" v-loading="loading" size="small" border highlight-current-row @current-change="onSelect">
          <el-table-column prop="configName" label="名称" min-width="110" show-overflow-tooltip />
          <el-table-column label="状态" width="70" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? '启用' : '停用' }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="80" align="center">
            <template #default="{ row }">
              <el-button type="danger" link size="small" @click.stop="onDelete(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!loading && configs.length === 0" description="暂无方案，点击「新建方案」" :image-size="60" />
      </div>

      <!-- 右：编辑表单 -->
      <div class="config-form" v-if="form">
        <el-form label-width="96px" size="small">
          <el-form-item label="方案名称" required>
            <el-input v-model="form.configName" placeholder="如：某平台-普通验证码" />
          </el-form-item>
          <el-form-item label="接口地址" required>
            <el-input v-model="form.apiUrl" placeholder="https://platform.example.com/api/upload" />
          </el-form-item>
          <el-form-item label="请求方法">
            <el-radio-group v-model="form.httpMethod">
              <el-radio value="POST">POST</el-radio>
              <el-radio value="GET">GET</el-radio>
            </el-radio-group>
            <span style="margin-left: 16px">上传方式</span>
            <el-radio-group v-model="form.uploadMode" style="margin-left: 8px">
              <el-radio value="BASE64">Base64</el-radio>
              <el-radio value="IMAGE_URL">图片URL</el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="参数模板" required>
            <el-input
              v-model="form.paramTemplate"
              type="textarea"
              :rows="3"
              placeholder='{"image":"{{image_base64}}","key":"{{api_key}}"}'
            />
          </el-form-item>
          <el-form-item label="请求头JSON">
            <el-input
              v-model="form.requestHeaders"
              type="textarea"
              :rows="2"
              placeholder='{"Authorization":"Bearer {{api_key}}"}（可留空）'
            />
          </el-form-item>
          <el-form-item label="结果JSONPath" required>
            <el-input v-model="form.resultJsonPath" placeholder="$.data.result" />
          </el-form-item>
          <el-form-item label="平台密钥">
            <el-input
              v-model="form.apiKey"
              :placeholder="form.id && form.apiKeyConfigured ? '已配置，留空保持不变' : '平台Key/Token（加密存储）'"
              show-password
            />
          </el-form-item>
          <el-form-item label="超时/重试">
            <el-input-number v-model="form.timeoutMs" :min="1000" :step="1000" style="width: 130px" />
            <span class="form-hint">毫秒</span>
            <el-input-number v-model="form.retryTimes" :min="0" :max="10" style="width: 90px" />
            <span class="form-hint">次重试</span>
            <el-input-number v-model="form.pollIntervalMs" :min="500" :step="500" style="width: 130px" />
            <span class="form-hint">轮询毫秒</span>
          </el-form-item>
          <el-form-item label="启用">
            <el-switch v-model="form.enabled" />
            <el-input v-model="form.remark" placeholder="备注（可选）" style="margin-left: 16px; width: 260px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :loading="saving" @click="onSave">保存方案</el-button>
            <el-button :loading="testing" @click="onTest">测试连接</el-button>
          </el-form-item>
        </el-form>
        <el-alert
          v-if="testResult"
          :type="testResult.success ? 'success' : 'error'"
          :closable="false"
          :title="testResult.success
            ? `识别成功：${testResult.code}（耗时 ${testResult.costMs}ms）`
            : `识别失败：${testResult.errorMsg || '未知错误'}`"
        />
      </div>
      <el-empty v-else description="选择左侧方案查看/编辑，或新建方案" :image-size="80" class="config-form" />
    </div>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listCaptchaConfigs, saveCaptchaConfig, deleteCaptchaConfig, testCaptchaSolve } from '../api/captcha'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'changed'])

const configs = ref([])
const loading = ref(false)
const form = ref(null)
const saving = ref(false)
const testing = ref(false)
const testResult = ref(null)

// 1x1 白色 PNG，用于测试打码链路（真实平台一般会拒绝并返回错误，正好验证错误透传）
const TEST_IMAGE_BASE64 = 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg=='

async function loadList () {
  loading.value = true
  form.value = null
  testResult.value = null
  try {
    configs.value = await listCaptchaConfigs()
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

function emptyForm () {
  return {
    id: null,
    configName: '',
    apiUrl: '',
    httpMethod: 'POST',
    requestHeaders: '',
    paramTemplate: '{"image":"{{image_base64}}"}',
    uploadMode: 'BASE64',
    resultJsonPath: '$.data.result',
    apiKey: '',
    pollIntervalMs: 3000,
    timeoutMs: 30000,
    retryTimes: 3,
    enabled: true,
    remark: ''
  }
}

function openCreate () {
  form.value = emptyForm()
  testResult.value = null
}

/** 选中列表行：载入编辑（密钥不回填，留空表示保持不变） */
function onSelect (row) {
  if (!row) return
  form.value = { ...emptyForm(), ...row, apiKey: '', apiKeyConfigured: row.apiKeyConfigured }
  testResult.value = null
}

async function onSave () {
  saving.value = true
  try {
    const id = await saveCaptchaConfig(form.value)
    ElMessage.success('方案已保存')
    emit('changed')
    await loadList()
    // 保存后回选该方案
    const saved = configs.value.find(c => c.id === id)
    if (saved) onSelect(saved)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}

/** 测试连接：用当前表单内容（不落库）发起一次真实打码转发 */
async function onTest () {
  testing.value = true
  testResult.value = null
  try {
    // 测试时若密钥留空且是已保存方案，提示先保存再测（后端测试接口不读库）
    const config = { ...form.value }
    delete config.id
    delete config.apiKeyConfigured
    testResult.value = await testCaptchaSolve(config, TEST_IMAGE_BASE64)
  } catch (e) {
    testResult.value = { success: false, errorMsg: e.message }
  } finally {
    testing.value = false
  }
}

async function onDelete (row) {
  try {
    await ElMessageBox.confirm(`确定删除打码方案「${row.configName}」吗？`, '删除确认', { type: 'warning' })
  } catch (e) {
    return
  }
  try {
    await deleteCaptchaConfig(row.id)
    ElMessage.success('方案已删除')
    emit('changed')
    await loadList()
  } catch (e) {
    ElMessage.error(e.message)
  }
}
</script>

<style scoped>
.captcha-layout {
  display: flex;
  gap: 16px;
}
.config-list {
  width: 320px;
  flex-shrink: 0;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 16px;
}
.config-list__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.config-form {
  flex: 1;
  min-width: 0;
}
.form-hint {
  margin: 0 12px 0 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
</style>
