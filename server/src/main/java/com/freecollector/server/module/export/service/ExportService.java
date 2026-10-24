package com.freecollector.server.module.export.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.clean.engine.CleanEngine;
import com.freecollector.server.module.clean.entity.CleanRule;
import com.freecollector.server.module.clean.mapper.CleanRuleMapper;
import com.freecollector.server.module.export.model.ExportResultInfo;
import com.freecollector.server.module.task.entity.CrawlField;
import com.freecollector.server.module.task.entity.CrawlResult;
import com.freecollector.server.module.task.entity.CrawlTask;
import com.freecollector.server.module.task.mapper.CrawlFieldMapper;
import com.freecollector.server.module.task.mapper.CrawlResultMapper;
import com.freecollector.server.module.task.mapper.CrawlTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 数据导出服务：把任务采集结果导出为 CSV / JSON / XLSX 文件
 *
 * 导出文件写到用户主目录下的 free_collector_exports 文件夹，接口返回文件路径。
 * CSV 带 UTF-8 BOM，XLSX 使用 SXSSF 流式写出。可选择是否应用清洗规则，
 * 原始数据本身不被修改。列顺序优先取任务字段规则，额外字段追加在最后。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ExportService {

    /** 单次导出最大行数保护，防止误操作拉爆内存 */
    private static final int MAX_EXPORT_ROWS = 100_000;

    private final CrawlTaskMapper taskMapper;
    private final CrawlFieldMapper fieldMapper;
    private final CrawlResultMapper resultMapper;
    private final CleanRuleMapper cleanRuleMapper;
    private final CleanEngine cleanEngine;
    private final ObjectMapper objectMapper;

    /**
     * 执行导出
     *
     * @param taskId     任务ID
     * @param format     csv / json / xlsx
     * @param applyClean 是否应用清洗规则
     * @return 导出文件信息
     */
    public ExportResultInfo export(Long taskId, String format, boolean applyClean) {
        CrawlTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在或已删除");
        }
        String normalizedFormat = normalizeFormat(format);

        List<Map<String, String>> rows = loadRows(taskId);
        if (rows.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "该任务暂无采集结果，无法导出");
        }

        List<String> warnings = new ArrayList<>();
        if (applyClean) {
            List<CleanRule> rules = cleanRuleMapper.selectList(new LambdaQueryWrapper<CleanRule>()
                    .eq(CleanRule::getTaskId, taskId)
                    .orderByAsc(CleanRule::getSortOrder)
                    .orderByAsc(CleanRule::getId));
            if (!rules.isEmpty()) {
                int before = rows.size();
                rows = cleanEngine.apply(rows, rules, warnings);
                log.info("导出前清洗 taskId={} 行数 {} → {}", taskId, before, rows.size());
            }
        }

        List<String> headers = resolveHeaders(taskId, rows);

        Path exportDir = ensureExportDir();
        String fileName = buildFileName(task.getTaskName(), normalizedFormat);
        Path file = exportDir.resolve(fileName);
        try {
            switch (normalizedFormat) {
                case "csv" -> writeCsv(file, headers, rows);
                case "json" -> writeJson(file, rows);
                case "xlsx" -> writeXlsx(file, headers, rows);
                default -> throw new BizException(ErrorCode.PARAM_ERROR, "不支持的导出格式：" + format);
            }
        } catch (IOException e) {
            log.error("导出文件写入失败 taskId={} file={}", taskId, file, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "导出文件写入失败：" + e.getMessage());
        }

        log.info("导出完成 taskId={} format={} rows={} file={}", taskId, normalizedFormat, rows.size(), file);

        ExportResultInfo info = new ExportResultInfo();
        info.setFilePath(file.toAbsolutePath().toString());
        info.setFileName(fileName);
        info.setFormat(normalizedFormat);
        info.setRows(rows.size());
        info.setCleaned(applyClean);
        info.setWarnings(warnings);
        return info;
    }

    /** 加载任务结果（带行数上限保护），解析 dataJson 为行 */
    private List<Map<String, String>> loadRows(Long taskId) {
        List<CrawlResult> records = resultMapper.selectList(new LambdaQueryWrapper<CrawlResult>()
                .eq(CrawlResult::getTaskId, taskId)
                .orderByAsc(CrawlResult::getId)
                .last("LIMIT " + MAX_EXPORT_ROWS));
        List<Map<String, String>> rows = new ArrayList<>(records.size());
        for (CrawlResult record : records) {
            try {
                Map<String, String> row = new LinkedHashMap<>();
                JsonNode node = objectMapper.readTree(record.getDataJson());
                node.fields().forEachRemaining(e ->
                        row.put(e.getKey(), e.getValue().isNull() ? "" : e.getValue().asText()));
                rows.add(row);
            } catch (Exception e) {
                log.debug("结果JSON解析失败已跳过 id={}", record.getId());
            }
        }
        return rows;
    }

    /** 列顺序：任务字段规则（sortOrder）优先，数据中的额外 key 按出现顺序追加 */
    private List<String> resolveHeaders(Long taskId, List<Map<String, String>> rows) {
        List<CrawlField> fields = fieldMapper.selectList(new LambdaQueryWrapper<CrawlField>()
                .eq(CrawlField::getTaskId, taskId)
                .orderByAsc(CrawlField::getSortOrder)
                .orderByAsc(CrawlField::getId));

        Set<String> headers = new LinkedHashSet<>();
        for (CrawlField field : fields) {
            if (field.getFieldName() != null && !field.getFieldName().isBlank()) {
                headers.add(field.getFieldName());
            }
        }
        // 只保留数据中真实存在的字段 + 数据中额外出现的字段
        Set<String> dataKeys = new LinkedHashSet<>();
        rows.forEach(row -> dataKeys.addAll(row.keySet()));
        headers.retainAll(dataKeys);
        headers.addAll(dataKeys);
        return new ArrayList<>(headers);
    }

    /**
     * CSV：UTF-8 with BOM（Excel 打开不乱码）
     * 转义规则：包含 逗号/双引号/换行 的值用双引号包裹，内部双引号翻倍
     */
    private void writeCsv(Path file, List<String> headers, List<Map<String, String>> rows) throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                Files.newOutputStream(file), StandardCharsets.UTF_8))) {
            writer.write('﻿'); // BOM
            writer.write(toCsvLine(headers));
            for (Map<String, String> row : rows) {
                List<String> cells = new ArrayList<>(headers.size());
                for (String header : headers) {
                    cells.add(row.getOrDefault(header, ""));
                }
                writer.newLine();
                writer.write(toCsvLine(cells));
            }
        }
    }

    private String toCsvLine(List<String> cells) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            String value = cells.get(i) == null ? "" : cells.get(i);
            if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
                line.append('"').append(value.replace("\"", "\"\"")).append('"');
            } else {
                line.append(value);
            }
        }
        return line.toString();
    }

    /** JSON：数组格式，元素为 字段名→值 的对象 */
    private void writeJson(Path file, List<Map<String, String>> rows) throws IOException {
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), rows);
    }

    /** XLSX：SXSSF 流式写出（窗口100行），低内存 */
    private void writeXlsx(Path file, List<String> headers, List<Map<String, String>> rows) throws IOException {
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(100)) {
            Sheet sheet = workbook.createSheet("采集结果");
            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.size(); i++) {
                headerRow.createCell(i).setCellValue(headers.get(i));
            }
            int rowIndex = 1;
            for (Map<String, String> row : rows) {
                Row dataRow = sheet.createRow(rowIndex++);
                for (int i = 0; i < headers.size(); i++) {
                    dataRow.createCell(i).setCellValue(row.getOrDefault(headers.get(i), ""));
                }
            }
            try (java.io.OutputStream out = Files.newOutputStream(file)) {
                workbook.write(out);
            }
            workbook.dispose(); // 清理SXSSF临时文件
        }
    }

    /** 导出目录：用户主目录/free_collector_exports，不存在则创建 */
    private Path ensureExportDir() {
        Path dir = Paths.get(System.getProperty("user.home"), "free_collector_exports");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "无法创建导出目录：" + e.getMessage());
        }
        return dir;
    }

    /** 文件名：任务名_时间戳.扩展名（剔除Windows文件名非法字符） */
    private String buildFileName(String taskName, String format) {
        String safeName = taskName == null ? "export"
                : taskName.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (safeName.isEmpty()) {
            safeName = "export";
        }
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        return safeName + "_" + timestamp + "." + format;
    }

    private String normalizeFormat(String format) {
        String f = format == null ? "csv" : format.trim().toLowerCase();
        if (!f.equals("csv") && !f.equals("json") && !f.equals("xlsx")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "不支持的导出格式：" + format + "（支持 csv/json/xlsx）");
        }
        return f;
    }
}
