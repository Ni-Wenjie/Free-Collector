package com.freecollector.server.module.clean.model;

import lombok.Data;

/**
 * 清洗规则 DTO（前端保存与展示共用）
 */
@Data
public class CleanRuleDTO {

    /** 目标字段名；空表示作用于整条记录 */
    private String fieldName;

    /** 规则类型：DEDUP / REMOVE_EMPTY / SUBSTRING / REPLACE / REGEX */
    private String ruleType;

    /** 规则参数（JSON字符串）：按 ruleType 解析
     *  DEDUP        ：无需参数
     *  REMOVE_EMPTY ：无需参数（字段级：剔除该字段为空的行）
     *  SUBSTRING    ：{"start":0,"end":10} end 可省略表示到结尾
     *  REPLACE      ：{"from":"￥","to":""} 普通文本替换
     *  REGEX        ：{"pattern":"(\\d+)","replace":"$1"} 正则替换（高阶）
     */
    private String ruleParam;

    /** 执行顺序 */
    private Integer sortOrder;
}
