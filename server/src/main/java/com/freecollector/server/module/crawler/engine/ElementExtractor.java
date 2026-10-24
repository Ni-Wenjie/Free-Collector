package com.freecollector.server.module.crawler.engine;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.freecollector.server.module.crawler.model.ExtractRule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 元素提取器：把可视化框选得到的规则转换为实际数据
 *
 * 支持的定位方式：
 *  - CSS   ：.product-item .title
 *  - XPATH ：//div[@class='title']
 *  - TEXT  ：按可见文本精确匹配
 *  - AUTO  ：自动判断（/ 开头按XPath，其余按CSS）
 */
@Slf4j
@Component
public class ElementExtractor {

    /**
     * 提取所有字段
     *
     * @param page     页面
     * @param rules    字段规则
     * @param maxItems 每字段最大条数
     * @param warnings 告警收集器
     * @return 字段名 → 多值列表（保持字段顺序）
     */
    public Map<String, List<String>> extract(Page page, List<ExtractRule> rules,
                                             int maxItems, List<String> warnings) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        if (rules == null || rules.isEmpty()) {
            return result;
        }

        // 按 sortOrder 排序，保证导出列顺序稳定
        List<ExtractRule> sorted = new ArrayList<>(rules);
        sorted.sort(Comparator.comparingInt(ExtractRule::getSortOrder));

        for (ExtractRule rule : sorted) {
            String fieldName = rule.getFieldName() == null || rule.getFieldName().isBlank()
                    ? "未命名字段" : rule.getFieldName();
            List<String> values = extractField(page, rule, maxItems, warnings);
            result.put(fieldName, values);
            if (values.isEmpty()) {
                warnings.add("字段【" + fieldName + "】未找到匹配元素，请检查框选规则");
            }
        }
        return result;
    }

    /**
     * 提取单个字段
     */
    private List<String> extractField(Page page, ExtractRule rule, int maxItems, List<String> warnings) {
        List<String> values = new ArrayList<>();
        if (rule.getLocatorExpression() == null || rule.getLocatorExpression().isBlank()) {
            warnings.add("字段【" + rule.getFieldName() + "】定位表达式为空");
            return values;
        }

        String selector = buildSelector(rule);
        try {
            Locator locator = page.locator(selector);
            int count = Math.min(locator.count(), maxItems);
            for (int i = 0; i < count; i++) {
                Locator item = locator.nth(i);
                String value = extractValue(item, rule.getExtractAttr());
                if (value != null && !value.isBlank()) {
                    values.add(value.trim());
                }
            }
        } catch (Exception e) {
            // 单个字段失败不影响其他字段，收集告警供监控面板展示
            warnings.add("字段【" + rule.getFieldName() + "】提取异常: " + e.getMessage());
            log.debug("字段提取异常 selector={}", selector, e);
        }
        return values;
    }

    /**
     * 根据提取目标取值：text 取可见文本，其余取指定属性
     */
    private String extractValue(Locator item, String extractAttr) {
        String attr = extractAttr == null || extractAttr.isBlank() ? "text" : extractAttr.trim();
        if ("text".equalsIgnoreCase(attr)) {
            return item.innerText();
        }
        return item.getAttribute(attr);
    }

    /**
     * 组装 Playwright 选择器
     */
    private String buildSelector(ExtractRule rule) {
        String expr = rule.getLocatorExpression().trim();
        String type = rule.getLocatorType() == null ? "AUTO" : rule.getLocatorType().trim().toUpperCase();

        switch (type) {
            case "XPATH":
                return expr.startsWith("xpath=") ? expr : "xpath=" + expr;
            case "TEXT":
                // 加引号表示精确文本匹配，避免匹配到包含该文本的长段落
                return expr.startsWith("text=") ? expr
                        : "text=\"" + expr.replace("\"", "\\\"") + "\"";
            case "CSS":
                return expr;
            case "AUTO":
            default:
                if (expr.startsWith("/") || expr.startsWith("xpath=")) {
                    return expr.startsWith("xpath=") ? expr : "xpath=" + expr;
                }
                if (expr.startsWith("text=")) {
                    return expr;
                }
                return expr;
        }
    }
}
