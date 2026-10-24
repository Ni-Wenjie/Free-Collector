package com.freecollector.server.module.crawler.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 爬虫内核全局参数（application.yml 中 free-collector.crawler 节点）
 */
@Data
@Component
@ConfigurationProperties(prefix = "free-collector.crawler")
public class CrawlerProperties {

    /** 是否无头模式：true 不显示浏览器窗口 */
    private boolean headless = true;

    /** 首次运行是否自动下载内置 Chromium */
    private boolean autoInstallBrowser = true;

    /** 浏览器安装超时（毫秒），默认10分钟 */
    private long installTimeoutMs = 600_000L;

    /** 单页单字段最大提取条数，防止异常页面撑爆内存 */
    private int maxItemsPerField = 500;
}
