package com.freecollector.server.module.crawler.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 采集字段规则（对应可视化框选解析结果，数据库 crawl_field 的运行时载体）
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ExtractRule {

    /** 字段名称，导出时的列名 */
    private String fieldName;

    /** 字段类型：TEXT / IMAGE / LINK / LIST / PAGINATION / CUSTOM */
    private String fieldType = "TEXT";

    /** 定位方式：AUTO（自动判断）/ CSS / XPATH / TEXT */
    private String locatorType = "AUTO";

    /** 定位表达式，如 .product-item .title 或 //div[@class='title'] */
    private String locatorExpression;

    /** 提取目标：text 文本 / href 链接 / src 图片地址 / 其他属性名 */
    private String extractAttr = "text";

    /** 排序，控制导出列顺序 */
    private int sortOrder = 0;
}
