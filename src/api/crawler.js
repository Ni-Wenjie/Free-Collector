// 爬虫相关 API 封装（预览 + 单页采集测试）
import { post } from './index'

/**
 * 渲染页面：后端用 Playwright 打开目标 URL，返回渲染后完整 HTML
 * 前端用 iframe srcdoc 同源加载，从而可以注入选择脚本实现元素点选
 *
 * @param {object} data { url, waitSelector?, waitMs?, simulateHuman?, rotateUa? }
 * @returns {Promise<{html, title, finalUrl, elapsedMs}>}
 */
export function renderPreview(data) {
  return post('/api/crawler/preview/render', data)
}

/**
 * 单页采集测试（模块三引擎）
 *
 * @param {object} data { url, fields, speedLevel, simulateHuman, rotateUa }
 * @returns {Promise<{pageUrl, title, fieldValues, rows, pagination, warnings, elapsedMs}>}
 */
export function testExtract(data) {
  return post('/api/crawler/test/extract', data)
}
