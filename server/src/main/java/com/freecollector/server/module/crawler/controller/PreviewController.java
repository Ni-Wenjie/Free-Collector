package com.freecollector.server.module.crawler.controller;

import com.freecollector.server.common.Result;
import com.freecollector.server.module.crawler.model.PreviewRenderRequest;
import com.freecollector.server.module.crawler.model.PreviewRenderResult;
import com.freecollector.server.module.crawler.service.CrawlerEngineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 页面预览接口（模块四：前端可视化元素选取）
 * <p>
 * 前端可视化采集的核心支撑：后端用 Playwright 打开目标页面，返回渲染后
 * 完整 HTML；前端用 iframe 的 srcdoc 同源加载，从而可以注入自定义 CSS/JS
 * 实现元素高亮与点击选取，绕开目标站 X-Frame-Options / CSP 跨域限制。
 */
@RestController
@RequestMapping("/api/crawler/preview")
@RequiredArgsConstructor
public class PreviewController {

    private final CrawlerEngineService crawlerEngineService;

    /**
     * 渲染页面：用 Playwright 打开目标 URL，返回渲染后 HTML
     * <p>
     * 返回的 HTML 已注入 {@code <base href="最终URL">} 标签，相对资源可正确加载。
     */
    @PostMapping("/render")
    public Result<PreviewRenderResult> render(@RequestBody @Valid PreviewRenderRequest request) {
        return Result.ok(crawlerEngineService.renderPage(request));
    }
}
