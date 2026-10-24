package com.freecollector.server.module.task.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.crawler.engine.DownloadManager;
import com.freecollector.server.module.crawler.engine.PagedCrawlRunner;
import com.freecollector.server.module.crawler.engine.RecursiveCrawlRunner;
import com.freecollector.server.module.crawler.engine.RecursiveCrawlRunner.RecursiveCrawlResult;
import com.freecollector.server.module.crawler.model.AdvancedOptions;
import com.freecollector.server.module.crawler.model.CrawlOptions;
import com.freecollector.server.module.crawler.model.CrawlPageRequest;
import com.freecollector.server.module.crawler.model.ExtractRule;
import com.freecollector.server.module.crawler.model.PagedCrawlResult;
import com.freecollector.server.module.crawler.strategy.CrawlStrategy.LinkCandidate;
import com.freecollector.server.module.crawler.strategy.CrawlStrategy.MediaCandidate;
import com.freecollector.server.module.risk.service.RiskControlService;
import com.freecollector.server.module.task.config.TaskProperties;
import com.freecollector.server.module.task.dto.TaskRuntimeVO;
import com.freecollector.server.module.task.entity.CrawlField;
import com.freecollector.server.module.task.entity.CrawlResult;
import com.freecollector.server.module.task.entity.CrawlTask;
import com.freecollector.server.module.task.mapper.CrawlFieldMapper;
import com.freecollector.server.module.task.mapper.CrawlResultMapper;
import com.freecollector.server.module.task.mapper.CrawlTaskMapper;
import com.freecollector.server.module.task.model.TaskStatus;
import com.freecollector.server.module.task.util.DedupHashUtil;
import com.freecollector.server.util.AesCryptoUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 任务执行服务：由工作线程调用，完成一个任务的实际采集
 *
 * 读取任务与字段，解密代理/Cookie，构建采集参数，多页采集并落库更新断点，
 * 最后根据控制指令收尾（暂停/终止/完成/失败）。
 * checkpoint_cursor 记录"下一页页码"，继续时从该页开始落库，
 * 之前的页面只翻页不写入，保证点选式分页站也能续爬。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskExecutionService {

    private static final DateTimeFormatter BATCH_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CrawlTaskMapper taskMapper;
    private final CrawlFieldMapper fieldMapper;
    private final CrawlResultMapper resultMapper;
    private final com.freecollector.server.module.crawler.mapper.CrawlMediaMapper mediaMapper;
    private final TaskQueueService queueService;
    private final PagedCrawlRunner pagedCrawlRunner;
    private final RecursiveCrawlRunner recursiveCrawlRunner;
    private final DownloadManager downloadManager;
    private final TaskProperties taskProperties;
    private final ObjectMapper objectMapper;
    private final RiskControlService riskControlService;

    /**
     * 执行任务（阻塞直到结束或暂停/终止）
     */
    public void execute(Long taskId) {
        CrawlTask task = taskMapper.selectById(taskId);
        if (task == null) {
            log.warn("队列中的任务不存在，已跳过 taskId={}", taskId);
            queueService.deleteRuntime(taskId);
            return;
        }

        // 只执行处于 READY 的任务：暂停/终止后数据库状态已变，队列中的过期消息直接丢弃
        if (!TaskStatus.READY.equals(task.getStatus())) {
            log.info("任务状态不是待运行，丢弃队列消息 taskId={} status={}", taskId, task.getStatus());
            queueService.clearControl(taskId);
            return;
        }

        // 启动前已被暂停/终止（此时数据库状态已是 PAUSED/STOPPED，收尾写入会被条件更新跳过）
        String control = queueService.getControl(taskId);
        if ("STOP".equals(control)) {
            markStatus(taskId, TaskStatus.STOPPED, "已终止");
            queueService.clearControl(taskId);
            return;
        }
        if ("PAUSE".equals(control)) {
            markStatus(taskId, TaskStatus.PAUSED, "已暂停");
            queueService.clearControl(taskId);
            return;
        }

        int maxPages = readMaxPages(task);
        int startPage = parseCheckpoint(task);
        String batchNo = buildBatchNo(taskId);

        // 运行期计数（以数据库已有值为基础累加）
        AtomicInteger successCount = new AtomicInteger(safeInt(task.getSuccessCount()));
        AtomicInteger failCount = new AtomicInteger(safeInt(task.getFailCount()));

        // READY → RUNNING 采用条件更新：
        // 若入队后用户已暂停/终止/继续（数据库状态不再是 READY），说明本次队列消息已过期，放弃执行，
        // 避免与 resume 产生竞态（旧运行的收尾覆盖新运行写入的 READY 状态）
        if (!updateTaskStatusCond(taskId, TaskStatus.RUNNING, TaskStatus.READY)) {
            log.info("任务状态已被变更，放弃本次执行 taskId={}", taskId);
            queueService.clearControl(taskId);
            return;
        }

        // 读取当前运行令牌作为本次运行的身份，收尾时校验（见下方收尾处注释）
        String myRunToken = queueService.getRunToken(taskId);

        // 合规风控：执行前再次确认 robots 允许（防止启动后站点规则变更）
        try {
            riskControlService.assertAllowedToCrawl(task.getTargetUrl());
        } catch (BizException e) {
            log.warn("任务被合规风控拦截 taskId={} reason={}", taskId, e.getMessage());
            markStatus(taskId, TaskStatus.FAILED, e.getMessage());
            queueService.clearControl(taskId);
            return;
        }

        TaskRuntimeVO runtime = new TaskRuntimeVO();
        runtime.setTaskId(taskId);
        runtime.setStatus("RUNNING");
        runtime.setMaxPages(maxPages);
        runtime.setCurrentPage(startPage);
        runtime.setSuccessCount(successCount.get());
        runtime.setFailCount(failCount.get());
        runtime.setBatchNo(batchNo);
        runtime.setMessage("正在启动任务...");
        queueService.saveRuntime(runtime);

        try {
            String crawlMode = task.getCrawlMode();
            if (crawlMode == null || crawlMode.isBlank() || "FLAT".equalsIgnoreCase(crawlMode)) {
                runFlatMode(task, taskId, myRunToken, batchNo, rules(taskId),
                        buildOptions(task), startPage, maxPages,
                        successCount, failCount);
            } else if ("RECURSIVE".equalsIgnoreCase(crawlMode) || "MEDIA_ONLY".equalsIgnoreCase(crawlMode)) {
                runRecursiveMode(task, taskId, myRunToken, batchNo, rules(taskId),
                        buildOptions(task), successCount, failCount);
            } else {
                throw new BizException(ErrorCode.PARAM_ERROR, "未知的爬取模式: " + crawlMode);
            }

            // 收尾前先校验运行令牌：
            // 场景"暂停后立即继续"——resume 会生成新令牌并入队，新运行可能已把状态改回 RUNNING，
            // 此时旧运行（本次）若继续收尾，会把新运行的状态错写成 FINISHED 并误清控制指令。
            // 令牌不匹配说明本次运行已被取代，直接静默退出（状态由新运行全权管理）。
            if (myRunToken != null && !myRunToken.equals(queueService.getRunToken(taskId))) {
                log.info("运行令牌已失效，本次运行静默退出 taskId={}", taskId);
                return;
            }

            // 媒体下载（如果开启了）
            boolean mediaDownload = task.getMediaDownload() != null && task.getMediaDownload() == 1;
            if (mediaDownload) {
                runtime.setMessage("正在下载媒体文件...");
                queueService.saveRuntime(runtime);
                downloadManager.drainAndWait(taskId, 3);
            }

            // 根据控制指令与执行结果收尾
            String finalControl = queueService.getControl(taskId);
            String status;
            String message;
            if ("STOP".equals(finalControl)) {
                status = TaskStatus.STOPPED;
                message = "已终止";
            } else if ("PAUSE".equals(finalControl)) {
                status = TaskStatus.PAUSED;
                message = "已暂停";
            } else {
                status = TaskStatus.FINISHED;
                message = successCount.get() == 0
                        ? "采集结束，但没有提取到数据，请检查配置"
                        : "采集完成，共写入 " + successCount.get() + " 条";
            }
            queueService.clearControl(taskId);
            markStatus(taskId, status, message);

            log.info("任务执行结束 taskId={} status={} rows={}",
                    taskId, status, successCount.get());

        } catch (BizException e) {
            log.warn("任务执行失败 taskId={} msg={}", taskId, e.getMessage());
            markStatus(taskId, TaskStatus.FAILED, e.getMessage());
        } catch (Exception e) {
            log.error("任务执行异常 taskId={}", taskId, e);
            markStatus(taskId, TaskStatus.FAILED, "采集异常：" + shorten(e.getMessage()));
        }
    }

    /**
     * 保存一页数据并更新进度、断点
     *
     * @return true 继续翻页；false 用户已暂停/终止
     */
    private boolean savePage(Long taskId, String runToken, String batchNo, int pageIndex, String pageUrl,
                             List<Map<String, String>> rows, Set<String> seenHashes,
                             AtomicInteger successCount, AtomicInteger failCount, int maxPages) {
        // 运行令牌失效说明用户已点击"继续"（生成了新令牌），本运行必须立刻停止落库，
        // 否则当前页会在新运行的断点页上重复写入，导致续爬后出现重复数据
        if (runToken != null && !runToken.equals(queueService.getRunToken(taskId))) {
            log.info("运行令牌已失效，停止落库 taskId={} page={}", taskId, pageIndex);
            return false;
        }
        int savedThisPage = 0;
        for (Map<String, String> row : rows) {
            // 只保留有值的行
            if (row.values().stream().noneMatch(v -> v != null && !v.isBlank())) {
                continue;
            }
            String hash = DedupHashUtil.hash(row);
            // 同一批次内重复数据直接跳过
            if (!seenHashes.add(hash)) {
                continue;
            }
            try {
                CrawlResult entity = new CrawlResult();
                entity.setTaskId(taskId);
                entity.setBatchNo(batchNo);
                entity.setPageUrl(pageUrl);
                entity.setDataJson(DedupHashUtil.toJson(row));
                entity.setDedupHash(hash);
                resultMapper.insert(entity);
                savedThisPage++;
            } catch (Exception e) {
                failCount.incrementAndGet();
                log.warn("结果写入失败 taskId={} page={} error={}", taskId, pageIndex, e.getMessage());
            }
        }
        successCount.addAndGet(savedThisPage);

        // 更新任务进度与断点游标（下一页）
        CrawlTask update = new CrawlTask();
        update.setId(taskId);
        update.setSuccessCount(successCount.get());
        update.setFailCount(failCount.get());
        update.setTotalCount(successCount.get() + failCount.get());
        update.setCheckpointCursor(String.valueOf(pageIndex + 1));
        taskMapper.updateById(update);

        // 更新运行时快照
        TaskRuntimeVO runtime = new TaskRuntimeVO();
        runtime.setTaskId(taskId);
        runtime.setStatus("RUNNING");
        runtime.setCurrentPage(pageIndex);
        runtime.setMaxPages(maxPages);
        runtime.setSuccessCount(successCount.get());
        runtime.setFailCount(failCount.get());
        runtime.setBatchNo(batchNo);
        runtime.setMessage("已完成第 " + pageIndex + " 页，本页写入 " + savedThisPage + " 条");
        queueService.saveRuntime(runtime);

        // 合规风控：同站点请求限速（限速开关关闭时为空操作）
        riskControlService.throttle(pageUrl);

        return queueService.getControl(taskId) == null;
    }

    /** 构建采集参数：解密代理与 Cookie，仅在本机内存中短暂持有明文 */
    private CrawlOptions buildOptions(CrawlTask task) {
        CrawlPageRequest req = new CrawlPageRequest();
        req.setUrl(task.getTargetUrl());
        req.setSpeedLevel(task.getSpeedLevel());
        req.setSimulateHuman(task.getSimulateHuman() != null && task.getSimulateHuman() == 1);
        req.setRotateUa(task.getRotateUa() != null && task.getRotateUa() == 1);

        // 代理地址解密（字段中可能含账号密码，禁止明文入库）
        String proxy = decryptQuietly(task.getProxyUrlEncrypted());
        if (proxy != null && !proxy.isBlank()) {
            req.setProxyUrl(proxy.trim());
        }

        AdvancedOptions advanced = parseAdvancedConfig(task.getAdvancedConfig());
        // Cookie 解密并解析为 Playwright Cookie 列表（任务级独立字段，优先于高阶面板里的 cookies）
        String cookieRaw = decryptQuietly(task.getCookieEncrypted());
        if (cookieRaw != null && !cookieRaw.isBlank()) {
            advanced.setCookies(parseCookies(cookieRaw, task.getTargetUrl()));
        }
        req.setAdvanced(advanced);

        return CrawlOptions.from(req);
    }

    /**
     * 反序列化 advanced_config JSON 为高阶参数
     * JSON 中含 maxPages 等任务级键，AdvancedOptions 已配置忽略未知属性
     */
    private AdvancedOptions parseAdvancedConfig(String json) {
        if (json == null || json.isBlank()) {
            return new AdvancedOptions();
        }
        try {
            AdvancedOptions options = objectMapper.readValue(json, AdvancedOptions.class);
            return options == null ? new AdvancedOptions() : options;
        } catch (Exception e) {
            log.warn("高阶配置JSON解析失败，按默认参数执行: {}", e.getMessage());
            return new AdvancedOptions();
        }
    }

    /** 解析 "a=1; b=2" 形式的 Cookie 字符串 */
    private List<AdvancedOptions.CookieItem> parseCookies(String raw, String targetUrl) {
        List<AdvancedOptions.CookieItem> cookies = new ArrayList<>();
        String host = hostOf(targetUrl);
        for (String part : raw.split("[;\\n]")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            int idx = item.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            AdvancedOptions.CookieItem cookie = new AdvancedOptions.CookieItem();
            cookie.setName(item.substring(0, idx).trim());
            cookie.setValue(item.substring(idx + 1).trim());
            cookie.setDomain(host);
            cookie.setPath("/");
            cookies.add(cookie);
        }
        return cookies;
    }

    /** 读取任务字段规则并转换为提取规则 */
    private List<ExtractRule> loadRules(Long taskId) {
        List<CrawlField> fields = fieldMapper.selectList(
                new LambdaQueryWrapper<CrawlField>()
                        .eq(CrawlField::getTaskId, taskId)
                        .orderByAsc(CrawlField::getSortOrder)
                        .orderByAsc(CrawlField::getId));
        List<ExtractRule> rules = new ArrayList<>();
        for (CrawlField field : fields) {
            ExtractRule rule = new ExtractRule();
            rule.setFieldName(field.getFieldName());
            rule.setFieldType(field.getFieldType());
            rule.setLocatorType(field.getLocatorType());
            rule.setLocatorExpression(field.getLocatorExpression());
            rule.setExtractAttr(field.getExtractAttr());
            rule.setSortOrder(field.getSortOrder() == null ? 0 : field.getSortOrder());
            rules.add(rule);
        }
        return rules;
    }

    /** 读取任务最大翻页数（存于 advanced_config JSON） */
    int readMaxPages(CrawlTask task) {
        String json = task.getAdvancedConfig();
        if (json == null || json.isBlank()) {
            return taskProperties.getDefaultMaxPages();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            int value = node.path("maxPages").asInt(taskProperties.getDefaultMaxPages());
            return value <= 0 ? taskProperties.getDefaultMaxPages() : value;
        } catch (Exception e) {
            return taskProperties.getDefaultMaxPages();
        }
    }

    /** 解析断点游标为起始落库页码 */
    private int parseCheckpoint(CrawlTask task) {
        String cursor = task.getCheckpointCursor();
        if (cursor == null || cursor.isBlank()) {
            return 1;
        }
        try {
            int page = Integer.parseInt(cursor.trim());
            return Math.max(page, 1);
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
     * 收尾：更新数据库状态并写入运行时快照
     *
     * 状态更新采用条件更新（仅当数据库仍为 RUNNING 才生效）：
     * 暂停后用户立即点"继续"时，resume 会先把状态置为 READY 并重新入队，
     * 旧运行的收尾若直接覆盖会把 READY 错写成 FINISHED，导致新队列消息被丢弃。
     * 条件更新失败说明状态已被用户操作变更，本次收尾直接跳过（含快照）。
     */
    private void markStatus(Long taskId, String status, String message) {
        CrawlTask update = new CrawlTask();
        update.setId(taskId);
        update.setStatus(status);
        // 已完成的任务清空断点游标，下次启动从头开始
        if (TaskStatus.FINISHED.equals(status)) {
            update.setCheckpointCursor("");
        }
        int rows = taskMapper.update(update, new LambdaQueryWrapper<CrawlTask>()
                .eq(CrawlTask::getId, taskId)
                .eq(CrawlTask::getStatus, TaskStatus.RUNNING));
        if (rows == 0) {
            log.info("任务状态已被变更，跳过收尾写入 taskId={} targetStatus={}", taskId, status);
            return;
        }

        TaskRuntimeVO runtime = queueService.getRuntime(taskId);
        if (runtime == null) {
            runtime = new TaskRuntimeVO();
            runtime.setTaskId(taskId);
        }
        runtime.setStatus(status);
        runtime.setMessage(message);
        queueService.saveRuntime(runtime);
    }

    /** 条件状态更新：仅当当前状态等于 fromStatus 时才更新为 toStatus */
    private boolean updateTaskStatusCond(Long taskId, String toStatus, String fromStatus) {
        CrawlTask update = new CrawlTask();
        update.setId(taskId);
        update.setStatus(toStatus);
        return taskMapper.update(update, new LambdaQueryWrapper<CrawlTask>()
                .eq(CrawlTask::getId, taskId)
                .eq(CrawlTask::getStatus, fromStatus)) > 0;
    }

    /** 批次号：任务ID + 时间 + 随机数，便于区分多次运行 */
    private String buildBatchNo(Long taskId) {
        return "T" + taskId + "-" + LocalDateTime.now().format(BATCH_FMT)
                + "-" + ThreadLocalRandom.current().nextInt(1000, 9999);
    }

    private String decryptQuietly(String cipher) {
        if (cipher == null || cipher.isBlank()) {
            return null;
        }
        try {
            return AesCryptoUtil.decrypt(cipher);
        } catch (Exception e) {
            log.warn("敏感配置解密失败，已忽略该配置: {}", e.getMessage());
            return null;
        }
    }

    private String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String shorten(String msg) {
        if (msg == null || msg.isBlank()) {
            return "未知错误";
        }
        return msg.length() > 300 ? msg.substring(0, 300) + "..." : msg;
    }

    /**
     * 扁平翻页模式（PagedCrawlRunner，兼容旧版任务）
     */
    private void runFlatMode(CrawlTask task, Long taskId, String myRunToken, String batchNo,
                             List<ExtractRule> rules, CrawlOptions options,
                             int startPage, int maxPages,
                             AtomicInteger successCount, AtomicInteger failCount) {
        if (rules.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务没有采集字段，请先在编辑页添加字段");
        }
        Set<String> seenHashes = new HashSet<>();
        PagedCrawlResult result = pagedCrawlRunner.run(
                options, rules, startPage, maxPages, taskProperties.getPageSwitchWaitMs(),
                (pageIndex, pageUrl, rows) -> savePage(taskId, myRunToken, batchNo, pageIndex, pageUrl,
                        rows, seenHashes, successCount, failCount, maxPages),
                () -> queueService.getControl(taskId) == null
                        && (myRunToken == null || myRunToken.equals(queueService.getRunToken(taskId))));
        log.info("FLAT 模式完成 pagesVisited={} pagesSaved={}",
                result.getPagesVisited(), result.getPagesSaved());
    }

    /**
     * 递归爬取 / 仅媒体下载模式（RecursiveCrawlRunner）
     */
    private void runRecursiveMode(CrawlTask task, Long taskId, String myRunToken, String batchNo,
                                  List<ExtractRule> rules, CrawlOptions options,
                                  AtomicInteger successCount, AtomicInteger failCount) {

        // MEDIA_ONLY 模式不需要字段规则
        boolean mediaOnly = "MEDIA_ONLY".equalsIgnoreCase(task.getCrawlMode());
        if (!mediaOnly && rules.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务没有采集字段，请先在编辑页添加字段");
        }

        int maxDepth = task.getMaxDepth() != null ? task.getMaxDepth() : 2;
        int maxUrls = task.getMaxUrls() != null ? task.getMaxUrls() : 500;
        boolean domainRestrict = task.getDomainRestrict() == null || task.getDomainRestrict() == 1;
        boolean autoDiscoverRaw = task.getAutoDiscover() != null && task.getAutoDiscover() == 1;
        boolean mediaDownloadRaw = task.getMediaDownload() != null && task.getMediaDownload() == 1;

        // MEDIA_ONLY 模式强制自动发现和下载
        final boolean autoDiscover;
        final boolean mediaDownload;
        final int effectiveMaxDepth;
        if (mediaOnly) {
            autoDiscover = true;
            mediaDownload = true;
            effectiveMaxDepth = Math.max(maxDepth, 3); // 至少深入3层
        } else {
            autoDiscover = autoDiscoverRaw;
            mediaDownload = mediaDownloadRaw;
            effectiveMaxDepth = maxDepth;
        }

        Set<String> seenHashes = new HashSet<>();
        AtomicInteger pagesProcessed = new AtomicInteger(0);
        AtomicInteger mediaFound = new AtomicInteger(0);

        RecursiveCrawlResult result = recursiveCrawlRunner.run(
                options,
                mediaOnly ? List.of() : rules,  // MEDIA_ONLY 不需要用户字段规则
                taskId,
                effectiveMaxDepth,
                maxUrls,
                domainRestrict,
                task.getUrlIncludePattern(),
                task.getUrlExcludePattern(),
                (depth, url, rows, supplemental, mediaList, linkList) -> {
                    // 运行令牌检查
                    if (myRunToken != null && !myRunToken.equals(queueService.getRunToken(taskId))) {
                        return;
                    }
                    pagesProcessed.incrementAndGet();

                    if (!rows.isEmpty()) {
                        for (Map<String, String> row : rows) {
                            if (row.values().stream().noneMatch(v -> v != null && !v.isBlank())) {
                                continue;
                            }
                            row.putIfAbsent("__page_url", url);
                            row.putIfAbsent("__depth", String.valueOf(depth));

                            String hash = DedupHashUtil.hash(row);
                            if (!seenHashes.add(hash)) continue;
                            try {
                                CrawlResult entity = new CrawlResult();
                                entity.setTaskId(taskId);
                                entity.setBatchNo(batchNo);
                                entity.setPageUrl(url);
                                entity.setDataJson(DedupHashUtil.toJson(row));
                                entity.setDedupHash(hash);
                                resultMapper.insert(entity);
                                successCount.incrementAndGet();
                            } catch (Exception e) {
                                failCount.incrementAndGet();
                                log.warn("递归模式结果写入失败: {}", e.getMessage());
                            }
                        }
                    }

                    if (autoDiscover && mediaList != null && !mediaList.isEmpty()) {
                        for (MediaCandidate mc : mediaList) {
                            mc.setSourcePageUrl(url);
                        }
                        mediaFound.addAndGet(mediaList.size());
                        downloadManager.submitBatch(taskId, batchNo, mediaList,
                                mediaDownload ? task.getMediaTypes() : "");
                        if (!mediaDownload) {
                            markMediaSkipped(taskId, url);
                        }
                    }

                    updateRecursiveProgress(taskId, pagesProcessed.get(), mediaFound.get(),
                            url, depth, myRunToken);

                    riskControlService.throttle(url);
                },
                () -> queueService.getControl(taskId) == null
                        && (myRunToken == null || myRunToken.equals(queueService.getRunToken(taskId))));

        log.info("递归爬取完成 urlsVisited={} pagesProcessed={} mediaFound={} finished={}",
                result.getUrlsVisited(), result.getPagesProcessed(), mediaFound.get(), result.isFinished());
    }

    /**
     * 把指定页面来源的媒体标记为 SKIPPED（用户未开启下载时）
     */
    private void markMediaSkipped(Long taskId, String sourceUrl) {
        try {
            mediaMapper.update(null,
                    new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.freecollector.server.module.crawler.entity.CrawlMedia>()
                            .eq(com.freecollector.server.module.crawler.entity.CrawlMedia::getTaskId, taskId)
                            .eq(com.freecollector.server.module.crawler.entity.CrawlMedia::getSourceUrl, sourceUrl)
                            .eq(com.freecollector.server.module.crawler.entity.CrawlMedia::getDownloadStatus, "PENDING")
                            .set(com.freecollector.server.module.crawler.entity.CrawlMedia::getDownloadStatus, "SKIPPED"));
        } catch (Exception e) {
            log.debug("媒体 SKIPPED 标记失败（忽略）: {}", e.getMessage());
        }
    }

    /**
     * 更新递归爬取进度（比 savePage 更轻量，不更新断点游标）
     */
    private void updateRecursiveProgress(Long taskId, int pagesProcessed, int mediaFound,
                                         String currentUrl, int depth, String runToken) {
        if (runToken != null && !runToken.equals(queueService.getRunToken(taskId))) return;

        CrawlTask update = new CrawlTask();
        update.setId(taskId);
        update.setSuccessCount(pagesProcessed);
        update.setFailCount(0);
        update.setTotalCount(pagesProcessed);
        taskMapper.updateById(update);

        TaskRuntimeVO runtime = new TaskRuntimeVO();
        runtime.setTaskId(taskId);
        runtime.setStatus("RUNNING");
        runtime.setCurrentPage(pagesProcessed);
        runtime.setMaxPages(0);
        runtime.setSuccessCount(pagesProcessed);
        runtime.setFailCount(0);
        runtime.setBatchNo("");
        runtime.setMessage(String.format("depth=%d processed=%d media=%d url=%s",
                depth, pagesProcessed, mediaFound,
                currentUrl.length() > 80 ? currentUrl.substring(0, 80) + "..." : currentUrl));
        queueService.saveRuntime(runtime);
    }

    /** 读取字段规则的辅助方法 */
    private List<ExtractRule> rules(Long taskId) {
        return loadRules(taskId);
    }
}
