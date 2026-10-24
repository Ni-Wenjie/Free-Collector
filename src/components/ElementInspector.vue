<template>
  <!-- 元素确认弹窗：点击页面元素后展示元素信息，确认添加为采集字段 -->
  <el-dialog
    v-model="visible"
    title="确认添加采集字段"
    width="540px"
    append-to-body
    :close-on-click-modal="false"
  >
    <!-- 元素原始信息展示 -->
    <el-descriptions :column="1" border size="small">
      <el-descriptions-item label="标签">{{ info.tagName }}</el-descriptions-item>
      <el-descriptions-item label="文本内容" v-if="info.text">
        <div class="text-preview">{{ info.text }}</div>
      </el-descriptions-item>
      <el-descriptions-item label="链接地址" v-if="info.href">
        <a :href="info.href" target="_blank" class="link-preview">{{ info.href }}</a>
      </el-descriptions-item>
      <el-descriptions-item label="图片地址" v-if="info.src">
        <a :href="info.src" target="_blank" class="link-preview">{{ info.src }}</a>
      </el-descriptions-item>
      <el-descriptions-item label="CSS 选择器">
        <code class="css-path">{{ info.cssPath }}</code>
      </el-descriptions-item>
    </el-descriptions>

    <el-divider />

    <!-- 字段配置：可修改字段名/类型/属性 -->
    <el-form label-width="80px" size="small">
      <el-form-item label="字段名">
        <el-input v-model="field.fieldName" placeholder="给这个字段起个名字，如：商品标题" />
      </el-form-item>
      <el-form-item label="字段类型">
        <el-select v-model="field.fieldType" style="width: 100%">
          <el-option label="文本" value="TEXT" />
          <el-option label="链接" value="LINK" />
          <el-option label="图片" value="IMAGE" />
          <el-option label="列表" value="LIST" />
        </el-select>
      </el-form-item>
      <el-form-item label="提取属性">
        <el-select v-model="field.extractAttr" style="width: 100%">
          <el-option label="文本内容 (text)" value="text" />
          <el-option label="链接 (href)" value="href" />
          <el-option label="图片 (src)" value="src" />
          <el-option label="HTML (outerHTML)" value="outerHTML" />
        </el-select>
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" @click="onConfirm">添加字段</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  info: { type: Object, default: () => ({}) }
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const visible = ref(false)

// 当前编辑的字段
const field = ref({
  fieldName: '',
  fieldType: 'TEXT',
  locatorType: 'CSS',
  locatorExpression: '',
  extractAttr: 'text'
})

// 双向绑定 v-model
watch(() => props.modelValue, val => { visible.value = val })
watch(visible, val => emit('update:modelValue', val))

// 弹窗打开时，根据元素信息自动推断字段配置
watch(() => props.info, (info) => {
  if (!info || !info.cssPath) return
  field.value = {
    fieldName: suggestFieldName(info),
    fieldType: suggestFieldType(info),
    locatorType: 'CSS',
    locatorExpression: info.cssPath,
    extractAttr: suggestAttr(info)
  }
}, { immediate: true })

/** 根据标签推断字段名 */
function suggestFieldName(info) {
  if (info.tagName === 'IMG') return '图片'
  if (info.tagName === 'A') return '链接'
  if (info.text) return info.text.slice(0, 12)
  return '字段'
}

/** 根据标签推断字段类型 */
function suggestFieldType(info) {
  if (info.tagName === 'IMG') return 'IMAGE'
  if (info.tagName === 'A') return 'LINK'
  return 'TEXT'
}

/** 根据标签推断提取属性 */
function suggestAttr(info) {
  if (info.tagName === 'IMG') return 'src'
  if (info.tagName === 'A') return 'href'
  return 'text'
}

/** 确认添加 */
function onConfirm() {
  if (!field.value.fieldName.trim()) {
    return
  }
  emit('confirm', { ...field.value })
  visible.value = false
}
</script>

<style scoped>
.text-preview {
  max-height: 80px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
}
.link-preview {
  word-break: break-all;
  color: #409eff;
  text-decoration: none;
}
.css-path {
  word-break: break-all;
  color: #e6a23c;
}
</style>
