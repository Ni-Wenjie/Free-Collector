package com.freecollector.server.module.clean.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freecollector.server.module.clean.entity.CleanRule;
import com.freecollector.server.module.task.util.DedupHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 数据清洗引擎：把采集结果按规则串行处理
 *
 * 输入是原始行的副本，清洗不修改 crawl_result 中的原始数据。
 * 规则按 sort_order 升序串行执行，前一条的输出是后一条的输入。
 * 单条规则失败只记录告警并跳过，不中断整个流程。
 * DEDUP / REMOVE_EMPTY 可能减少行数，其余规则只修改字段值。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CleanEngine {

    private final ObjectMapper objectMapper;

    /**
     * 执行清洗
     *
     * @param rows     原始数据行（字段名 → 值）
     * @param rules    清洗规则（已按 sort_order 排序）
     * @param warnings 告警收集器
     * @return 清洗后的数据行
     */
    public List<Map<String, String>> apply(List<Map<String, String>> rows,
                                           List<CleanRule> rules, List<String> warnings) {
        List<Map<String, String>> current = copyRows(rows);
        if (rules == null || rules.isEmpty()) {
            return current;
        }

        for (CleanRule rule : rules) {
            try {
                current = applyOne(current, rule, warnings);
            } catch (Exception e) {
                // 单条规则异常不影响其他规则
                warnings.add("规则【" + describe(rule) + "】执行失败已跳过：" + e.getMessage());
                log.warn("清洗规则执行失败 rule={}", rule.getRuleType(), e);
            }
        }
        return current;
    }

    /** 执行单条规则 */
    private List<Map<String, String>> applyOne(List<Map<String, String>> rows,
                                               CleanRule rule, List<String> warnings) {
        String type = rule.getRuleType() == null ? "" : rule.getRuleType().trim().toUpperCase();
        switch (type) {
            case "DEDUP":
                return dedup(rows);
            case "REMOVE_EMPTY":
                return removeEmpty(rows, rule.getFieldName());
            case "SUBSTRING":
                return substring(rows, rule, warnings);
            case "REPLACE":
                return replace(rows, rule, warnings);
            case "REGEX":
                return regexReplace(rows, rule, warnings);
            default:
                warnings.add("未知规则类型【" + type + "】已跳过");
                return rows;
        }
    }

    /** 记录级去重：整行内容相同的只保留第一行（跨批次去重，补充运行内去重） */
    private List<Map<String, String>> dedup(List<Map<String, String>> rows) {
        Set<String> seen = new HashSet<>();
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (seen.add(DedupHashUtil.hash(row))) {
                result.add(row);
            }
        }
        return result;
    }

    /**
     * 剔除空值：
     *  - 指定字段：剔除该字段为空的行
     *  - 未指定字段（记录级）：剔除所有字段都为空的行
     */
    private List<Map<String, String>> removeEmpty(List<Map<String, String>> rows, String fieldName) {
        List<Map<String, String>> result = new ArrayList<>();
        for (Map<String, String> row : rows) {
            if (fieldName != null && !fieldName.isBlank()) {
                if (!isBlank(row.get(fieldName))) {
                    result.add(row);
                }
            } else {
                boolean allBlank = row.values().stream().allMatch(CleanEngine::isBlank);
                if (!allBlank) {
                    result.add(row);
                }
            }
        }
        return result;
    }

    /** 文本截取：{"start":0,"end":10}，end 省略表示到结尾；越界自动收敛 */
    private List<Map<String, String>> substring(List<Map<String, String>> rows,
                                                CleanRule rule, List<String> warnings) {
        String field = requireField(rule, warnings);
        if (field == null) {
            return rows;
        }
        JsonNode param = parseParam(rule.getRuleParam(), warnings);
        int start = Math.max(0, param.path("start").asInt(0));
        Integer end = param.hasNonNull("end") ? param.path("end").asInt() : null;

        for (Map<String, String> row : rows) {
            String value = row.get(field);
            if (isBlank(value)) {
                continue;
            }
            int from = Math.min(start, value.length());
            int to = end == null ? value.length() : Math.min(Math.max(end, from), value.length());
            row.put(field, value.substring(from, to));
        }
        return rows;
    }

    /** 普通文本替换：{"from":"￥","to":""} */
    private List<Map<String, String>> replace(List<Map<String, String>> rows,
                                              CleanRule rule, List<String> warnings) {
        String field = requireField(rule, warnings);
        if (field == null) {
            return rows;
        }
        JsonNode param = parseParam(rule.getRuleParam(), warnings);
        String from = param.path("from").asText("");
        String to = param.path("to").asText("");
        if (from.isEmpty()) {
            warnings.add("REPLACE 规则缺少 from 参数，已跳过");
            return rows;
        }
        for (Map<String, String> row : rows) {
            String value = row.get(field);
            if (!isBlank(value)) {
                row.put(field, value.replace(from, to));
            }
        }
        return rows;
    }

    /** 正则替换（高阶）：{"pattern":"(\\d+)","replace":"$1"}，支持捕获组引用 */
    private List<Map<String, String>> regexReplace(List<Map<String, String>> rows,
                                                   CleanRule rule, List<String> warnings) {
        String field = requireField(rule, warnings);
        if (field == null) {
            return rows;
        }
        JsonNode param = parseParam(rule.getRuleParam(), warnings);
        String patternText = param.path("pattern").asText("");
        String replacement = param.path("replace").asText("");
        if (patternText.isEmpty()) {
            warnings.add("REGEX 规则缺少 pattern 参数，已跳过");
            return rows;
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            warnings.add("REGEX 规则正则表达式无效：" + e.getMessage());
            return rows;
        }
        for (Map<String, String> row : rows) {
            String value = row.get(field);
            if (!isBlank(value)) {
                row.put(field, pattern.matcher(value).replaceAll(replacement));
            }
        }
        return rows;
    }

    /** 深拷贝行数据，避免清洗污染原始结果 */
    private List<Map<String, String>> copyRows(List<Map<String, String>> rows) {
        List<Map<String, String>> copy = new ArrayList<>(rows.size());
        for (Map<String, String> row : rows) {
            copy.add(new LinkedHashMap<>(row));
        }
        return copy;
    }

    private String requireField(CleanRule rule, List<String> warnings) {
        if (rule.getFieldName() == null || rule.getFieldName().isBlank()) {
            warnings.add(rule.getRuleType() + " 规则未指定目标字段，已跳过");
            return null;
        }
        return rule.getFieldName();
    }

    private JsonNode parseParam(String param, List<String> warnings) {
        if (param == null || param.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(param);
        } catch (Exception e) {
            warnings.add("规则参数不是合法JSON，按空参数处理");
            return objectMapper.createObjectNode();
        }
    }

    private String describe(CleanRule rule) {
        return rule.getRuleType() + (rule.getFieldName() == null ? "" : "(" + rule.getFieldName() + ")");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
