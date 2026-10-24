<template>
  <!-- 合规免责声明：首次启动强制确认（force 模式下不可关闭） -->
  <el-dialog
    :model-value="modelValue"
    title="合规使用声明"
    width="620px"
    :close-on-click-modal="false"
    :close-on-press-escape="!force"
    :show-close="!force"
    @update:model-value="onVisibleChange"
  >
    <el-alert
      type="warning"
      :closable="false"
      title="本软件为内部技术测试版，仅用于本地技术验证，请勿用于商业采集或分发。"
      style="margin-bottom: 12px"
    />

    <div class="disclaimer-body">
      <p>使用本软件前，请确认并遵守以下约定：</p>
      <ol>
        <li>遵守目标网站的《服务条款》、robots.txt 协议以及当地法律法规，不对目标网站造成访问压力或破坏。</li>
        <li>不采集个人信息、商业秘密、受版权保护的付费内容等受法律保护的数据。</li>
        <li>不将采集结果用于任何违法用途，不得转售、公开发布或二次分发。</li>
        <li>不用于突破目标网站的技术保护措施；验证码仅通过你自行配置的第三方打码平台处理。</li>
        <li>所有采集数据、任务配置默认仅保存在本机，软件不会自动上传到任何外部服务器。</li>
        <li>因使用本软件产生的一切法律责任与版权纠纷，由使用者自行承担。</li>
      </ol>
      <p class="disclaimer-tip">
        软件内置 robots.txt 校验与访问限速开关，可在「设置 - 合规风控」中查看与调整；<br />
        建议保持开启，降低对目标站点的干扰与自身合规风险。
      </p>
    </div>

    <el-checkbox v-model="agreed" style="margin-top: 8px">
      我已阅读并同意上述声明，承诺依法合规使用本软件
    </el-checkbox>

    <template #footer>
      <el-button type="primary" :disabled="!agreed" :loading="saving" @click="onConfirm">
        同意并继续
      </el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { confirmDisclaimer } from '../api/risk'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 强制模式：用户必须勾选确认才能关闭，常用于首次启动
  force: { type: Boolean, default: true }
})
const emit = defineEmits(['update:modelValue', 'confirmed'])

const agreed = ref(false)
const saving = ref(false)

// 每次打开都重置勾选状态
watch(() => props.modelValue, (val) => {
  if (val) agreed.value = false
})

/** 强制模式下不允许关闭弹窗（只能通过"同意并继续"） */
function onVisibleChange(val) {
  if (!val && props.force) return
  emit('update:modelValue', val)
}

async function onConfirm() {
  if (!agreed.value) {
    ElMessage.warning('请先勾选同意声明')
    return
  }
  saving.value = true
  try {
    await confirmDisclaimer()
    ElMessage.success('已记录确认，感谢合规使用')
    emit('update:modelValue', false)
    emit('confirmed')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.disclaimer-body {
  font-size: 13px;
  line-height: 1.9;
  color: var(--el-text-color-regular);
}
.disclaimer-body ol {
  padding-left: 20px;
  margin: 8px 0;
}
.disclaimer-tip {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  background: var(--el-fill-color-light);
  padding: 8px 10px;
  border-radius: 4px;
}
</style>
