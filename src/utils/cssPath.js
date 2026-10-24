// CSS 选择器路径生成工具
// 在 iframe 预览中点击元素后，生成稳定可复用的 CSS 选择器作为字段定位表达式。
// 优先级：id > 有意义的 class > tag:nth-of-type(n)，逐层向上拼接到 html。

/**
 * 生成元素的稳定 CSS 选择器路径
 * @param {Element} el DOM 元素
 * @returns {string} CSS 选择器，如 #main > div.content > p:nth-of-type(2)
 */
export function generateCssPath(el) {
  if (!(el instanceof Element)) return ''
  const path = []
  let node = el
  while (node && node.nodeType === 1) {
    let selector = node.tagName.toLowerCase()

    // 优先用 ID（全站唯一，路径到此为止）
    if (node.id) {
      selector = '#' + cssEscape(node.id)
      path.unshift(selector)
      break
    }

    // 其次用第一个有意义的 class
    const cls = getMeaningfulClass(node)
    if (cls) {
      selector += '.' + cssEscape(cls)
    }

    // 同级同标签有多个时，用 nth-of-type 精确定位
    const parent = node.parentNode
    if (parent) {
      const siblings = Array.from(parent.children).filter(c => c.tagName === node.tagName)
      if (siblings.length > 1) {
        const idx = siblings.indexOf(node) + 1
        selector += `:nth-of-type(${idx})`
      }
    }

    path.unshift(selector)
    node = node.parentElement
  }
  return path.join(' > ')
}

/**
 * 取元素第一个"有意义"的 class 名
 * 跳过 active/selected/hover 等纯状态 class，以及以数字开头的 class
 */
function getMeaningfulClass(el) {
  if (!el.className || typeof el.className !== 'string') return ''
  const classes = el.className.trim().split(/\s+/)
  const skip = new Set(['active', 'selected', 'hover', 'focus', 'current', 'open', 'show', 'hidden', 'visible'])
  for (const c of classes) {
    if (!c) continue
    if (skip.has(c.toLowerCase())) continue
    if (/^\d/.test(c)) continue   // 纯数字开头的 class 不稳定
    return c
  }
  return ''
}

/**
 * CSS 标识符转义：将特殊字符转义为 \字符
 * 处理 id/class 中含有的 . # : [ ] 等字符
 */
function cssEscape(str) {
  if (window.CSS && typeof window.CSS.escape === 'function') {
    return window.CSS.escape(str)
  }
  // 兜底：手动转义非字母数字下划线连字符
  return str.replace(/[^a-zA-Z0-9_-]/g, ch => '\\' + ch)
}
