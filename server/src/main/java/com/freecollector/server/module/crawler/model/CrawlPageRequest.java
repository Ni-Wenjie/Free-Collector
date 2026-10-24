package com.freecollector.server.module.crawler.model;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 单页采集请求（模块三测试入口 + 后续任务调度模块复用）
 */
@Data
public class CrawlPageRequest {

    /** 目标页面地址，仅允许 http/https */
    @NotBlank(message = "网址不能为空")
    private String url;

    /** 采集字段列表（可视化框选生成） */
    private List<ExtractRule> fields;

    /** 访问速度档位：SLOW（默认）/ NORMAL / FAST */
    private String speedLevel = "SLOW";

    /** 模拟真人浏览：随机停顿 + 页面滚动 */
    private boolean simulateHuman = false;

    /** 自动轮换访问标识（UA） */
    private boolean rotateUa = false;

    /** 代理地址，如 http://127.0.0.1:8888 */
    private String proxyUrl;

    /** 高阶参数，普通模式下为 null */
    private AdvancedOptions advanced;

    /** 登录 Cookie（可选，格式 a=1; b=2），运行时解析为上下文 Cookie */
    private String cookie;
}
