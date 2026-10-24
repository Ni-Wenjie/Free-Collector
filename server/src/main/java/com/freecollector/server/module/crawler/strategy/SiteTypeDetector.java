package com.freecollector.server.module.crawler.strategy;

import com.microsoft.playwright.Page;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Set;

/**
 * 网站类型检测器：根据 URL 特征 + 页面 DOM 特征判定网站类型
 *
 * 判定由快到慢：先域名匹配，再路径关键词，再 DOM 特征，最后兜底 GENERIC。
 */
@Slf4j
@Component
public class SiteTypeDetector {

    /** 视频站域名特征 */
    private static final Set<String> VIDEO_DOMAINS = Set.of(
            "bilibili.com", "youtube.com", "youtu.be", "iqiyi.com", "youku.com",
            "tudou.com", "mgtv.com", "qq.com/video", "ixigua.com", "douyin.com",
            "kuaishou.com", "jkan.app", "v.qq.com", "video.taobao.com"
    );

    /** 图片站域名特征 */
    private static final Set<String> IMAGE_DOMAINS = Set.of(
            "pinterest.com", "unsplash.com", "pexels.com", "pixabay.com",
            "500px.com", "smugmug.com", "imgur.com", "deviantart.com"
    );

    /** 论坛社区域名特征 */
    private static final Set<String> FORUM_DOMAINS = Set.of(
            "zhihu.com", "tieba.baidu.com", "bbs.", "forum.", "reddit.com",
            "v2ex.com", "douban.com/group"
    );

    /** 电商域名特征 */
    private static final Set<String> ECOMMERCE_DOMAINS = Set.of(
            "taobao.com", "tmall.com", "jd.com", "amazon.", "ebay.",
            "pinduoduo.com", "xiaohongshu.com", "dewu.com"
    );

    /** SPA 框架特征（从页面 script 中检测） */
    private static final Set<String> SPA_INDICATORS = Set.of(
            "id=\"app\"", "id=\"__next\"", "id=\"nuxt\"",
            "data-reactroot", "__NEXT_DATA__", "__NUXT__"
    );

    /**
     * 先根据 URL 预判，再根据页面 DOM 特征精确判定
     *
     * @param page 已加载完成的页面
     * @return 判定的网站类型
     */
    public SiteType detect(Page page) {
        String url = page.url();
        String host = extractHost(url);

        SiteType byDomain = detectByDomain(host);
        if (byDomain != null) {
            log.debug("网站类型判定：域名匹配 host={} → {}", host, byDomain);
            return byDomain;
        }

        SiteType byPath = detectByPath(url);
        if (byPath != null) {
            log.debug("网站类型判定：路径匹配 url={} → {}", url, byPath);
            return byPath;
        }

        SiteType byDom = detectByDom(page);
        if (byDom != null) {
            log.debug("网站类型判定：DOM特征匹配 → {}", byDom);
            return byDom;
        }

        log.debug("网站类型判定：兜底 GENERIC url={}", url);
        return SiteType.GENERIC;
    }

    /**
     * 仅根据 URL 快速预判（不需要打开页面）
     * 用于任务创建时的策略预览
     */
    public SiteType detectByUrl(String url) {
        String host = extractHost(url);
        SiteType byDomain = detectByDomain(host);
        if (byDomain != null) return byDomain;
        SiteType byPath = detectByPath(url);
        if (byPath != null) return byPath;
        return SiteType.GENERIC;
    }

    private SiteType detectByDomain(String host) {
        if (host == null) return null;
        String h = host.toLowerCase();

        for (String d : VIDEO_DOMAINS) {
            if (h.contains(d)) return SiteType.VIDEO_SITE;
        }
        for (String d : IMAGE_DOMAINS) {
            if (h.contains(d)) return SiteType.IMAGE_GALLERY;
        }
        for (String d : ECOMMERCE_DOMAINS) {
            if (h.contains(d)) return SiteType.ECOMMERCE;
        }
        for (String d : FORUM_DOMAINS) {
            if (h.contains(d)) return SiteType.FORUM;
        }
        return null;
    }

    private SiteType detectByPath(String url) {
        if (url == null) return null;
        String u = url.toLowerCase();

        if (u.contains("/video") || u.contains("/play") || u.contains("/v/")
                || u.contains(".m3u8") || u.contains(".mp4")) {
            return SiteType.VIDEO_SITE;
        }
        if (u.contains("/gallery") || u.contains("/image") || u.contains("/photo")
                || u.contains("/pic")) {
            return SiteType.IMAGE_GALLERY;
        }
        if (u.contains("/forum") || u.contains("/thread") || u.contains("/topic")
                || u.contains("/post")) {
            return SiteType.FORUM;
        }
        return null;
    }

    private SiteType detectByDom(Page page) {
        try {
            // 检测 video 标签数量
            int videoCount = page.locator("video").count();
            int sourceCount = page.locator("video source").count();
            if (videoCount > 0 || sourceCount > 0) {
                // 有 video 标签 + 大量 script = 视频站
                boolean hasM3u8 = page.content().toLowerCase().contains(".m3u8");
                if (hasM3u8 || videoCount >= 2) {
                    return SiteType.VIDEO_SITE;
                }
            }

            // 检测 SPA 特征
            String body = page.content();
            for (String indicator : SPA_INDICATORS) {
                if (body.contains(indicator)) {
                    return SiteType.SPA;
                }
            }

            // 检测图片比例
            int imgCount = page.locator("img").count();
            if (imgCount > 20) {
                return SiteType.IMAGE_GALLERY;
            }

            // 检测是否有 article 标签（文档/博客站特征）
            int articleCount = page.locator("article").count();
            if (articleCount > 0) {
                return SiteType.DOCUMENT;
            }
        } catch (Exception e) {
            log.debug("DOM 特征检测失败（忽略）: {}", e.getMessage());
        }
        return null;
    }

    private String extractHost(String url) {
        try {
            URI uri = URI.create(url);
            return uri.getHost();
        } catch (Exception e) {
            return null;
        }
    }
}
