package com.freecollector.server.module.crawler.engine;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/**
 * 一次采集会话：Playwright + Chromium + 独立浏览器上下文 + 页面
 *
 * Playwright 非线程安全，每个采集任务创建独立会话，用完即关。
 * 每个会话使用独立 BrowserContext，实现 Cookie/缓存/存储的页面沙箱隔离，
 * 任务之间互不影响。网络层只允许 http/https/data/about，阻断 file:// 等本地方案。
 */
@Slf4j
@Getter
public class PlaywrightSession implements AutoCloseable {

    private final Playwright playwright;
    private final Browser browser;
    private final BrowserContext context;
    private final Page page;

    PlaywrightSession(Playwright playwright, Browser browser, BrowserContext context, Page page) {
        this.playwright = playwright;
        this.browser = browser;
        this.context = context;
        this.page = page;
    }

    @Override
    public void close() {
        // 关闭顺序：上下文 → 浏览器 → Playwright，任一步失败都不影响后续关闭
        closeQuietly(() -> context.close());
        closeQuietly(() -> browser.close());
        closeQuietly(() -> playwright.close());
    }

    private void closeQuietly(Runnable action) {
        try {
            action.run();
        } catch (Exception e) {
            log.debug("关闭Playwright资源时出现异常（可忽略）: {}", e.getMessage());
        }
    }
}
