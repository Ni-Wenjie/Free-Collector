package com.freecollector.server.module.crawler.engine;

import com.freecollector.server.module.crawler.config.CrawlerProperties;
import com.freecollector.server.module.crawler.entity.CrawlUrlQueue;
import com.freecollector.server.module.crawler.mapper.CrawlUrlQueueMapper;
import com.freecollector.server.module.crawler.model.CrawlOptions;
import com.freecollector.server.module.crawler.model.ExtractRule;
import com.freecollector.server.module.crawler.strategy.CrawlStrategy;
import com.freecollector.server.module.crawler.strategy.CrawlStrategyFactory;
import com.freecollector.server.module.crawler.strategy.CrawlStrategy.LinkCandidate;
import com.freecollector.server.module.crawler.strategy.CrawlStrategy.MediaCandidate;
import com.freecollector.server.module.crawler.strategy.GenericCrawlStrategy;
import com.freecollector.server.module.crawler.strategy.SiteType;
import com.freecollector.server.module.crawler.strategy.SiteTypeDetector;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 递归爬取引擎：URL frontier 驱动的 BFS 遍历
 *
 * 与 PagedCrawlRunner 的区别：PagedCrawlRunner 是"点击下一页"的线性分页模式，
 * 本类是"跟随链接"的深度遍历模式，适合列表→详情→媒体的场景。
 * 由 CrawlTask.crawlMode 决定使用哪个。
 */
@Slf4j
@Component
public class RecursiveCrawlRunner {

    private final PlaywrightSessionFactory sessionFactory;
    private final SmartWaiter smartWaiter;
    private final HumanBehaviorSimulator humanBehaviorSimulator;
    private final ElementExtractor elementExtractor;
    private final SiteTypeDetector siteTypeDetector;
    private final CrawlStrategyFactory strategyFactory;
    private final CrawlUrlQueueMapper urlQueueMapper;
    private final CrawlerProperties properties;

    public RecursiveCrawlRunner(PlaywrightSessionFactory sessionFactory,
                                SmartWaiter smartWaiter,
                                HumanBehaviorSimulator humanBehaviorSimulator,
                                ElementExtractor elementExtractor,
                                SiteTypeDetector siteTypeDetector,
                                CrawlStrategyFactory strategyFactory,
                                CrawlUrlQueueMapper urlQueueMapper,
                                CrawlerProperties properties) {
        this.sessionFactory = sessionFactory;
        this.smartWaiter = smartWaiter;
        this.humanBehaviorSimulator = humanBehaviorSimulator;
        this.elementExtractor = elementExtractor;
        this.siteTypeDetector = siteTypeDetector;
        this.strategyFactory = strategyFactory;
        this.urlQueueMapper = urlQueueMapper;
        this.properties = properties;
    }

    /**
     * 执行递归爬取
     *
     * @param options          采集参数
     * @param fields           用户定义的字段规则（可以为空）
     * @param taskId           任务ID（用于URL队列表持久化）
     * @param maxDepth         最大递归深度
     * @param maxUrls          最多访问URL数
     * @param domainRestrict   是否限制同域
     * @param urlInclude       URL包含正则（白名单）
     * @param urlExclude       URL排除正则（黑名单）
     * @param pageHandler      每页处理回调：(depth, url, rows, mediaList, linkList)
     * @param shouldContinue   页间继续判断
     * @return 执行统计
     */
    public RecursiveCrawlResult run(CrawlOptions options,
                                    List<ExtractRule> fields,
                                    Long taskId,
                                    int maxDepth,
                                    int maxUrls,
                                    boolean domainRestrict,
                                    String urlInclude,
                                    String urlExclude,
                                    PageHandler pageHandler,
                                    BooleanSupplier shouldContinue) {

        RecursiveCrawlResult result = new RecursiveCrawlResult();
        result.setFinished(true);
        Set<String> visited = ConcurrentHashMap.newKeySet();
        int visitedCount = 0;

        // 编译正则过滤器
        Pattern includePattern = compilePattern(urlInclude);
        Pattern excludePattern = compilePattern(urlExclude);
        String startHost = extractHost(options.getUrl());

        // 初始化 URL frontier（用 List 模拟队列，便于断点恢复）
        List<UrlItem> frontier = new ArrayList<>();
        Set<String> queued = new HashSet<>();

        // 先从数据库恢复未完成的URL（断点续爬）
        if (taskId != null) {
            restorePendingUrls(taskId, frontier, queued);
        }

        // 如果没有恢复到，把起始URL入队
        if (frontier.isEmpty()) {
            String startUrl = options.getUrl();
            frontier.add(new UrlItem(startUrl, 0, null));
            queued.add(hashUrl(startUrl));
            // 持久化起始URL
            persistUrl(taskId, startUrl, 0, null, "PENDING");
        }

        log.info("递归爬取启动 startUrl={} maxDepth={} maxUrls={} queueSize={}",
                options.getUrl(), maxDepth, maxUrls, frontier.size());

        // BFS 主循环
        while (!frontier.isEmpty() && visitedCount < maxUrls) {
            // 用户暂停/终止检查
            if (!shouldContinue.getAsBoolean()) {
                result.setFinished(false);
                log.info("递归爬取被用户中断 visited={} queueRemaining={}",
                        visitedCount, frontier.size());
                break;
            }

            // 取队首（BFS：广度优先）
            UrlItem current = frontier.remove(0);
            String normalizedKey = GenericCrawlStrategy.normalizeUrl(current.url);

            // 已访问跳过
            if (!visited.add(normalizedKey)) {
                persistUrlStatus(taskId, current.url, "SKIPPED");
                continue;
            }
            visitedCount++;

            // 处理当前URL
            processUrl(current, options, fields, taskId, pageHandler, result,
                    frontier, queued, maxDepth, startHost, domainRestrict,
                    includePattern, excludePattern, shouldContinue);
        }

        if (visitedCount >= maxUrls) {
            log.info("递归爬取达到 maxUrls={}，停止入队新链接", maxUrls);
        }
        result.setUrlsVisited(visitedCount);
        result.setQueueRemaining(frontier.size());
        return result;
    }

    /**
     * 处理单个URL：打开页面 → 策略准备 → 提取 → 发现
     */
    private void processUrl(UrlItem item,
                            CrawlOptions options,
                            List<ExtractRule> fields,
                            Long taskId,
                            PageHandler pageHandler,
                            RecursiveCrawlResult result,
                            List<UrlItem> frontier,
                            Set<String> queued,
                            int maxDepth,
                            String startHost,
                            boolean domainRestrict,
                            Pattern includePattern,
                            Pattern excludePattern,
                            BooleanSupplier shouldContinue) {

        String url = item.url;
        int depth = item.depth;

        // 更新队列为处理中
        persistUrlStatus(taskId, url, "PROCESSING");

        // 更新 CrawlOptions 的当前URL（保持会话隔离）
        CrawlOptions currentOptions = options;
        try (PlaywrightSession session = sessionFactory.open(currentOptions)) {
            Page page = session.getPage();
            page.navigate(url, new Page.NavigateOptions()
                    .setTimeout(options.getPageTimeoutMs()));
            smartWaiter.waitPageLoaded(page, options);
            humanBehaviorSimulator.simulate(page, options);

            // 检测网站类型并选择策略
            SiteType siteType = siteTypeDetector.detect(page);
            CrawlStrategy strategy = strategyFactory.getStrategy(siteType);
            log.info("处理URL depth={} siteType={} url={}", depth, siteType, url);

            // 策略准备（展开折叠、触发懒加载等）
            strategy.preparePage(page);

            // 字段提取（用户自定义规则）
            List<Map<String, String>> rows = List.of();
            if (fields != null && !fields.isEmpty()) {
                List<String> warnings = new ArrayList<>();
                Map<String, List<String>> fieldValues = elementExtractor.extract(
                        page, fields, properties.getMaxItemsPerField(), warnings);
                rows = RowAssembler.assemble(fieldValues);
                result.addWarnings(warnings);
            }

            // 补充自动发现的数据（标题、URL等基础信息）
            Map<String, String> supplemental = strategy.extractSupplementalData(page);
            if (!supplemental.isEmpty() && !rows.isEmpty()) {
                for (Map<String, String> row : rows) {
                    supplemental.forEach((k, v) -> row.putIfAbsent(k, v));
                }
            }

            // 媒体发现
            List<MediaCandidate> mediaList = strategy.discoverMedia(page);
            log.info("媒体发现: {} 个", mediaList.size());

            // 链接发现（仅当还可以继续深入时）
            List<LinkCandidate> linkList = new ArrayList<>();
            if (depth < maxDepth) {
                linkList = strategy.discoverLinks(page, depth);
                for (LinkCandidate link : linkList) {
                    String targetUrl = link.getUrl();
                    if (!shouldEnqueue(targetUrl, startHost, domainRestrict,
                            includePattern, excludePattern, queued)) {
                        continue;
                    }
                    UrlItem next = new UrlItem(targetUrl, depth + 1, url);
                    frontier.add(next);
                    queued.add(hashUrl(targetUrl));
                    persistUrl(taskId, targetUrl, depth + 1, url, "PENDING");
                }
                log.info("链接发现并入队: {} 个", linkList.size());
            }

            // 回调通知外层（落库 + 媒体下载 + 进度更新）
            if (pageHandler != null) {
                try {
                    pageHandler.onPageProcessed(depth, url, rows, supplemental, mediaList, linkList);
                } catch (Exception e) {
                    log.warn("页面回调处理失败 url={}: {}", url, e.getMessage());
                }
            }

            // 更新队列为完成
            persistUrlStatus(taskId, url, "DONE");
            result.incrementPagesProcessed();

            // 合规限速
            try {
                Thread.sleep(options.randomDelayMs());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }

        } catch (Exception e) {
            log.warn("URL处理失败 url={}: {}", url, e.getMessage());
            result.addWarning("URL处理失败: " + url + " - " + e.getMessage());
            persistUrlStatus(taskId, url, "FAILED");
        }
    }

    // URL 过滤与入队

    private boolean shouldEnqueue(String url, String startHost, boolean domainRestrict,
                                  Pattern include, Pattern exclude, Set<String> queued) {
        if (url == null || url.isBlank()) return false;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return false;

        // 去重检查
        if (queued.contains(hashUrl(url))) return false;

        // 排除规则优先级最高
        if (exclude != null && exclude.matcher(url).find()) return false;

        // 包含规则（白名单）
        if (include != null && !include.matcher(url).find()) return false;

        // 同域限制
        if (domainRestrict) {
            String host = extractHost(url);
            if (startHost != null && !startHost.equalsIgnoreCase(host)) return false;
        }

        return true;
    }

    // 数据库持久化（断点续爬）

    private void restorePendingUrls(Long taskId, List<UrlItem> frontier, Set<String> queued) {
        if (taskId == null) return;
        try {
            List<CrawlUrlQueue> pendings = urlQueueMapper.selectList(
                    new LambdaQueryWrapper<CrawlUrlQueue>()
                            .eq(CrawlUrlQueue::getTaskId, taskId)
                            .in(CrawlUrlQueue::getStatus, "PENDING", "PROCESSING")
                            .orderByAsc(CrawlUrlQueue::getPriority)
                            .orderByAsc(CrawlUrlQueue::getId));
            for (CrawlUrlQueue q : pendings) {
                frontier.add(new UrlItem(q.getUrl(), q.getDepth(), q.getParentUrl()));
                queued.add(q.getUrlHash());
            }
            if (!pendings.isEmpty()) {
                log.info("从数据库恢复 {} 个待处理URL（断点续爬）", pendings.size());
                // 把 PROCESSING 重置为 PENDING
                urlQueueMapper.update(null,
                        new LambdaUpdateWrapper<CrawlUrlQueue>()
                                .eq(CrawlUrlQueue::getTaskId, taskId)
                                .eq(CrawlUrlQueue::getStatus, "PROCESSING")
                                .set(CrawlUrlQueue::getStatus, "PENDING"));
            }
        } catch (Exception e) {
            log.warn("恢复待处理URL失败: {}", e.getMessage());
        }
    }

    private void persistUrl(Long taskId, String url, int depth, String parentUrl, String status) {
        if (taskId == null) return;
        try {
            CrawlUrlQueue entity = new CrawlUrlQueue();
            entity.setTaskId(taskId);
            entity.setUrl(url);
            entity.setParentUrl(parentUrl);
            entity.setDepth(depth);
            entity.setUrlHash(hashUrl(url));
            entity.setStatus(status);
            entity.setPriority(depth);
            // upsert：存在则忽略（唯一索引冲突）
            try {
                urlQueueMapper.insert(entity);
            } catch (Exception ignore) {
                // 已存在，忽略
            }
        } catch (Exception e) {
            log.debug("URL入队持久化失败（忽略）: {}", e.getMessage());
        }
    }

    private void persistUrlStatus(Long taskId, String url, String status) {
        if (taskId == null) return;
        try {
            urlQueueMapper.update(null,
                    new LambdaUpdateWrapper<CrawlUrlQueue>()
                            .eq(CrawlUrlQueue::getTaskId, taskId)
                            .eq(CrawlUrlQueue::getUrlHash, hashUrl(url))
                            .set(CrawlUrlQueue::getStatus, status));
        } catch (Exception e) {
            log.debug("URL状态更新失败（忽略）: {}", e.getMessage());
        }
    }

    // 工具方法

    private Pattern compilePattern(String regex) {
        if (regex == null || regex.isBlank()) return null;
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (Exception e) {
            log.warn("URL正则编译失败: {} - {}", regex, e.getMessage());
            return null;
        }
    }

    private String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }

    private String hashUrl(String url) {
        String normalized = GenericCrawlStrategy.normalizeUrl(url);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            // 降级：用字符串hashCode
            return String.valueOf(normalized.hashCode());
        }
    }

    // 内部数据类

    /** URL 队列项 */
    public static class UrlItem {
        public final String url;
        public final int depth;
        public final String parentUrl;

        public UrlItem(String url, int depth, String parentUrl) {
            this.url = url;
            this.depth = depth;
            this.parentUrl = parentUrl;
        }
    }

    /** 单页处理回调 */
    @FunctionalInterface
    public interface PageHandler {
        void onPageProcessed(int depth, String url,
                             List<Map<String, String>> rows,
                             Map<String, String> supplemental,
                             List<MediaCandidate> mediaList,
                             List<LinkCandidate> linkList);
    }

    /** 递归爬取执行结果 */
    public static class RecursiveCrawlResult {
        private boolean finished = true;
        private int urlsVisited;
        private int pagesProcessed;
        private int queueRemaining;
        private List<String> warnings = new ArrayList<>();

        public boolean isFinished() { return finished; }
        public void setFinished(boolean finished) { this.finished = finished; }
        public int getUrlsVisited() { return urlsVisited; }
        public void setUrlsVisited(int urlsVisited) { this.urlsVisited = urlsVisited; }
        public int getPagesProcessed() { return pagesProcessed; }
        public void incrementPagesProcessed() { this.pagesProcessed++; }
        public int getQueueRemaining() { return queueRemaining; }
        public void setQueueRemaining(int queueRemaining) { this.queueRemaining = queueRemaining; }
        public List<String> getWarnings() { return warnings; }
        public void addWarning(String w) { this.warnings.add(w); }
        public void addWarnings(List<String> ws) { if (ws != null) this.warnings.addAll(ws); }
    }
}
