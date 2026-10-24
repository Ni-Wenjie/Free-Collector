package com.freecollector.server.module.crawler.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单页采集结果
 */
@Data
public class PageExtractResult {

    /** 实际采集页面地址（可能被重定向） */
    private String pageUrl;

    /** 页面标题 */
    private String title;

    /** 各字段提取值：key=字段名，value=多值列表 */
    private Map<String, List<String>> fieldValues = new LinkedHashMap<>();

    /** 按行组装后的数据：列表页多商品时按索引对齐 */
    private List<Map<String, String>> rows = new ArrayList<>();

    /** 分页识别结果 */
    private PaginationInfo pagination = PaginationInfo.none();

    /** 提取过程告警（字段未找到等），不影响整体返回 */
    private List<String> warnings = new ArrayList<>();

    /** 耗时毫秒 */
    private long elapsedMs;

    /** 失败重试次数 */
    private int retryUsed;
}
