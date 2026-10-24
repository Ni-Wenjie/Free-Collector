import { request } from './index'

// 系统配置

/** 按 key 读取配置，不存在时返回 null（不抛错，供开关类配置缺省处理） */
export async function getSysConfig (key) {
  try {
    return await request('GET', `/api/sys-config/${key}`)
  } catch (e) {
    return null
  }
}

/** 按 key 写入配置（后端 upsert） */
export function setSysConfig (key, value, remark = '') {
  return request('PUT', `/api/sys-config/${key}`, { value, remark })
}
