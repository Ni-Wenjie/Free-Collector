package com.freecollector.server.module.task.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采集字段规则实体，对应表 crawl_field
 */
@Data
@TableName("crawl_field")
public class CrawlField {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 字段名称（导出列名） */
    private String fieldName;

    /** 字段类型：TEXT / IMAGE / LINK / LIST / PAGINATION / CUSTOM */
    private String fieldType;

    /** 定位方式：AUTO / CSS / XPATH / TEXT */
    private String locatorType;

    /** 定位表达式 */
    private String locatorExpression;

    /** 提取目标：text / href / src / 属性名 */
    private String extractAttr;

    /** 框选示例值 */
    private String sampleValue;

    /** 字段顺序 */
    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
