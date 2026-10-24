<template>
  <el-container class="layout">
    <el-header class="header" height="56px">
      <span class="title">自由采集器</span>
      <el-tag size="small" type="warning" effect="plain">内部技术测试版</el-tag>
      <el-menu
        :default-active="activeMenu"
        mode="horizontal"
        class="nav-menu"
        @select="onMenuSelect"
      >
        <el-menu-item index="home">首页</el-menu-item>
        <el-menu-item index="capture">可视化采集</el-menu-item>
        <el-menu-item index="task">任务管理</el-menu-item>
      </el-menu>
      <!-- 设置入口：高阶模式开关 -->
      <el-tooltip content="设置" placement="bottom">
        <el-button circle text @click="settingsVisible = true">
          <span style="font-size: 18px">⚙</span>
        </el-button>
      </el-tooltip>
    </el-header>
    <el-main class="main">
      <HomeView v-if="activeMenu === 'home'" />
      <CaptureView v-else-if="activeMenu === 'capture'" />
      <TaskView v-else />
    </el-main>

    <!-- 设置对话框 -->
    <el-dialog v-model="settingsVisible" title="设置" width="520px">
      <div class="setting-item">
        <div class="setting-item__text">
          <div class="setting-item__name">高阶模式</div>
          <div class="setting-item__desc">
            开启后任务编辑将显示高阶配置面板（页面超时、重试、注入脚本、打码方案等专业能力），
            面向有爬虫经验的用户；默认关闭，普通用户无需关注。
          </div>
        </div>
        <el-switch
          :model-value="appStore.advancedMode"
          :loading="savingAdvanced"
          @change="onToggleAdvanced"
        />
      </div>

      <el-divider content-position="left">合规风控</el-divider>

      <div class="setting-item">
        <div class="setting-item__text">
          <div class="setting-item__name">robots.txt 校验</div>
          <div class="setting-item__desc">
            采集前检查目标站点是否允许抓取，被禁止时阻止任务启动。建议保持开启。
          </div>
        </div>
        <el-switch
          :model-value="riskSettings.robotsCheckEnabled"
          :loading="savingRisk"
          @change="val => onRiskSwitch('robotsCheckEnabled', val)"
        />
      </div>

      <div class="setting-item">
        <div class="setting-item__text">
          <div class="setting-item__name">访问限速</div>
          <div class="setting-item__desc">
            同一站点两次页面请求之间保持最小间隔，降低对目标站点的访问压力。
          </div>
        </div>
        <el-switch
          :model-value="riskSettings.rateLimitEnabled"
          :loading="savingRisk"
          @change="val => onRiskSwitch('rateLimitEnabled', val)"
        />
      </div>

      <div class="setting-item">
        <div class="setting-item__text">
          <div class="setting-item__name">最小请求间隔</div>
          <div class="setting-item__desc">
            单位毫秒（200 ~ 60000），仅访问限速开启时生效。
          </div>
        </div>
        <el-input-number
          :model-value="riskSettings.minIntervalMs"
          :min="200"
          :max="60000"
          :step="500"
          size="small"
          @change="onRiskIntervalChange"
        />
      </div>

      <div class="setting-item">
        <div class="setting-item__text">
          <div class="setting-item__name">合规使用声明</div>
          <div class="setting-item__desc">
            {{ riskSettings.disclaimerConfirmed ? '已确认，可随时查看' : '尚未确认，请先阅读并同意' }}
          </div>
        </div>
        <el-button size="small" @click="openDisclaimer">查看</el-button>
      </div>
    </el-dialog>

    <!-- 首次启动强制确认合规免责声明 -->
    <RiskDisclaimerDialog
      v-model="disclaimerVisible"
      :force="disclaimerForce"
      @confirmed="onDisclaimerConfirmed"
    />
  </el-container>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { ElMessage } from 'element-plus'
import HomeView from './views/HomeView.vue'
import CaptureView from './views/CaptureView.vue'
import TaskView from './views/TaskView.vue'
import RiskDisclaimerDialog from './components/RiskDisclaimerDialog.vue'
import { useAppStore } from './store/app'
import { setSysConfig } from './api/system'
import { getRiskSettings, updateRiskSettings } from './api/risk'

const appStore = useAppStore()
const activeMenu = ref('home')
const settingsVisible = ref(false)
const savingAdvanced = ref(false)

// 合规风控设置（设置面板展示）
const riskSettings = ref({
  robotsCheckEnabled: true,
  rateLimitEnabled: true,
  minIntervalMs: 1500,
  disclaimerConfirmed: false
})
const savingRisk = ref(false)
const disclaimerVisible = ref(false)
// true 表示首次启动强制确认（不可关闭）；从设置里"查看"时为 false
const disclaimerForce = ref(true)

function onMenuSelect(index) {
  activeMenu.value = index
}

/** 启动时读取高阶模式开关与合规设置 */
onMounted(async () => {
  try {
    await appStore.loadAdvancedMode()
  } catch (e) {
    // 配置不存在时按关闭处理
    appStore.advancedMode = false
  }
  await loadRisk()
  // 首次启动：未确认免责声明时强制弹出（必须勾选同意才能进入使用）
  if (!riskSettings.value.disclaimerConfirmed) {
    disclaimerForce.value = true
    disclaimerVisible.value = true
  }
})

/** 读取合规风控设置 */
async function loadRisk() {
  try {
    riskSettings.value = await getRiskSettings()
  } catch (e) {
    // 后端未就绪时保持默认值，不阻塞界面
  }
}

// 打开设置面板时刷新风控状态
watch(settingsVisible, (val) => {
  if (val) loadRisk()
})

/** 切换风控开关 */
async function onRiskSwitch(field, val) {
  savingRisk.value = true
  try {
    riskSettings.value = await updateRiskSettings({ [field]: val })
    ElMessage.success('合规设置已保存')
  } catch (e) {
    ElMessage.error(e.message)
    await loadRisk()
  } finally {
    savingRisk.value = false
  }
}

/** 修改最小请求间隔 */
async function onRiskIntervalChange(val) {
  savingRisk.value = true
  try {
    riskSettings.value = await updateRiskSettings({ minIntervalMs: val })
    ElMessage.success('最小请求间隔已保存')
  } catch (e) {
    ElMessage.error(e.message)
    await loadRisk()
  } finally {
    savingRisk.value = false
  }
}

/** 从设置中查看免责声明（非强制模式） */
function openDisclaimer() {
  disclaimerForce.value = false
  disclaimerVisible.value = true
}

/** 免责声明确认后刷新状态 */
async function onDisclaimerConfirmed() {
  await loadRisk()
}

/** 切换高阶模式并持久化到 sys_config */
async function onToggleAdvanced(val) {
  savingAdvanced.value = true
  try {
    await setSysConfig('advanced_mode_enabled', val ? '1' : '0', '高阶模式开关：1开启 0关闭')
    appStore.advancedMode = val
    ElMessage.success(val ? '高阶模式已开启，任务编辑中将显示高阶配置面板' : '高阶模式已关闭')
  } catch (e) {
    ElMessage.error(e.message)
  } finally {
    savingAdvanced.value = false
  }
}
</script>

<style>
/* 全局基础样式 */
html, body, #app {
  margin: 0;
  height: 100%;
  background: #f5f7fa;
}
.layout {
  height: 100%;
}
.header {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #fff;
  border-bottom: 1px solid #e4e7ed;
}
.header .title {
  font-size: 18px;
  font-weight: 600;
}
.nav-menu {
  flex: 1;
  border-bottom: none;
}
.main {
  padding: 20px;
}
.setting-item {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
}
.setting-item__name {
  font-weight: 600;
  margin-bottom: 4px;
}
.setting-item__desc {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}
</style>
