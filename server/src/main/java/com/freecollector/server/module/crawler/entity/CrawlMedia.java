package com.freecollector.server.module.crawler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 媒体资源发现与下载记录，对应表 crawl_media
 *
 * 用途：
 *  - 自动发现：扫描页面中所有图片/视频/音频/文档的URL
 *  - 下载管理：记录下载状态、本地路径、失败信息
 */
@Data
@TableName("crawl_media")
public class CrawlMedia {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 执行批次号 */
    private String batchNo;

    /** 媒体来源页面URL（该媒体在哪个页面被发现） */
    private String sourceUrl;

    /** 媒体资源直接URL */
    private String mediaUrl;

    /** 媒体类型：IMAGE / VIDEO / AUDIO / DOCUMENT / OTHER */
    private String mediaType;

    /** 文件扩展名（从URL或Content-Type推断） */
    private String fileExt;

    /** 文件大小（字节） */
    private Long fileSize;

    /** 本地存储绝对路径 */
    private String localPath;

    /**
     * 下载状态：
     * PENDING     待下载
     * DOWNLOADING 下载中
     * SUCCESS     已下载
     * FAILED      下载失败
     * SKIPPED     跳过（已存在/不在下载类型列表内）
     */
    private String downloadStatus;

    /** 下载失败时的错误信息 */
    private String errorMsg;

    /** 媒体URL的SHA-256去重指纹 */
    private String dedupHash;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
