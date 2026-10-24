// 任务调度 API 封装
import { get, post, del } from './index'

/** 任务分页列表 */
export function listTasks(params = {}) {
  const query = new URLSearchParams()
  query.set('page', params.page || 1)
  query.set('size', params.size || 10)
  if (params.keyword) query.set('keyword', params.keyword)
  if (params.status) query.set('status', params.status)
  return get(`/api/task/list?${query.toString()}`)
}

/** 任务详情（含字段） */
export function getTask(id) {
  return get(`/api/task/${id}`)
}

/** 新建/编辑任务 */
export function saveTask(data) {
  return post('/api/task', data)
}

/** 删除任务 */
export function deleteTask(id) {
  return del(`/api/task/${id}`)
}

/** 启动任务 */
export function startTask(id) {
  return post(`/api/task/${id}/start`)
}

/** 暂停任务 */
export function pauseTask(id) {
  return post(`/api/task/${id}/pause`)
}

/** 继续任务（断点续爬） */
export function resumeTask(id) {
  return post(`/api/task/${id}/resume`)
}

/** 终止任务 */
export function stopTask(id) {
  return post(`/api/task/${id}/stop`)
}

/** 运行状态 */
export function getTaskStatus(id) {
  return get(`/api/task/${id}/status`)
}

/** 采集结果分页 */
export function getTaskResults(id, page = 1, size = 20) {
  return get(`/api/task/${id}/results?page=${page}&size=${size}`)
}

/** 把任务保存为模板 */
export function saveTaskAsTemplate(id, data) {
  return post(`/api/task/${id}/save-template`, data)
}

/** 模板列表 */
export function listTemplates() {
  return get('/api/template/list')
}

/** 模板导入为新任务 */
export function importTemplate(id) {
  return post(`/api/template/${id}/import`)
}

/** 导出模板内容 */
export function exportTemplate(id) {
  return get(`/api/template/${id}/export`)
}

/** 删除模板 */
export function deleteTemplate(id) {
  return del(`/api/template/${id}`)
}
