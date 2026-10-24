import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// Tauri 对 Vite 的要求：固定端口、禁止自动清屏
export default defineConfig({
  plugins: [vue()],
  clearScreen: false,
  server: {
    port: 5173,
    strictPort: true,
    host: '127.0.0.1',
    // 关键：忽略 src-tauri 目录的文件监听
    // Rust 编译时链接器会锁定/改写 target 下的 dll/exe，Vite 监听到会 EBUSY 崩溃，
    // 导致 beforeDevCommand 非零退出、Tauri 启动中断
    watch: {
      ignored: ['**/src-tauri/**', '**/dist/**', '**/node_modules/**']
    }
  },
  // 打包产物输出到项目根 dist，供 tauri.conf.json frontendDist 使用
  build: {
    outDir: 'dist'
  }
})
