import { request } from './index'

// 数据清洗

/** 查询任务的清洗规则 */
export function listCleanRules (taskId) {
  return request('GET', `/api/clean/rules?taskId=${taskId}`)
}

/** 整体保存清洗规则（空数组表示清空） */
export function saveCleanRules (taskId, rules) {
  return request('POST', `/api/clean/rules?taskId=${taskId}`, rules)
}

/** 清洗预览：rules 传 null 则用已保存的规则 */
export function previewClean (taskId, rules) {
  return request('POST', `/api/clean/preview?taskId=${taskId}`, rules)
}

/** 导出任务结果：format = csv/json/xlsx，applyClean 默认 true */
export function exportTask (taskId, format, applyClean = true) {
  return request('GET', `/api/export/task/${taskId}?format=${format}&applyClean=${applyClean}`)
}
