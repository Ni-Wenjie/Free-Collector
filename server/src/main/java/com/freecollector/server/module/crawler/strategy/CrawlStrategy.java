package com.freecollector.server.module.crawler.strategy;

import com.microsoft.playwright.Page;

import java.util.List;
import java.util.Map;

/**
 * 爬取策略接口：定义针对不同网站类型的差异化处理逻辑
 *
 * 设计思路：
 *  - 策略模式 —— SiteTypeDetector 先判定网站类型，再选择对应策略实例
 *  - 接口精简 —— 递归爬取引擎只调用接口方法，不关心具体实现
 *  - 可扩展 —— 新增网站类型只需加一个 SiteType 枚举值和一个 Strategy 实现类
 *
 * 典型实现：
 *  - GenericCrawlStrategy    通用兜底策略（所有类型都能跑）
 *  - VideoSiteStrategy      视频站（重点处理 m3u8/mp4/播放源解析）
 *  - ImageGalleryStrategy   图片画廊（重点提取大图原始URL）
 */
public interface CrawlStrategy {

    /**
     * 获取该策略对应的网站类型
     */
    SiteType getSiteType();

    /**
     * 页面就绪后的额外准备动作（如展开折叠区域、触发懒加载等）
     *
     * @param page 当前页面
     */
    default void preparePage(Page page) {
        // 默认无动作，子类按需覆盖
    }

    /**
     * 从页面中发现可跟进的链接（用于递归爬取）
     *
     * @param page  当前页面
     * @param depth 当前页面深度
     * @return 链接列表，每项包含 url 和可选的 title
     */
    List<LinkCandidate> discoverLinks(Page page, int depth);

    /**
     * 从页面中发现媒体资源（图片/视频/音频/文档）
     *
     * @param page 当前页面
     * @return 媒体候选列表
     */
    List<MediaCandidate> discoverMedia(Page page);

    /**
     * 策略级别的数据提取（在用户自定义字段规则之外补充自动提取的结构化数据）
     *
     * @param page 当前页面
     * @return 补充的键值数据（如视频标题、作者、播放量等），无补充则返回空Map
     */
    default Map<String, String> extractSupplementalData(Page page) {
        return Map.of();
    }

    /**
     * 链接候选
     */
    class LinkCandidate {
        private final String url;
        private final String title;

        public LinkCandidate(String url, String title) {
            this.url = url;
            this.title = title;
        }

        public String getUrl() { return url; }
        public String getTitle() { return title; }
    }

    /**
     * 媒体候选
     */
    class MediaCandidate {
        private final String mediaUrl;
        private final String mediaType;
        private final String sourceElement;
        /** 来源页面URL（该媒体在哪个页面被发现，可能为null由外部补充） */
        private String sourcePageUrl;

        public MediaCandidate(String mediaUrl, String mediaType, String sourceElement) {
            this.mediaUrl = mediaUrl;
            this.mediaType = mediaType;
            this.sourceElement = sourceElement;
        }

        public String getMediaUrl() { return mediaUrl; }
        public String getMediaType() { return mediaType; }
        public String getSourceElement() { return sourceElement; }
        public String getSourcePageUrl() { return sourcePageUrl; }
        public void setSourcePageUrl(String sourcePageUrl) { this.sourcePageUrl = sourcePageUrl; }
    }
}
