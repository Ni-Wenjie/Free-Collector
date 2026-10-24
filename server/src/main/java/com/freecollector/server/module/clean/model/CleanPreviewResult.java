package com.freecollector.server.module.clean.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 清洗预览结果：展示清洗前后的对比与统计
 */
@Data
public class CleanPreviewResult {

    /** 参与预览的原始行数 */
    private int totalRows;

    /** 清洗后剩余行数（DEDUP/REMOVE_EMPTY 可能减少行数） */
    private int cleanedRows;

    /** 被剔除的行数 */
    private int removedRows;

    /** 清洗前样本（最多 sampleSize 行） */
    private List<Map<String, String>> before;

    /** 清洗后样本 */
    private List<Map<String, String>> after;

    /** 规则执行告警（如正则写错、参数缺失） */
    private List<String> warnings;
}
