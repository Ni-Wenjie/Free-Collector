package com.freecollector.server.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采集任务实体，对应表 crawl_task
 *
 * 安全说明：
 *  - proxy_url_encrypted / cookie_encrypted 只存 AES 密文，
 *    写入前由 AesCryptoUtil.encrypt() 加密，读取执行时再解密；
 *  - 接口返回给前端时永不返回密文与明文，只返回"是否已配置"标记。
 */
@Data
@TableName("crawl_task")
public class CrawlTask {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 任务名称 */
    private String taskName;

    /** 目标起始网址（仅 http/https） */
    private String targetUrl;

    /**
     * 爬取模式：
     * FLAT         扁平翻页（默认，点击下一页）
     * RECURSIVE    递归爬取（跟随页面链接深度遍历）
     * MEDIA_ONLY   仅媒体下载（自动发现页面内的图片/视频/文档）
     */
    private String crawlMode;

    /** 递归爬取最大深度（FLAT模式固定1） */
    private Integer maxDepth;

    /** 递归爬取最多访问URL数上限 */
    private Integer maxUrls;

    /** URL包含正则（白名单），仅匹配的URL才会入队 */
    private String urlIncludePattern;

    /** URL排除正则（黑名单），优先级高于include */
    private String urlExcludePattern;

    /** 是否限制同域：1只爬取起始域名下的URL 0允许跨域 */
    private Integer domainRestrict;

    /** 自动发现开关：1自动扫描页面媒体资源 0仅提取规则内字段 */
    private Integer autoDiscover;

    /** 媒体下载开关：1自动下载发现的媒体文件 0只记录URL */
    private Integer mediaDownload;

    /** 要下载的媒体类型列表（逗号分隔） */
    private String mediaTypes;

    /** 任务状态：DRAFT / READY / RUNNING / PAUSED / STOPPED / FINISHED / FAILED */
    private String status;

    /** 访问速度：SLOW / NORMAL / FAST */
    private String speedLevel;

    /** 模拟真人浏览：0关 1开 */
    private Integer simulateHuman;

    /** 自动轮换UA：0关 1开 */
    private Integer rotateUa;

    /** 代理地址（AES密文） */
    private String proxyUrlEncrypted;

    /** 高阶参数 JSON（模块五用于保存 maxPages 等运行参数） */
    private String advancedConfig;

    /** 自定义 Cookie（AES密文） */
    private String cookieEncrypted;

    /** 绑定的打码方案ID */
    private Long captchaConfigId;

    /** 断点游标：下一次从第几页继续采集（字符串存储） */
    private String checkpointCursor;

    /** 累计条数 */
    private Integer totalCount;

    /** 成功条数 */
    private Integer successCount;

    /** 失败条数 */
    private Integer failCount;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 逻辑删除：0正常 1已删除 */
    @TableLogic
    private Integer deleted;
}
