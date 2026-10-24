<template>
  <!-- 网页预览容器：iframe 同源加载后端渲染的 HTML，支持鼠标点选元素 -->
  <div class="preview-frame-wrap" v-loading="loading" element-loading-text="正在加载页面...">
    <!--
      sandbox="allow-same-origin"：保留同源以便父窗口操作 contentDocument（高亮/点选），
      但不授予 allow-scripts。否则目标站点的内联脚本会继承主窗口源执行，
      可能跳转顶层窗口（表现为 about:srcdoc / ERR_INVALID_URL）甚至访问 Tauri IPC。
      注意：不能同时加 allow-scripts，否则沙箱失去隔离意义。
    -->
    <iframe
      ref="iframeRef"
      class="preview-iframe"
      sandbox="allow-same-origin"
      referrerpolicy="no-referrer"
      :srcdoc="html"
      @load="onIframeLoad"
    ></iframe>
  </div>
</template>

<script setup>
import { ref, watch, nextTick } from 'vue'
import { generateCssPath } from '../utils/cssPath'

const props = defineProps({
  // 后端 Playwright 渲染后的完整 HTML（已注入 <base> 标签）
  html: { type: String, default: '' }
})
// element-click: 用户点击了页面元素，payload 为元素信息
// ready: iframe 加载并注入脚本完成
const emit = defineEmits(['element-click', 'ready'])

const iframeRef = ref(null)
const loading = ref(false)

// 监听 html 变化：新页面加载中
watch(() => props.html, (val) => {
  if (val) {
    loading.value = true
  }
})

/** iframe 加载完成：注入高亮 CSS 和点击监听脚本 */
function onIframeLoad() {
  loading.value = false
  injectHighlightAndPicker()
  emit('ready')
}

/**
 * 向 iframe 注入：
 * 1) 高亮样式（悬停蓝色描边、选中绿色描边）
 * 2) 鼠标悬停高亮
 * 3) 点击拦截：阻止默认导航，计算 CSS 选择器并 emit 给父组件
 *
 * 由于 srcdoc 是同源的，可以直接操作 contentDocument
 */
function injectHighlightAndPicker() {
  const iframe = iframeRef.value
  if (!iframe) return
  const doc = iframe.contentDocument
  if (!doc) return

  // 注入高亮样式
  const style = doc.createElement('style')
  style.textContent = `
    .fc-hover-highlight {
      outline: 2px solid #409eff !important;
      outline-offset: -2px !important;
      cursor: crosshair !important;
    }
    .fc-selected-highlight {
      outline: 2px solid #67c23a !important;
      outline-offset: -2px !important;
      background: rgba(103, 194, 58, 0.12) !important;
    }
  `
  doc.head.appendChild(style)

  let lastHover = null
  let lastSelected = null

  // 悬停高亮
  doc.addEventListener('mouseover', (e) => {
    if (lastHover && lastHover !== e.target) {
      lastHover.classList.remove('fc-hover-highlight')
    }
    lastHover = e.target
    e.target.classList.add('fc-hover-highlight')
  })
  doc.addEventListener('mouseout', (e) => {
    e.target.classList.remove('fc-hover-highlight')
  })

  // 点击拦截 + 元素信息采集
  // 用捕获阶段（true）确保优先于页面自身的 click 处理器
  doc.addEventListener('click', (e) => {
    e.preventDefault()
    e.stopPropagation()
    e.stopImmediatePropagation()

    const el = e.target
    // 清除上一个选中高亮
    if (lastSelected) {
      lastSelected.classList.remove('fc-selected-highlight')
    }
    el.classList.add('fc-selected-highlight')
    lastSelected = el

    // 计算 CSS 选择器路径
    const cssPath = generateCssPath(el)

    // 收集元素关键信息，供父组件推断字段类型
    const info = {
      tagName: el.tagName,
      // 文本内容截断 300 字，避免超长
      text: (el.innerText || el.textContent || '').trim().slice(0, 300),
      href: el.getAttribute('href') || '',
      src: el.getAttribute('src') || '',
      className: typeof el.className === 'string' ? el.className : '',
      cssPath
    }
    emit('element-click', info)
  }, true)
}
</script>

<style scoped>
.preview-frame-wrap {
  width: 100%;
  height: 100%;
  background: #fff;
  border: 1px solid #e4e7ed;
  overflow: hidden;
}
.preview-iframe {
  width: 100%;
  height: 100%;
  border: none;
  background: #fff;
}
</style>
