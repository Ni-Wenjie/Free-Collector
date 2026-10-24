package com.freecollector.server.module.crawler.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 行数据组装工具：把"字段名 → 多值列表"按索引合并为多行记录
 *
 * 例：标题[3条] + 链接[3条] → 3行；某字段数量不足则该列留空
 */
public final class RowAssembler {

    private RowAssembler() {
    }

    public static List<Map<String, String>> assemble(Map<String, List<String>> fieldValues) {
        List<Map<String, String>> rows = new ArrayList<>();
        int maxSize = fieldValues.values().stream().mapToInt(List::size).max().orElse(0);
        for (int i = 0; i < maxSize; i++) {
            Map<String, String> row = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : fieldValues.entrySet()) {
                List<String> values = entry.getValue();
                row.put(entry.getKey(), i < values.size() ? values.get(i) : null);
            }
            rows.add(row);
        }
        return rows;
    }
}
