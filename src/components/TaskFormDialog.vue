<template>
  <!-- 任务新建/编辑对话框 -->
  <el-dialog
    v-model="visible"
    :title="form.id ? '编辑任务' : '新建任务'"
    width="760px"
    append-to-body
    :close-on-click-modal="false"
  >
    <el-form label-width="90px" size="small">
      <el-form-item label="任务名称" required>
        <el-input v-model="form.taskName" placeholder="例如：某某网站商品列表" maxlength="128" />
      </el-form-item>
      <el-form-item label="目标网址" required>
        <div style="width: 100%">
          <div class="url-row">
            <el-input v-model="form.targetUrl" placeholder="https://example.com/list" />
            <el-button :loading="checkingRisk" @click="onCheckRisk">合规预检</el-button>
          </div>
          <!-- 合规预检结果：robots.txt 是否允许抓取 -->
          <el-alert
            v-if="riskResult"
            :type="riskResult.type"
            :title="riskResult.text"
            :closable="true"
            show-icon
            style="margin-top: 6px"
            @close="riskResult = null"
          />
        </div>
      </el-form-item>

      <el-row :gutter="12">
        <el-col :span="8">
          <el-form-item label="访问速度">
            <el-select v-model="form.speedLevel" style="width: 100%">
              <el-option label="慢速（推荐）" value="SLOW" />
              <el-option label="普通" value="NORMAL" />
              <el-option label="快速" value="FAST" />
            </el-select>
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="最大页数">
            <el-input-number v-model="form.maxPages" :min="1" :max="100" style="width: 100%" />
          </el-form-item>
        </el-col>
        <el-col :span="8">
          <el-form-item label="拟人浏览">
            <el-switch v-model="form.simulateHuman" />
          </el-form-item>
        </el-col>
      </el-row>

      <!-- 代理与 Cookie：编辑时留空表示保持原值 -->
      <el-form-item label="代理地址">
        <el-input
          v-model="proxyInput"
          :placeholder="proxyPlaceholder"
          clearable
        />
      </el-form-item>
      <el-form-item label="Cookie">
        <el-input
          v-model="cookieInput"
          type="textarea"
          :rows="2"
          :placeholder="cookiePlaceholder"
        />
      </el-form-item>

      <!-- 字段规则 -->
      <el-form-item label="采集字段">
        <div style="width: 100%">
          <FieldList :fields="form.fields" @delete="onDeleteField" />
          <el-button size="small" style="margin-top: 8px" @click="onAddField">+ 手动添加字段</el-button>
        </div>
      </el-form-item>

      <el-form-item label="备注">
        <el-input v-model="form.remark" placeholder="可选" maxlength="512" />
      </el-form-item>

      <!-- 高阶配置面板：仅高阶模式开启时显示，默认折叠（面向有爬虫经验的用户） -->
      <el-form-item v-if="appStore.advancedMode" label=" ">
        <el-collapse style="width: 100%">
          <el-collapse-item name="advanced">
            <template #title>
              <span>高阶配置</span>
              <el-tag size="small" type="warning" effect="plain" style="margin-left: 8px">专业</el-tag>
            </template>

            <el-form-item label="页面超时">
              <el-input-number v-model="advanced.pageTimeoutMs" :min="1000" :max="120000" :step="1000" />
              <span class="adv-tip">毫秒，默认 30000</span>
            </el-form-item>
            <el-form-item label="失败重试">
              <el-input-number v-model="advanced.retryTimes" :min="0" :max="5" />
              <span class="adv-tip">次（不含首次），默认 2</span>
            </el-form-item>
            <el-form-item label="等待策略">
              <el-select v-model="advanced.waitUntil" style="width: 220px">
                <el-option label="DOM 就绪（推荐）" value="DOMCONTENTLOADED" />
                <el-option label="页面完全加载" value="LOAD" />
                <el-option label="网络空闲" value="NETWORKIDLE" />
              </el-select>
            </el-form-item>
            <el-form-item label="等待元素">
              <el-input v-model="advanced.waitSelector" placeholder="可选，CSS 选择器，元素出现即认为页面就绪" />
            </el-form-item>
            <el-form-item label="请求头">
              <el-input
                v-model="advanced.extraHeadersText"
                type="textarea"
                :rows="2"
                placeholder='可选，JSON 对象，如 {"Referer":"https://example.com"}'
              />
            </el-form-item>
            <el-form-item label="注入脚本">
              <el-input
                v-model="advanced.injectScriptsText"
                type="textarea"
                :rows="2"
                placeholder="可选，每行一段 JS，页面加载后依次执行"
              />
            </el-form-item>
            <el-form-item label="覆盖 UA">
              <el-input v-model="advanced.userAgent" placeholder="可选，自定义 User-Agent；开启 UA 轮换时此项不生效" />
            </el-form-item>
            <el-form-item label="资源拦截">
              <el-checkbox v-model="advanced.blockImages">拦截图片加速</el-checkbox>
              <el-checkbox v-model="advanced.blockMedia">拦截音视频</el-checkbox>
              <el-checkbox v-model="advanced.ignoreHttpsErrors">忽略 HTTPS 证书错误</el-checkbox>
            </el-form-item>
            <el-form-item label="打码方案">
              <el-select v-model="form.captchaConfigId" clearable placeholder="不使用打码" style="width: 240px">
                <el-option
                  v-for="c in captchaConfigs"
                  :key="c.id"
                  :value="c.id"
                  :label="c.configName + (c.enabled ? '' : '（已停用）')"
                />
              </el-select>
              <el-button size="small" style="margin-left: 8px" @click="captchaDialogVisible = true">
                管理打码方案
              </el-button>
              <div class="adv-hint">页面出现验证码时，按所选方案转发第三方打码平台识别；密钥加密存储，请求由本机直接发起。</div>
            </el-form-item>
          </el-collapse-item>
        </el-collapse>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="onSave">保存</el-button>
    </template>
  </el-dialog>

  <!-- 打码方案管理（高阶面板入口），保存后刷新方案下拉 -->
  <CaptchaConfigDialog v-model="captchaDialogVisible" @changed="loadCaptchaConfigs" />
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import FieldList from './FieldList.vue'
import CaptchaConfigDialog from './CaptchaConfigDialog.vue'
import { getTask, saveTask } from '../api/task'
import { listCaptchaConfigs } from '../api/captcha'
import { checkUrlRisk } from '../api/risk'
import { useAppStore } from '../store/app'

const appStore = useAppStore()

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 传入任务ID表示编辑；传入 { targetUrl, fields } 表示预填新建
  taskId: { type: [Number, String], default: null },
  prefill: { type: Object, default: null }
})
const emit = defineEmits(['update:modelValue', 'saved'])

const visible = ref(false)
const saving = ref(false)
const proxyInput = ref('')
const cookieInput = ref('')
const proxyPlaceholder = ref('如 http://127.0.0.1:8888，含账号密码会自动加密存储')
const cookiePlaceholder = ref('格式：a=1; b=2（加密存储，不会明文保存）')
// 高阶面板：打码方案下拉数据 + 管理对话框开关
const captchaConfigs = ref([])
const captchaDialogVisible = ref(false)
// 合规预检：loading 与结果提示（结果含 type/text）
const checkingRisk = ref(false)
const riskResult = ref(null)

const form = ref(emptyForm())
const advanced = ref(emptyAdvanced())

function emptyForm() {
  return {
    id: null,
    taskName: '',
    targetUrl: '',
    speedLevel: 'SLOW',
    simulateHuman: false,
    rotateUa: false,
    maxPages: 5,
    remark: '',
    fields: [],
    proxyConfigured: false,
    cookieConfigured: false,
    captchaConfigId: null
  }
}

/** 高阶配置默认值：与后端 AdvancedOptions 默认值保持一致 */
function emptyAdvanced() {
  return {
    pageTimeoutMs: 30000,
    retryTimes: 2,
    waitUntil: 'DOMCONTENTLOADED',
    waitSelector: '',
    extraHeadersText: '',
    injectScriptsText: '',
    userAgent: '',
    ignoreHttpsErrors: false,
    blockImages: false,
    blockMedia: false
  }
}

/** 加载打码方案下拉（高阶面板用），失败仅提示不阻塞表单 */
async function loadCaptchaConfigs() {
  try {
    captchaConfigs.value = await listCaptchaConfigs()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

watch(() => props.modelValue, (val) => {
  visible.value = val
  if (val) {
    loadData()
  }
})
watch(visible, (val) => emit('update:modelValue', val))

/** 打开时加载数据：编辑读取详情，新建使用预填 */
async function loadData() {
  proxyInput.value = ''
  cookieInput.value = ''
  riskResult.value = null
  form.value = emptyForm()
  advanced.value = emptyAdvanced()
  // 高阶模式下预载打码方案下拉
  if (appStore.advancedMode) {
    loadCaptchaConfigs()
  }

  if (props.taskId) {
    try {
      const detail = await getTask(props.taskId)
      form.value = {
        id: detail.id,
        taskName: detail.taskName,
        targetUrl: detail.targetUrl,
        speedLevel: detail.speedLevel,
        simulateHuman: detail.simulateHuman,
        rotateUa: detail.rotateUa,
        maxPages: detail.maxPages || 5,
        remark: detail.remark || '',
        fields: (detail.fields || []).map(f => ({ ...f })),
        proxyConfigured: detail.proxyConfigured,
        cookieConfigured: detail.cookieConfigured,
        captchaConfigId: detail.captchaConfigId ?? null
      }
      // 回填高阶配置（advancedConfig 为后端返回的高阶键 Map，不含 maxPages）
      const ac = detail.advancedConfig || {}
      advanced.value = {
        pageTimeoutMs: ac.pageTimeoutMs ?? 30000,
        retryTimes: ac.retryTimes ?? 2,
        waitUntil: ac.waitUntil || 'DOMCONTENTLOADED',
        waitSelector: ac.waitSelector || '',
        extraHeadersText: ac.extraHeaders ? JSON.stringify(ac.extraHeaders, null, 2) : '',
        injectScriptsText: Array.isArray(ac.injectScripts) ? ac.injectScripts.join('\n') : '',
        userAgent: ac.userAgent || '',
        ignoreHttpsErrors: !!ac.ignoreHttpsErrors,
        blockImages: !!ac.blockImages,
        blockMedia: !!ac.blockMedia
      }
      proxyPlaceholder.value = detail.proxyConfigured
        ? '已配置（留空表示不修改，输入新地址则覆盖）'
        : '如 http://127.0.0.1:8888，含账号密码会自动加密存储'
      cookiePlaceholder.value = detail.cookieConfigured
        ? '已配置（留空表示不修改，输入新内容则覆盖）'
        : '格式：a=1; b=2（加密存储，不会明文保存）'
    } catch (e) {
      ElMessage.error(e.message)
      visible.value = false
      return
    }
  } else if (props.prefill) {
    form.value.targetUrl = props.prefill.targetUrl || ''
    form.value.fields = (props.prefill.fields || []).map(f => ({ ...f }))
    form.value.taskName = props.prefill.taskName || ''
    // 可视化采集页填写的登录 Cookie 直接带入任务，保存时由后端加密
    cookieInput.value = props.prefill.cookie || ''
    // 懒加载站点（如淘宝）的等待元素一并带入，任务执行时同样等待商品卡出现
    advanced.value.waitSelector = props.prefill.waitSelector || ''
  }
}

/**
 * 合规预检：调用后端 robots.txt 校验当前目标网址
 * 结果按类型着色：允许=success，无法读取=warning，禁止=error
 */
async function onCheckRisk() {
  const url = form.value.targetUrl.trim()
  if (!url) {
    ElMessage.warning('请先填写目标网址')
    return
  }
  checkingRisk.value = true
  riskResult.value = null
  try {
    const res = await checkUrlRisk(url)
    const hit = res.matchedRule ? `（命中规则 ${res.matchedRule}）` : ''
    if (!res.allowed) {
      riskResult.value = { type: 'error', text: '禁止抓取：' + res.reason + hit }
    } else if (res.fetched) {
      riskResult.value = { type: 'success', text: '允许抓取：' + res.reason + hit }
    } else {
      // 站点无 robots.txt 或读取失败，按放行处理但提示用户自行确认
      riskResult.value = { type: 'warning', text: res.reason }
    }
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    checkingRisk.value = false
  }
}

function onAddField() {
  form.value.fields.push({
    fieldName: '字段' + (form.value.fields.length + 1),
    fieldType: 'TEXT',
    locatorType: 'CSS',
    locatorExpression: '',
    extractAttr: 'text'
  })
}

function onDeleteField(index) {
  form.value.fields.splice(index, 1)
}

/** 保存任务 */
async function onSave() {
  if (!form.value.taskName.trim()) {
    ElMessage.warning('请填写任务名称')
    return
  }
  if (!form.value.targetUrl.trim()) {
    ElMessage.warning('请填写目标网址')
    return
  }
  if (form.value.fields.length === 0) {
    ElMessage.warning('请至少添加一个采集字段')
    return
  }
  const invalid = form.value.fields.find(f => !f.locatorExpression || !f.locatorExpression.trim())
  if (invalid) {
    ElMessage.warning(`字段【${invalid.fieldName}】还没有定位表达式`)
    return
  }

  const payload = {
    id: form.value.id,
    taskName: form.value.taskName.trim(),
    targetUrl: form.value.targetUrl.trim(),
    speedLevel: form.value.speedLevel,
    simulateHuman: form.value.simulateHuman,
    rotateUa: form.value.rotateUa,
    maxPages: form.value.maxPages,
    remark: form.value.remark,
    fields: form.value.fields.map((f, index) => ({
      fieldName: f.fieldName,
      fieldType: f.fieldType,
      locatorType: f.locatorType || 'CSS',
      locatorExpression: f.locatorExpression,
      extractAttr: f.extractAttr,
      sortOrder: index
    }))
  }
  // 高阶配置：高阶模式开启时组装提交；关闭时仅在已填写等待元素时提交该项
  if (appStore.advancedMode || advanced.value.waitSelector.trim()) {
    const adv = {}
    if (advanced.value.pageTimeoutMs) adv.pageTimeoutMs = advanced.value.pageTimeoutMs
    if (advanced.value.retryTimes !== null && advanced.value.retryTimes !== undefined) {
      adv.retryTimes = advanced.value.retryTimes
    }
    if (advanced.value.waitUntil) adv.waitUntil = advanced.value.waitUntil
    if (advanced.value.waitSelector.trim()) adv.waitSelector = advanced.value.waitSelector.trim()
    if (advanced.value.extraHeadersText.trim()) {
      // 自定义请求头必须是合法 JSON 对象
      try {
        const headers = JSON.parse(advanced.value.extraHeadersText)
        if (!headers || typeof headers !== 'object' || Array.isArray(headers)) {
          throw new Error('not a json object')
        }
        adv.extraHeaders = headers
      } catch (e) {
        ElMessage.warning('高阶配置：自定义请求头必须是合法的 JSON 对象')
        return
      }
    }
    const scripts = advanced.value.injectScriptsText
      .split('\n').map(s => s.trim()).filter(Boolean)
    if (scripts.length) adv.injectScripts = scripts
    if (advanced.value.userAgent.trim()) adv.userAgent = advanced.value.userAgent.trim()
    if (advanced.value.ignoreHttpsErrors) adv.ignoreHttpsErrors = true
    if (advanced.value.blockImages) adv.blockImages = true
    if (advanced.value.blockMedia) adv.blockMedia = true
    payload.advancedConfig = adv
    payload.captchaConfigId = form.value.captchaConfigId || null
  }

  // 留空表示保持原值，不提交该字段
  if (proxyInput.value.trim()) payload.proxyUrl = proxyInput.value.trim()
  if (cookieInput.value.trim()) payload.cookie = cookieInput.value.trim()
  if (!form.value.id) {
    // 新建时允许为空，后端按未配置处理
    payload.proxyUrl = proxyInput.value.trim() || null
    payload.cookie = cookieInput.value.trim() || null
  }

  saving.value = true
  try {
    const id = await saveTask(payload)
    ElMessage.success('任务已保存')
    visible.value = false
    emit('saved', id)
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
/* 目标网址行：输入框 + 合规预检按钮 */
.url-row {
  display: flex;
  gap: 8px;
}
/* 高阶面板辅助文字 */
.adv-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.adv-hint {
  width: 100%;
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
  margin-top: 4px;
}
</style>
