import { defineStore } from 'pinia'
import { get } from '../api'

/**
 * 全局应用状态
 */
export const useAppStore = defineStore('app', {
  state: () => ({
    backendOk: false,    // 后端连接状态：true已连接
    advancedMode: false  // 高阶模式开关：默认关闭（对应 sys_config.advanced_mode_enabled）
  }),
  actions: {
    /** 检测后端是否可用 */
    async checkBackend() {
      try {
        await get('/api/health')
        this.backendOk = true
      } catch (e) {
        this.backendOk = false
      }
    },
    /** 从后端读取高阶模式开关 */
    async loadAdvancedMode() {
      const cfg = await get('/api/sys-config/advanced_mode_enabled')
      this.advancedMode = cfg.configValue === '1'
    }
  }
})
