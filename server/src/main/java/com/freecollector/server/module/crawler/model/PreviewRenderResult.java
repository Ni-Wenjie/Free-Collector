package com.freecollector.server.module.crawler.model;

import lombok.Data;

/**
 * 页面预览渲染结果
 */
@Data
public class PreviewRenderResult {

    /** 渲染后的完整 HTML（已注入 <base href> 标签，相对资源可正确加载） */
    private String html;

    /** 页面标题 */
    private String title;

    /** 最终 URL（处理重定向后） */
    private String finalUrl;

    /** 渲染耗时（毫秒） */
    private long elapsedMs;
}
