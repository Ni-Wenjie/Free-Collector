package com.freecollector.server.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采集结果实体，对应表 crawl_result
 * 本模块只负责写入；清洗与导出由模块六处理
 */
@Data
@TableName("crawl_result")
public class CrawlResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 执行批次号 */
    private String batchNo;

    /** 数据来源页面URL */
    private String pageUrl;

    /** 采集结果JSON：字段名 → 值 */
    private String dataJson;

    /** 去重指纹（规范化JSON的SHA-256） */
    private String dedupHash;

    private LocalDateTime createTime;
}
