package com.freecollector.server.module.crawler;

import com.freecollector.server.module.crawler.engine.ElementExtractor;
import com.freecollector.server.module.crawler.engine.PaginationDetector;
import com.freecollector.server.module.crawler.model.ExtractRule;
import com.freecollector.server.module.crawler.model.PaginationInfo;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Playwright 内核冒烟测试（需要已安装内置浏览器）
 *
 * 默认跳过，避免首次运行自动下载浏览器影响常规构建。
 * 手动执行：
 *   PowerShell: $env:RUN_PLAYWRIGHT_TEST="true"; mvn test -Dtest=PlaywrightSmokeTest
 *   CMD       : set RUN_PLAYWRIGHT_TEST=true && mvn test -Dtest=PlaywrightSmokeTest
 */
@EnabledIfEnvironmentVariable(named = "RUN_PLAYWRIGHT_TEST", matches = "true")
class PlaywrightSmokeTest {

    @Test
    void extractFieldsAndDetectPagination() {
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(
                    new BrowserType.LaunchOptions().setHeadless(true));
            Page page = browser.newPage();

            // 使用静态HTML，不访问任何外部站点
            page.setContent("""
                    <html><body>
                      <div class="item"><span class="title">标题A</span><a class="link" href="/a">链接A</a></div>
                      <div class="item"><span class="title">标题B</span><a class="link" href="/b">链接B</a></div>
                      <a class="next" href="/list?page=2">下一页</a>
                    </body></html>
                    """);

            List<ExtractRule> rules = List.of(
                    new ExtractRule("标题", "TEXT", "CSS", ".item .title", "text", 1),
                    new ExtractRule("链接", "LINK", "CSS", ".item .link", "href", 2)
            );

            List<String> warnings = new ArrayList<>();
            Map<String, List<String>> values = new ElementExtractor().extract(page, rules, 500, warnings);

            assertEquals(List.of("标题A", "标题B"), values.get("标题"));
            assertEquals(List.of("/a", "/b"), values.get("链接"));
            assertTrue(warnings.isEmpty(), () -> "不应有告警: " + warnings);

            PaginationInfo pagination = new PaginationDetector().detect(page);
            assertTrue(pagination.isFound());
            assertEquals("/list?page=2", pagination.getHref());
            assertTrue(pagination.isHasNext());

            browser.close();
        }
    }
}
