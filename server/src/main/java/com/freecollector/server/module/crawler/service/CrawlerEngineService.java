package com.freecollector.server.module.crawler.service;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.crawler.config.CrawlerProperties;
import com.freecollector.server.module.crawler.engine.ElementExtractor;
import com.freecollector.server.module.crawler.engine.HumanBehaviorSimulator;
import com.freecollector.server.module.crawler.engine.PaginationDetector;
import com.freecollector.server.module.crawler.engine.PlaywrightSession;
import com.freecollector.server.module.crawler.engine.PlaywrightSessionFactory;
import com.freecollector.server.module.crawler.engine.RowAssembler;
import com.freecollector.server.module.crawler.engine.SmartWaiter;
import com.freecollector.server.module.crawler.model.CrawlOptions;
import com.freecollector.server.module.crawler.model.CrawlPageRequest;
import com.freecollector.server.module.crawler.model.PageExtractResult;
import com.freecollector.server.module.crawler.model.PreviewRenderRequest;
import com.freecollector.server.module.crawler.model.PreviewRenderResult;
import com.microsoft.playwright.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 爬虫引擎服务：编排一次完整的页面采集
 *
 * 同步执行，异步队列/并发调度由任务调度模块负责，因此不会阻塞前端。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CrawlerEngineService {

    private final PlaywrightSessionFactory sessionFactory;
    private final SmartWaiter smartWaiter;
    private final ElementExtractor elementExtractor;
    private final PaginationDetector paginationDetector;
    private final HumanBehaviorSimulator humanBehaviorSimulator;
    private final CrawlerProperties properties;

    /**
     * 采集单页
     */
    public PageExtractResult crawlPage(CrawlPageRequest request) {
        CrawlOptions options = CrawlOptions.from(request);
        long start = System.currentTimeMillis();
        Exception lastError = null;

        // 自动重试：首次 + retryTimes 次
        for (int attempt = 0; attempt <= options.getRetryTimes(); attempt++) {
            if (attempt > 0) {
                log.info("第{}次重试采集: {}", attempt, options.getUrl());
                backoff(attempt);
            }
            try (PlaywrightSession session = sessionFactory.open(options)) {
                Page page = session.getPage();

                page.navigate(options.getUrl(), new Page.NavigateOptions()
                        .setTimeout(options.getPageTimeoutMs()));

                smartWaiter.waitPageLoaded(page, options);
                humanBehaviorSimulator.simulate(page, options);
                injectScripts(page, options);
                smartWaiter.waitCustomCondition(page, options);

                PageExtractResult result = new PageExtractResult();
                result.setRetryUsed(attempt);
                result.setPageUrl(page.url());
                result.setTitle(page.title());

                List<String> warnings = new ArrayList<>();
                Map<String, List<String>> fieldValues = elementExtractor.extract(
                        page, request.getFields(), properties.getMaxItemsPerField(), warnings);
                result.setFieldValues(fieldValues);
                result.setRows(RowAssembler.assemble(fieldValues));
                result.setWarnings(warnings);

                result.setPagination(paginationDetector.detect(page));

                result.setElapsedMs(System.currentTimeMillis() - start);
                log.info("采集完成 url={} 字段数={} 数据行数={} 耗时={}ms",
                        options.getUrl(), fieldValues.size(), result.getRows().size(), result.getElapsedMs());
                return result;

            } catch (Exception e) {
                lastError = e;
                log.warn("采集失败 url={} attempt={} error={}", options.getUrl(), attempt, e.getMessage());
            }
        }

        String msg = lastError == null ? "未知错误" : lastError.getMessage();
        throw new BizException(ErrorCode.CRAWLER_ERROR, "页面采集失败：" + shorten(msg));
    }

    /**
     * 高阶JS注入：脚本异常不影响采集主流程，逐条记录告警
     */
    private void injectScripts(Page page, CrawlOptions options) {
        if (options.getAdvanced() == null || options.getAdvanced().getInjectScripts() == null) {
            return;
        }
        for (String script : options.getAdvanced().getInjectScripts()) {
            if (script == null || script.isBlank()) {
                continue;
            }
            try {
                page.evaluate(script);
            } catch (Exception e) {
                log.warn("自定义JS注入失败（已跳过）: {}", e.getMessage());
            }
        }
    }

    /** 重试退避：1s、2s、3s... 避免连续冲击目标站点 */
    private void backoff(int attempt) {
        try {
            Thread.sleep(1000L * attempt);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 错误信息截断，避免超长堆栈进入前端提示 */
    private String shorten(String msg) {
        if (msg == null) {
            return "未知错误";
        }
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }

    /**
     * 渲染页面：打开 Playwright 会话，导航等待后返回完整 HTML
     * <p>
     * 用于前端可视化预览。返回的 HTML 会注入 {@code <base href="最终URL">} 标签，
     * 这样前端用 iframe srcdoc 同源加载时，页面内的相对资源（图片/CSS/JS）仍能
     * 从原站正确加载，从而还原真实页面效果。
     *
     * @param request 预览请求
     * @return 渲染结果（HTML + 标题 + 最终URL + 耗时）
     */
    public PreviewRenderResult renderPage(PreviewRenderRequest request) {
        // 复用 CrawlOptions 的参数解析与安全校验（仅允许 http/https）
        CrawlOptions options = CrawlOptions.from(request.toCrawlPageRequest());
        long start = System.currentTimeMillis();
        Exception lastError = null;

        for (int attempt = 0; attempt <= options.getRetryTimes(); attempt++) {
            if (attempt > 0) {
                log.info("第{}次重试渲染: {}", attempt, request.getUrl());
                backoff(attempt);
            }
            try (PlaywrightSession session = sessionFactory.open(options)) {
                Page page = session.getPage();

                page.navigate(options.getUrl(), new Page.NavigateOptions()
                        .setTimeout(options.getPageTimeoutMs()));
                smartWaiter.waitPageLoaded(page, options);
                humanBehaviorSimulator.simulate(page, options);
                injectScripts(page, options);

                if (request.getWaitMs() != null && request.getWaitMs() > 0) {
                    page.waitForTimeout(request.getWaitMs());
                }

                String finalUrl = page.url();
                String title = page.title();
                String html = page.content();
                html = injectBaseTag(html, finalUrl);

                PreviewRenderResult result = new PreviewRenderResult();
                result.setHtml(html);
                result.setTitle(title);
                result.setFinalUrl(finalUrl);
                result.setElapsedMs(System.currentTimeMillis() - start);
                log.info("页面渲染完成 url={} title={} 耗时={}ms",
                        finalUrl, title, result.getElapsedMs());
                return result;

            } catch (Exception e) {
                lastError = e;
                log.warn("页面渲染失败 url={} attempt={} error={}", request.getUrl(), attempt, e.getMessage());
            }
        }

        String msg = lastError == null ? "未知错误" : lastError.getMessage();
        throw new BizException(ErrorCode.CRAWLER_ERROR, "页面渲染失败：" + shorten(msg));
    }

    /**
     * 在 HTML 中注入 {@code <base href="finalUrl">} 标签，使 iframe 加载时
     * 相对资源能从原站正确加载。已有 base 标签则替换其 href，无则插入 head 开头。
     */
    private String injectBaseTag(String html, String finalUrl) {
        if (html == null || html.isEmpty()) {
            return "";
        }
        String baseTag = "<base href=\"" + finalUrl + "\">";
        // 已有 base 标签：替换
        if (html.toLowerCase().contains("<base ")) {
            return html.replaceFirst("(?i)<base[^>]*>", baseTag);
        }
        // 无 base 但有 head：插入 head 标签之后
        if (html.toLowerCase().contains("<head")) {
            return html.replaceFirst("(?i)<head[^>]*>", "$0" + baseTag);
        }
        // 没有 head：在开头插入
        return baseTag + html;
    }
}
