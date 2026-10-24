package com.freecollector.server.module.task.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 任务调度参数（application.yml 中 free-collector.task 节点）
 */
@Data
@Component
@ConfigurationProperties(prefix = "free-collector.task")
public class TaskProperties {

    /** 本地工作线程数：每个线程独立浏览器会话，建议 1-3 */
    private int workerThreads = 2;

    /** 队列阻塞出队超时（秒） */
    private int popTimeoutSeconds = 1;

    /** 单任务默认最大翻页数（任务未单独配置时生效） */
    private int defaultMaxPages = 5;

    /** 翻页后等待页面刷新的毫秒数（应对JS异步渲染） */
    private int pageSwitchWaitMs = 800;
}
