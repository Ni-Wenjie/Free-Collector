import { get, post, put } from './index'

// 合规风控 API 封装（所有检查在本机完成，不上报数据）

/** 读取风控设置（robots 校验/限速开关/最小间隔/免责声明状态） */
export function getRiskSettings () {
  return get('/api/risk/settings')
}

/** 更新风控设置 */
export function updateRiskSettings (data) {
  return put('/api/risk/settings', data)
}

/** 单条 URL 合规预检：返回 { allowed, robotsUrl, matchedRule, reason, fetched, elapsedMs } */
export function checkUrlRisk (url) {
  return post('/api/risk/check-url', { url })
}

/** 查询免责声明确认状态 */
export function getDisclaimerStatus () {
  return get('/api/risk/disclaimer')
}

/** 确认免责声明 */
export function confirmDisclaimer () {
  return post('/api/risk/disclaimer/confirm')
}
