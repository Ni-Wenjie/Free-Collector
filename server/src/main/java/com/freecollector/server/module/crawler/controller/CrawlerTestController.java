package com.freecollector.server.module.crawler.controller;

import com.freecollector.server.common.Result;
import com.freecollector.server.module.crawler.engine.BrowserInstaller;
import com.freecollector.server.module.crawler.engine.UaProvider;
import com.freecollector.server.module.crawler.model.CrawlPageRequest;
import com.freecollector.server.module.crawler.model.PageExtractResult;
import com.freecollector.server.module.crawler.model.SpeedProfile;
import com.freecollector.server.module.crawler.service.CrawlerEngineService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 爬虫内核测试接口（模块三联调用，后续任务模块复用引擎服务）
 */
@RestController
@RequestMapping("/api/crawler")
@RequiredArgsConstructor
public class CrawlerTestController {

    private final CrawlerEngineService crawlerEngineService;
    private final BrowserInstaller browserInstaller;
    private final UaProvider uaProvider;

    /**
     * 单页采集测试
     * 示例请求体：
     * {
     *   "url": "https://example.com",
     *   "fields": [{"fieldName":"标题","locatorType":"CSS","locatorExpression":"h1","extractAttr":"text"}],
     *   "speedLevel": "SLOW",
     *   "simulateHuman": true,
     *   "rotateUa": true
     * }
     */
    @PostMapping("/test/extract")
    public Result<PageExtractResult> extract(@RequestBody @Valid CrawlPageRequest request) {
        return Result.ok(crawlerEngineService.crawlPage(request));
    }

    /**
     * 引擎状态：浏览器是否就绪、UA池、速度档位说明
     */
    @GetMapping("/engine/status")
    public Result<Map<String, Object>> status() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("engine", "playwright");
        info.put("browserInstalled", browserInstaller.isInstalled());
        info.put("browserPath", browserInstaller.browsersPath());
        info.put("uaPool", uaProvider.all());

        List<Map<String, Object>> speeds = Arrays.stream(SpeedProfile.values())
                .map(p -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("level", p.name());
                    m.put("concurrency", p.getConcurrency());
                    m.put("minDelayMs", p.getMinDelayMs());
                    m.put("maxDelayMs", p.getMaxDelayMs());
                    m.put("description", p.getDescription());
                    return m;
                })
                .collect(Collectors.toList());
        info.put("speedProfiles", speeds);
        return Result.ok(info);
    }
}
