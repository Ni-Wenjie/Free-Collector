import { request } from './index'

// 打码方案配置（高阶面板）

/** 方案列表（脱敏） */
export function listCaptchaConfigs () {
  return request('GET', '/api/captcha/config/list')
}

/** 保存方案（id 空新建，非空编辑） */
export function saveCaptchaConfig (data) {
  return request('POST', '/api/captcha/config', data)
}

/** 删除方案 */
export function deleteCaptchaConfig (id) {
  return request('DELETE', `/api/captcha/config/${id}`)
}

/**
 * 测试打码调用：config 为内存中的方案配置（不落库），imageBase64 为测试图片
 * 返回 CaptchaSolveResult（success / code / costMs / rawResponse 等）
 */
export function testCaptchaSolve (config, imageBase64) {
  return request('POST', '/api/captcha/test/solve', {
    config,
    request: { imageBase64 }
  })
}
