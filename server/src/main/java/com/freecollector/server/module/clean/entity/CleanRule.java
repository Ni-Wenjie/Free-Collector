package com.freecollector.server.module.clean.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 数据清洗规则实体，对应表 clean_rule
 *
 * 规则按 sort_order 串行执行，作用于导出/预览阶段，不回写原始采集数据，
 * 保证 crawl_result 中的原始数据永远可追溯。
 */
@Data
@TableName("clean_rule")
public class CleanRule {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 所属任务ID */
    private Long taskId;

    /** 目标字段名；NULL 表示作用于整条记录（如整体去重、剔除空行） */
    private String fieldName;

    /** 规则类型：DEDUP去重 / REMOVE_EMPTY剔除空值 / SUBSTRING文本截取 / REPLACE文本替换 / REGEX正则（高阶） */
    private String ruleType;

    /** 规则参数（JSON字符串，按 ruleType 解析） */
    private String ruleParam;

    /** 执行顺序，清洗规则按序串行执行 */
    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
