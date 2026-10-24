package com.freecollector.server.module.crawler.engine;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import com.freecollector.server.module.crawler.model.CrawlOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 智能等待：不让固定 sleep 拖慢采集，也不在页面未就绪时提前提取
 */
@Slf4j
@Component
public class SmartWaiter {

    /**
     * 等待页面加载到指定状态（可选等待某个选择器出现）
     */
    public void waitPageLoaded(Page page, CrawlOptions options) {
        LoadState state = mapState(options.getWaitUntil());
        page.waitForLoadState(state, new Page.WaitForLoadStateOptions()
                .setTimeout(options.getPageTimeoutMs()));

        if (options.getWaitSelector() != null && !options.getWaitSelector().isBlank()) {
            page.waitForSelector(options.getWaitSelector(), new Page.WaitForSelectorOptions()
                    .setTimeout(options.getPageTimeoutMs()));
        }
    }

    /**
     * 自定义等待逻辑：执行用户配置的JS，返回true即视为就绪
     * 普通模式不配置则不执行
     */
    public void waitCustomCondition(Page page, CrawlOptions options) {
        if (options.getWaitScript() == null || options.getWaitScript().isBlank()) {
            return;
        }
        page.waitForFunction(options.getWaitScript(), null,
                new Page.WaitForFunctionOptions().setTimeout(options.getPageTimeoutMs()));
    }

    /** 等待策略字符串转 Playwright 枚举，未知值回落到 DOMCONTENTLOADED */
    private LoadState mapState(String waitUntil) {
        if (waitUntil == null) {
            return LoadState.DOMCONTENTLOADED;
        }
        switch (waitUntil.trim().toUpperCase()) {
            case "LOAD":
                return LoadState.LOAD;
            case "NETWORKIDLE":
                return LoadState.NETWORKIDLE;
            case "DOMCONTENTLOADED":
            default:
                return LoadState.DOMCONTENTLOADED;
        }
    }
}
