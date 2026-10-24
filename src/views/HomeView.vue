<template>
  <!-- 模块二联调测试页：验证 Tauri IPC → Rust桥 → SpringBoot → MySQL 全链路 -->
  <el-card shadow="never">
    <template #header>
      <div class="card-header">
        <span>前后端联调测试</span>
        <el-tag :type="store.backendOk ? 'success' : 'danger'">
          {{ store.backendOk ? '后端已连接' : '后端未连接' }}
        </el-tag>
      </div>
    </template>

    <el-space wrap>
      <el-button type="primary" :loading="checking" @click="onCheck">检测后端连接</el-button>
      <el-button :loading="loading" @click="onLoadConfig">读取系统配置</el-button>
    </el-space>

    <el-divider />

    <!-- 高阶模式开关：验证 IPC 写通路（PUT 接口） -->
    <el-form inline>
      <el-form-item label="高阶模式">
        <el-switch
          v-model="store.advancedMode"
          :disabled="!store.backendOk"
          @change="onToggleAdvanced"
        />
      </el-form-item>
      <el-form-item>
        <el-text type="info" size="small">默认关闭，仅技术测试人员在设置中手动开启</el-text>
      </el-form-item>
    </el-form>

    <!-- 系统配置列表：验证 IPC 读通路 + MyBatis-Plus 数据库查询 -->
    <el-table :data="configs" v-loading="loading" size="small" border>
      <el-table-column prop="configKey" label="配置键" width="220" />
      <el-table-column prop="configValue" label="配置值" width="140" />
      <el-table-column prop="remark" label="说明" />
    </el-table>
  </el-card>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { get, put } from '../api'
import { useAppStore } from '../store/app'

const store = useAppStore()
const configs = ref([])
const checking = ref(false)
const loading = ref(false)

// 页面打开自动检测一次后端
onMounted(async () => {
  await onCheck()
  if (store.backendOk) {
    try {
      await store.loadAdvancedMode()
    } catch (e) {
      // 配置读取失败不影响页面展示
    }
  }
})

/** 检测后端连接状态 */
async function onCheck() {
  checking.value = true
  await store.checkBackend()
  checking.value = false
  if (store.backendOk) {
    ElMessage.success('后端连接正常')
  } else {
    ElMessage.error('无法连接后端，请先启动 SpringBoot 服务')
  }
}

/** 读取系统配置列表 */
async function onLoadConfig() {
  loading.value = true
  try {
    configs.value = await get('/api/sys-config/list')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    loading.value = false
  }
}

/** 切换高阶模式：写库持久化 */
async function onToggleAdvanced(val) {
  try {
    await put('/api/sys-config/advanced_mode_enabled', { value: val ? '1' : '0' })
    ElMessage.success(val ? '高阶模式已开启' : '高阶模式已关闭')
  } catch (e) {
    ElMessage.error(e.message)
    // 写失败时回滚开关显示
    store.advancedMode = !val
  }
}
</script>

<style scoped>
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
</style>
