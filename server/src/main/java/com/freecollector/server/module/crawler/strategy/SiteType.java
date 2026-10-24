package com.freecollector.server.module.crawler.strategy;

/**
 * 网站类型枚举：由 SiteTypeDetector 根据 URL 和页面特征自动判定
 *
 * 各类型对应不同爬取策略：
 * STATIC_HTML 纯静态HTML（博客/新闻站）—— 标准CSS选择器提取
 * SPA         单页应用（Vue/React等）—— 需等待异步渲染完成
 * VIDEO_SITE  视频站（B站/YouTube等）—— 重点解析 m3u8/mp4/播放源
 * IMAGE_GALLERY 图片画廊（Pinterest/Unsplash等）—— 重点提取大图URL
 * FORUM       论坛（知乎/贴吧等）—— 列表→详情→翻页三层结构
 * ECOMMERCE   电商（淘宝/京东等）—— 商品列表→商品详情
 * DOCUMENT    文档站（CSDN/博客园等）—— 文章列表→全文
 * GENERIC     通用兜底—— 按默认策略处理
 */
public enum SiteType {

    /** 纯静态HTML页面 */
    STATIC_HTML,

    /** 单页应用（Vue/React/Angular） */
    SPA,

    /** 视频站 */
    VIDEO_SITE,

    /** 图片画廊 */
    IMAGE_GALLERY,

    /** 论坛社区 */
    FORUM,

    /** 电商平台 */
    ECOMMERCE,

    /** 文档/博客站 */
    DOCUMENT,

    /** 通用兜底 */
    GENERIC
}
