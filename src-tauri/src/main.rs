// 预编译产物模式（打包成exe时不弹出控制台窗口）
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    // 业务全部在 lib.rs，Tauri 仅作前后端桥接外壳，不写复杂 Rust 业务逻辑
    free_collector_lib::run()
}
