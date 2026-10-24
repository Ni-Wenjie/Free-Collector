package com.freecollector.server.module.crawler.engine;

import com.freecollector.server.module.crawler.config.CrawlerProperties;
import com.freecollector.server.module.crawler.model.CrawlOptions;
import com.freecollector.server.module.crawler.model.ExtractRule;
import com.freecollector.server.module.crawler.model.PagedCrawlResult;
import com.freecollector.server.module.crawler.model.PaginationInfo;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.LoadState;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 多页采集执行器：在同一个浏览器会话内完成"采集当前页 → 点下一页 → 再采集"
 *
 * 复用一个会话，Cookie/登录态在翻页间自然保持。每页采集完立即通过回调落库，
 * 避免数据全部堆在内存。断点续爬时，断点页之前的页面只翻页不落库，
 * 这样即使站点只能"点击下一页"也能从断点继续。暂停/终止在页间检查，保证不会半页中断。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PagedCrawlRunner {

    private final PlaywrightSessionFactory sessionFactory;
    private final SmartWaiter smartWaiter;
    private final ElementExtractor elementExtractor;
    private final PaginationDetector paginationDetector;
    private final HumanBehaviorSimulator humanBehaviorSimulator;
    private final CrawlerProperties properties;

    /**
     * 执行多页采集
     *
     * @param options          采集参数
     * @param fields           字段规则
     * @param startPersistPage 从第几页开始落库（断点续爬用，最小1）
     * @param maxPages         最大页数
     * @param pageSwitchWaitMs 翻页后额外等待毫秒
     * @param consumer         行消费者
     * @param shouldContinue   页间继续判断（暂停/终止返回 false）
     * @return 执行结果
     */
    public PagedCrawlResult run(CrawlOptions options, List<ExtractRule> fields,
                                int startPersistPage, int maxPages, int pageSwitchWaitMs,
                                PageRowConsumer consumer, BooleanSupplier shouldContinue) {
        int fromPage = Math.max(1, startPersistPage);
        int limit = Math.max(1, maxPages);
        PagedCrawlResult result = new PagedCrawlResult();
        result.setFinished(true);

        try (PlaywrightSession session = sessionFactory.open(options)) {
            Page page = session.getPage();

            page.navigate(options.getUrl(), new Page.NavigateOptions()
                    .setTimeout(options.getPageTimeoutMs()));
            smartWaiter.waitPageLoaded(page, options);
            humanBehaviorSimulator.simulate(page, options);
            injectScripts(page, options);

            int pageIndex = 1;
            while (pageIndex <= limit) {
                if (!shouldContinue.getAsBoolean()) {
                    result.setFinished(false);
                    break;
                }

                result.setPagesVisited(pageIndex);
                result.setLastPage(pageIndex);
                result.setLastPageUrl(page.url());

                List<String> warnings = new ArrayList<>();
                Map<String, List<String>> fieldValues = elementExtractor.extract(
                        page, fields, properties.getMaxItemsPerField(), warnings);
                result.getWarnings().addAll(warnings);
                List<Map<String, String>> rows = RowAssembler.assemble(fieldValues);

                if (pageIndex >= fromPage) {
                    boolean goOn = consumer.accept(pageIndex, page.url(), rows);
                    result.setPagesSaved(result.getPagesSaved() + 1);
                    if (!goOn) {
                        result.setFinished(false);
                        break;
                    }
                }

                PaginationInfo pagination = paginationDetector.detect(page);
                if (!pagination.isFound() || !pagination.isHasNext()) {
                    log.info("多页采集结束：无下一页 page={}", pageIndex);
                    break;
                }

                if (!clickNext(page, pagination, pageSwitchWaitMs)) {
                    result.getWarnings().add("第" + pageIndex + "页翻页失败，已停止继续翻页");
                    break;
                }
                pageIndex++;
            }

            // 达到最大页数也视为正常完成
            if (pageIndex > limit) {
                result.setFinished(true);
                log.info("多页采集结束：达到最大页数 maxPages={}", limit);
            }
            return result;
        }
    }

    /**
     * 点击下一页并等待刷新
     */
    private boolean clickNext(Page page, PaginationInfo pagination, int pageSwitchWaitMs) {
        try {
            Locator next = page.locator(pagination.getSelector()).first();
            next.click(new Locator.ClickOptions().setTimeout(10000));
            // 站点可能是整页跳转，也可能是JS局部刷新，两种都等待一下
            try {
                page.waitForLoadState(LoadState.DOMCONTENTLOADED,
                        new Page.WaitForLoadStateOptions().setTimeout(15000));
            } catch (Exception ignore) {
                // 非整页跳转时不会触发 load 事件，忽略即可
            }
            page.waitForTimeout(pageSwitchWaitMs);
            return true;
        } catch (Exception e) {
            log.warn("翻页点击失败 selector={} error={}", pagination.getSelector(), e.getMessage());
            return false;
        }
    }

    /** 高阶JS注入：脚本异常不影响采集主流程 */
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
}
