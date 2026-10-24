// Tauri IPC 通信封装：前端不直接发 HTTP，统一走 Rust 桥
import { invoke } from '@tauri-apps/api/core'

/**
 * 统一请求入口
 * @param {string} method 请求方法：GET / POST / PUT / DELETE
 * @param {string} path   后端路径，必须以 /api 开头（Rust 侧二次校验）
 * @param {object|null} body 请求体对象，自动序列化为 JSON
 * @returns {Promise<any>} 统一响应体 Result 中的 data 字段
 * @throws {Error} 业务码非 0 或桥/网络异常时抛出，message 可直接弹窗展示
 */
export async function request(method, path, body = null) {
  const raw = await invoke('api_request', {
    method,
    path,
    body: body === null ? null : JSON.stringify(body)
  })
  // Rust 返回的是后端原始 JSON 字符串，统一在此解析
  const result = JSON.parse(raw)
  if (result.code !== 0) {
    throw new Error(result.message || '后端业务处理失败')
  }
  return result.data
}

// 常用方法便捷封装
export const get = (path) => request('GET', path)
export const post = (path, body) => request('POST', path, body)
export const put = (path, body) => request('PUT', path, body)
export const del = (path) => request('DELETE', path)
