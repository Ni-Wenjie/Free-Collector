package com.freecollector.server.module.crawler.engine;

import com.freecollector.server.module.crawler.entity.CrawlMedia;
import com.freecollector.server.module.crawler.mapper.CrawlMediaMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 媒体下载管理器：并发下载 + 重试 + 状态追踪
 *
 * 并发度可配置（默认3线程），每个下载任务独立重试（默认2次）。
 * 文件按 taskId/batchNo/mediaType 组织目录，状态实时写入 crawl_media 表。
 */
@Slf4j
@Component
public class DownloadManager {

    /** 下载根目录（用户主目录下） */
    private static final String DOWNLOAD_ROOT = System.getProperty("user.home")
            + java.io.File.separator + "free_collector_media";

    /** 默认下载超时毫秒 */
    private static final int DOWNLOAD_TIMEOUT_MS = 30000;

    /** 默认最大重试次数 */
    private static final int DEFAULT_RETRY = 2;

    /** 默认并发线程数 */
    private static final int DEFAULT_CONCURRENCY = 3;

    private final CrawlMediaMapper mediaMapper;

    /** 下载线程池（独立于爬虫线程池，避免互相阻塞） */
    private ExecutorService downloadExecutor;

    /** 正在下载/等待下载的URL集合（防并发重复提交） */
    private final Set<String> inflight = new HashSet<>();

    public DownloadManager(CrawlMediaMapper mediaMapper) {
        this.mediaMapper = mediaMapper;
    }

    /**
     * 提交一批媒体下载任务（异步执行）
     *
     * @param taskId      任务ID
     * @param batchNo     批次号
     * @param mediaCands  媒体候选列表（来自页面发现）
     * @param mediaTypes  允许下载的类型（逗号分隔，如 "image,video"）
     * @return 本次新入库的媒体记录数
     */
    public int submitBatch(Long taskId, String batchNo,
                           List<? extends com.freecollector.server.module.crawler.strategy.CrawlStrategy.MediaCandidate> mediaCands,
                           String mediaTypes) {
        if (mediaCands == null || mediaCands.isEmpty()) return 0;
        Set<String> allowed = parseMediaTypes(mediaTypes);
        int submitted = 0;

        for (var cand : mediaCands) {
            // 过滤不允许的类型
            if (!allowed.isEmpty() && !allowed.contains(cand.getMediaType().toLowerCase())) {
                continue;
            }

            String mediaUrl = cand.getMediaUrl();
            if (mediaUrl == null || !mediaUrl.startsWith("http")) continue;

            // 生成去重哈希
            String hash = hashUrl(mediaUrl);

            // 检查是否已入库（数据库唯一索引兜底）
            CrawlMedia existing = mediaMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrawlMedia>()
                            .eq(CrawlMedia::getTaskId, taskId)
                            .eq(CrawlMedia::getMediaUrl, mediaUrl.length() > 500 ? mediaUrl.substring(0, 500) : mediaUrl));
            if (existing != null) continue;

            // 入库 PENDING（不下载的话状态会是 SKIPPED）
            CrawlMedia media = new CrawlMedia();
            media.setTaskId(taskId);
            media.setBatchNo(batchNo);
            // 优先使用来源页面URL，回退到 sourceElement（标签名）
            media.setSourceUrl(cand.getSourcePageUrl() != null ? cand.getSourcePageUrl() : cand.getSourceElement());
            media.setMediaUrl(mediaUrl);
            media.setMediaType(cand.getMediaType());
            media.setFileExt(inferExt(mediaUrl));
            media.setDownloadStatus("PENDING");
            media.setDedupHash(hash);
            try {
                mediaMapper.insert(media);
                submitted++;
            } catch (Exception e) {
                log.debug("媒体入库失败（可能已存在）: {}", e.getMessage());
            }
        }

        log.info("提交下载任务 taskId={} newMedia={} typeFilter={}", taskId, submitted, mediaTypes);
        return submitted;
    }

    /**
     * 同步等待所有 PENDING 任务完成（递归爬取结束后调用）
     *
     * @param taskId       任务ID
     * @param concurrency  并发线程数
     */
    public void drainAndWait(Long taskId, int concurrency) {
        int threads = concurrency > 0 ? concurrency : DEFAULT_CONCURRENCY;
        ExecutorService exec = Executors.newFixedThreadPool(threads,
                r -> new Thread(r, "media-download-" + taskId));

        List<CrawlMedia> pendings = mediaMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CrawlMedia>()
                        .eq(CrawlMedia::getTaskId, taskId)
                        .eq(CrawlMedia::getDownloadStatus, "PENDING"));

        if (pendings.isEmpty()) {
            exec.shutdown();
            return;
        }

        log.info("开始下载 {} 个媒体文件 taskId={} threads={}", pendings.size(), taskId, threads);
        AtomicInteger success = new AtomicInteger(0);
        AtomicInteger failed = new AtomicInteger(0);

        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (CrawlMedia media : pendings) {
            CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                try {
                    downloadOne(media, taskId);
                    success.incrementAndGet();
                } catch (Exception e) {
                    failed.incrementAndGet();
                }
            }, exec);
            futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        exec.shutdown();
        log.info("媒体下载完成 taskId={} success={} failed={}", taskId, success.get(), failed.get());
    }

    /**
     * 下载单个媒体文件
     */
    private void downloadOne(CrawlMedia media, Long taskId) {
        String mediaUrl = media.getMediaUrl();

        // 更新状态为 DOWNLOADING
        updateStatus(media.getId(), "DOWNLOADING", null, null, null);

        Exception lastError = null;
        for (int attempt = 0; attempt <= DEFAULT_RETRY; attempt++) {
            try {
                if (attempt > 0) {
                    log.debug("媒体下载重试 attempt={} url={}", attempt, mediaUrl);
                    Thread.sleep(1000L * attempt);
                }

                HttpURLConnection conn = (HttpURLConnection) URI.create(mediaUrl).toURL().openConnection();
                conn.setConnectTimeout(DOWNLOAD_TIMEOUT_MS);
                conn.setReadTimeout(DOWNLOAD_TIMEOUT_MS);
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; FreeCollector/1.0)");
                conn.setInstanceFollowRedirects(true);

                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IOException("HTTP " + code);
                }

                // 获取内容长度
                long contentLength = conn.getContentLengthLong();

                // 构造本地路径
                Path localPath = buildLocalPath(taskId, media, mediaUrl);
                Files.createDirectories(localPath.getParent());

                // 下载
                try (InputStream is = conn.getInputStream()) {
                    Files.copy(is, localPath,
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }

                // 回填文件大小
                long actualSize = Files.size(localPath);
                updateStatus(media.getId(), "SUCCESS", localPath.toString(), actualSize, null);
                log.info("媒体下载成功 url={} size={} path={}", mediaUrl, actualSize, localPath);
                return;

            } catch (Exception e) {
                lastError = e;
                // 清理可能残留的临时文件
            }
        }

        // 全部重试失败
        String errMsg = lastError != null ? lastError.getMessage() : "未知错误";
        updateStatus(media.getId(), "FAILED", null, null, errMsg);
        log.warn("媒体下载失败 url={} error={}", mediaUrl, errMsg);
    }

    /** 构造本地存储路径 */
    private Path buildLocalPath(Long taskId, CrawlMedia media, String mediaUrl) {
        String batchNo = media.getBatchNo() != null ? media.getBatchNo() : "unknown";
        String type = media.getMediaType().toLowerCase();
        String ext = media.getFileExt() != null ? media.getFileExt() : inferExt(mediaUrl);
        if (ext == null || ext.isBlank()) ext = "bin";
        if (!ext.startsWith(".")) ext = "." + ext;

        String fileName = hashUrl(mediaUrl) + ext;
        return Paths.get(DOWNLOAD_ROOT, String.valueOf(taskId), batchNo, type, fileName);
    }

    private void updateStatus(Long mediaId, String status, String localPath,
                              Long fileSize, String errorMsg) {
        try {
            CrawlMedia update = new CrawlMedia();
            update.setId(mediaId);
            update.setDownloadStatus(status);
            if (localPath != null) update.setLocalPath(localPath);
            if (fileSize != null) update.setFileSize(fileSize);
            if (errorMsg != null) update.setErrorMsg(errorMsg);
            mediaMapper.updateById(update);
        } catch (Exception e) {
            log.debug("媒体状态更新失败: {}", e.getMessage());
        }
    }

    private Set<String> parseMediaTypes(String mediaTypes) {
        Set<String> allowed = new HashSet<>();
        if (mediaTypes == null || mediaTypes.isBlank()) {
            allowed.add("image");
            allowed.add("video");
            return allowed;
        }
        for (String t : mediaTypes.split(",")) {
            String trimmed = t.trim().toLowerCase();
            if (!trimmed.isEmpty()) allowed.add(trimmed);
        }
        return allowed;
    }

    private String inferExt(String url) {
        try {
            int qIdx = url.indexOf('?');
            String path = qIdx >= 0 ? url.substring(0, qIdx) : url;
            int dotIdx = path.lastIndexOf('.');
            if (dotIdx >= 0 && dotIdx < path.length() - 1) {
                String ext = path.substring(dotIdx + 1).toLowerCase();
                // 只取常见扩展名
                if (ext.length() <= 5 && ext.matches("[a-z0-9]+")) {
                    return ext;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String hashUrl(String url) {
        if (url == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return String.valueOf(url.hashCode());
        }
    }
}
