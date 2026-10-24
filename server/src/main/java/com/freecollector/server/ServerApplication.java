package com.freecollector.server;

import org.apache.ibatis.annotations.Mapper;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 自由采集器后端启动类（本地常驻后台服务）
 *
 * 架构说明：
 *  - 前端 Vue 不直接发 HTTP，统一经 Tauri Rust 桥通过 IPC 转发到本服务；
 *  - 本服务仅监听 127.0.0.1，外部网络无法访问（见 application.yml 与 LocalhostOnlyFilter）。
 */
@SpringBootApplication
// 只扫描标注 @Mapper 的接口，避免把策略接口/业务接口误注册为 Mapper
@MapperScan(basePackages = "com.freecollector.server.module", annotationClass = Mapper.class)
public class ServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ServerApplication.class, args);
    }
}
