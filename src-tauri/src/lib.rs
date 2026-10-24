//! 自由采集器 Tauri 桥接层
//!
//! 职责：接收前端 Vue 通过 IPC 发来的请求，转发到本机 SpringBoot 后端，
//! 再把后端响应原样回传给前端。不做任何业务逻辑。
//!
//! 安全约束：转发目标写死为 http://127.0.0.1:17893，路径必须以 /api 开头。

use reqwest::Client;
use std::time::Duration;

/// 后端固定地址：仅本机回环（安全约束#2）
const BACKEND_BASE: &str = "http://127.0.0.1:17893";

/// 统一IPC请求入口
///
/// # 参数
/// - method: GET / POST / PUT / DELETE
/// - path:   后端接口路径，必须以 /api 开头，如 /api/health
/// - body:   可选，JSON 字符串（POST/PUT 时使用）
///
/// # 返回
/// 后端原始响应字符串（统一响应体 Result 的 JSON 文本），由前端 JS 解析
#[tauri::command]
async fn api_request(method: String, path: String, body: Option<String>) -> Result<String, String> {
    // 二次校验：仅允许 /api 前缀，防止前端把桥当成任意HTTP代理
    if !path.starts_with("/api") {
        return Err(format!("非法请求路径: {path}，仅允许 /api 前缀"));
    }

    let client = Client::builder()
        .timeout(Duration::from_secs(30))
        .build()
        .map_err(|e| format!("HTTP客户端初始化失败: {e}"))?;

    let url = format!("{BACKEND_BASE}{path}");
    let request = match method.to_uppercase().as_str() {
        "GET" => client.get(&url),
        "POST" => client.post(&url),
        "PUT" => client.put(&url),
        "DELETE" => client.delete(&url),
        other => return Err(format!("不支持的请求方法: {other}")),
    };

    // 有请求体时按 JSON 发送
    let request = match body {
        Some(b) => request.header("Content-Type", "application/json").body(b),
        None => request,
    };

    let response = request
        .send()
        .await
        .map_err(|e| format!("无法连接后端服务（请先启动后端）: {e}"))?;

    let status = response.status();
    let text = response
        .text()
        .await
        .map_err(|e| format!("读取后端响应失败: {e}"))?;

    // HTTP层错误（如后端5xx）也带响应体回传，方便前端展示
    if !status.is_success() {
        return Err(format!("后端返回HTTP {status}: {text}"));
    }
    Ok(text)
}

/// 后端健康检查快捷命令
#[tauri::command]
async fn backend_health() -> Result<String, String> {
    api_request("GET".to_string(), "/api/health".to_string(), None).await
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![api_request, backend_health])
        .run(tauri::generate_context!())
        .expect("Tauri 应用启动失败");
}
