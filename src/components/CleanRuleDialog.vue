<template>
  <!-- 清洗规则配置对话框：规则编辑 + 即时预览 -->
  <el-dialog
    :model-value="modelValue"
    title="数据清洗规则"
    width="860px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @open="onOpen"
  >
    <div class="clean-layout">
      <!-- 左侧：规则编辑区 -->
      <div class="rule-editor">
        <div class="rule-editor__header">
          <span class="rule-editor__title">规则列表（按顺序执行，不修改原始数据）</span>
          <el-button size="small" type="primary" plain @click="addRule">添加规则</el-button>
        </div>

        <el-empty v-if="rules.length === 0" description="暂无规则，导出时将输出原始数据" :image-size="60" />

        <div v-for="(rule, index) in rules" :key="index" class="rule-item">
          <div class="rule-item__row">
            <el-select v-model="rule.ruleType" size="small" style="width: 130px" @change="onTypeChange(rule)">
              <el-option label="整体去重" value="DEDUP" />
              <el-option label="剔除空行" value="REMOVE_EMPTY" />
              <el-option label="文本截取" value="SUBSTRING" />
              <el-option label="文本替换" value="REPLACE" />
              <el-option label="正则替换" value="REGEX" />
            </el-select>

            <!-- 字段级规则需要选择目标字段；REGEX 属高阶能力，仅高阶模式展示 -->
            <el-select
              v-if="needField(rule)"
              v-model="rule.fieldName"
              size="small"
              placeholder="目标字段"
              style="width: 130px"
              filterable
              allow-create
            >
              <el-option v-for="f in fieldNames" :key="f" :label="f" :value="f" />
            </el-select>

            <el-button size="small" type="danger" text @click="rules.splice(index, 1)">删除</el-button>
          </div>

          <!-- 规则参数区：按类型展示不同输入 -->
          <div v-if="rule.ruleType === 'SUBSTRING'" class="rule-item__param">
            <el-input-number v-model="rule.substringStart" :min="0" size="small" placeholder="起始" style="width: 100px" />
            <span class="param-sep">至</span>
            <el-input-number v-model="rule.substringEnd" :min="0" size="small" placeholder="结束(空=到结尾)" style="width: 140px" />
          </div>
          <div v-else-if="rule.ruleType === 'REPLACE'" class="rule-item__param">
            <el-input v-model="rule.replaceFrom" size="small" placeholder="查找内容" style="width: 180px" />
            <span class="param-sep">→</span>
            <el-input v-model="rule.replaceTo" size="small" placeholder="替换为(可空)" style="width: 180px" />
          </div>
          <div v-else-if="rule.ruleType === 'REGEX'" class="rule-item__param">
            <el-input v-model="rule.regexPattern" size="small" placeholder="正则表达式，如 (\d+)" style="width: 200px" />
            <span class="param-sep">→</span>
            <el-input v-model="rule.regexReplace" size="small" placeholder="替换为，如 $1" style="width: 140px" />
          </div>
        </div>

        <div class="rule-editor__footer">
          <el-button size="small" @click="doPreview" :loading="previewing">预览效果</el-button>
          <el-button size="small" type="primary" @click="doSave" :loading="saving">保存规则</el-button>
        </div>
      </div>

      <!-- 右侧：预览区 -->
      <div class="rule-preview">
        <div class="rule-preview__title">
          预览
          <template v-if="preview">
          （共 {{ preview.totalRows }} 行 → 清洗后 {{ preview.cleanedRows }} 行<template v-if="preview.removedRows">，剔除 {{ preview.removedRows }} 行</template>）
          </template>
        </div>
        <template v-if="preview">
          <el-alert
            v-for="(w, i) in preview.warnings"
            :key="i"
            :title="w"
            type="warning"
            :closable="false"
            style="margin-bottom: 6px"
          />
          <div class="preview-label">清洗前</div>
          <el-table :data="preview.before" size="small" border max-height="180">
            <el-table-column v-for="col in previewColumns" :key="'b' + col" :prop="col" :label="col" show-overflow-tooltip />
          </el-table>
          <div class="preview-label">清洗后</div>
          <el-table :data="preview.after" size="small" border max-height="180">
            <el-table-column v-for="col in previewColumns" :key="'a' + col" :prop="col" :label="col" show-overflow-tooltip />
          </el-table>
        </template>
        <el-empty v-else description="点击「预览效果」查看清洗对比" :image-size="60" />
      </div>
    </div>
  </el-dialog>
</template>

<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { listCleanRules, saveCleanRules, previewClean } from '../api/clean'

const props = defineProps({
  modelValue: Boolean,
  taskId: { type: Number, required: true },
  /** 任务的字段名列表（用于字段选择下拉） */
  fieldNames: { type: Array, default: () => [] }
})
const emit = defineEmits(['update:modelValue'])

const rules = ref([])       // 编辑中的规则（UI态，参数拆成独立字段便于编辑）
const saving = ref(false)
const previewing = ref(false)
const preview = ref(null)

/** 字段级规则需要选择目标字段 */
const needField = (rule) => ['SUBSTRING', 'REPLACE', 'REGEX'].includes(rule.ruleType)

const previewColumns = computed(() => {
  const rows = (preview.value && (preview.value.after.length ? preview.value.after : preview.value.before)) || []
  const keys = new Set()
  rows.forEach(r => Object.keys(r).forEach(k => keys.add(k)))
  return [...keys]
})

/** 对话框打开时加载已保存规则 */
async function onOpen () {
  preview.value = null
  try {
    const saved = await listCleanRules(props.taskId)
    rules.value = (saved || []).map(entityToUi)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

function addRule () {
  rules.value.push({ ruleType: 'REPLACE', fieldName: props.fieldNames[0] || '', replaceFrom: '', replaceTo: '' })
}

/** 切换规则类型时重置参数，避免残留旧参数 */
function onTypeChange (rule) {
  rule.substringStart = 0
  rule.substringEnd = null
  rule.replaceFrom = ''
  rule.replaceTo = ''
  rule.regexPattern = ''
  rule.regexReplace = ''
}

/** 实体（后端存储格式）→ UI 编辑态 */
function entityToUi (entity) {
  const ui = { ruleType: entity.ruleType, fieldName: entity.fieldName || '' }
  let param = {}
  try { param = entity.ruleParam ? JSON.parse(entity.ruleParam) : {} } catch { param = {} }
  if (entity.ruleType === 'SUBSTRING') {
    ui.substringStart = param.start ?? 0
    ui.substringEnd = param.end ?? null
  } else if (entity.ruleType === 'REPLACE') {
    ui.replaceFrom = param.from ?? ''
    ui.replaceTo = param.to ?? ''
  } else if (entity.ruleType === 'REGEX') {
    ui.regexPattern = param.pattern ?? ''
    ui.regexReplace = param.replace ?? ''
  }
  return ui
}

/** UI 编辑态 → 后端 DTO */
function uiToDto (ui) {
  const dto = { ruleType: ui.ruleType, fieldName: needField(ui) ? ui.fieldName : null, ruleParam: null }
  if (ui.ruleType === 'SUBSTRING') {
    dto.ruleParam = JSON.stringify({ start: ui.substringStart ?? 0, end: ui.substringEnd ?? null })
  } else if (ui.ruleType === 'REPLACE') {
    dto.ruleParam = JSON.stringify({ from: ui.replaceFrom ?? '', to: ui.replaceTo ?? '' })
  } else if (ui.ruleType === 'REGEX') {
    dto.ruleParam = JSON.stringify({ pattern: ui.regexPattern ?? '', replace: ui.regexReplace ?? '' })
  }
  return dto
}

async function doPreview () {
  previewing.value = true
  try {
    preview.value = await previewClean(props.taskId, rules.value.map(uiToDto))
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    previewing.value = false
  }
}

async function doSave () {
  saving.value = true
  try {
    await saveCleanRules(props.taskId, rules.value.map(uiToDto))
    ElMessage.success('清洗规则已保存，导出时将自动应用')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.clean-layout {
  display: flex;
  gap: 16px;
}
.rule-editor {
  flex: 1;
  min-width: 0;
  border-right: 1px solid var(--el-border-color-lighter);
  padding-right: 16px;
}
.rule-editor__header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
}
.rule-editor__title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.rule-item {
  padding: 8px;
  margin-bottom: 8px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
}
.rule-item__row {
  display: flex;
  gap: 8px;
  align-items: center;
}
.rule-item__param {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-top: 8px;
}
.param-sep {
  color: var(--el-text-color-secondary);
}
.rule-editor__footer {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
.rule-preview {
  width: 380px;
  flex-shrink: 0;
}
.rule-preview__title {
  font-size: 13px;
  color: var(--el-text-color-secondary);
  margin-bottom: 8px;
}
.preview-label {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  margin: 10px 0 4px;
}
</style>
