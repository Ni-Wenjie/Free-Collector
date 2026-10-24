package com.freecollector.server.module.crawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * URL队列表（递归爬取的frontier），对应表 crawl_url_queue
 *
 * 用途：
 *  - 存储待爬取的URL队列
 *  - 支持断点续爬：任务重启时从队列中恢复未处理URL
 *  - 去重：通过 url_hash 唯一索引防止同一URL重复入队
 */
@Data
@TableName("crawl_url_queue")
public class CrawlUrlQueue {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 待爬取URL */
    private String url;

    /** 来源页面URL（哪个页面发现了这个链接） */
    private String parentUrl;

    /** 当前URL距离起始URL的深度（起始页=0） */
    private Integer depth;

    /** URL标准化后的SHA-256（去重用） */
    private String urlHash;

    /**
     * 状态：
     * PENDING    待处理
     * PROCESSING 处理中
     * DONE       已完成
     * SKIPPED    已跳过
     * FAILED     失败
     */
    private String status;

    /** 优先级（深度小的优先，同深度先入先出） */
    private Integer priority;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
