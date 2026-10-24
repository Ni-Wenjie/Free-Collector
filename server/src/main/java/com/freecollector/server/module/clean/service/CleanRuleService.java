package com.freecollector.server.module.clean.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.clean.engine.CleanEngine;
import com.freecollector.server.module.clean.entity.CleanRule;
import com.freecollector.server.module.clean.mapper.CleanRuleMapper;
import com.freecollector.server.module.clean.model.CleanPreviewResult;
import com.freecollector.server.module.clean.model.CleanRuleDTO;
import com.freecollector.server.module.task.entity.CrawlResult;
import com.freecollector.server.module.task.entity.CrawlTask;
import com.freecollector.server.module.task.mapper.CrawlResultMapper;
import com.freecollector.server.module.task.mapper.CrawlTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 数据清洗服务：清洗规则 CRUD + 清洗预览
 *
 * 清洗只作用于导出/预览阶段的数据副本，原始采集数据永不修改。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CleanRuleService {

    /** 预览采样的最大行数 */
    private static final int PREVIEW_SAMPLE_SIZE = 10;
    /** 参与预览计算的最大行数（防止超大任务全量加载） */
    private static final int PREVIEW_MAX_ROWS = 500;

    private final CleanRuleMapper cleanRuleMapper;
    private final CrawlTaskMapper taskMapper;
    private final CrawlResultMapper resultMapper;
    private final CleanEngine cleanEngine;
    private final ObjectMapper objectMapper;

    /**
     * 查询任务的清洗规则（按执行顺序）
     */
    public List<CleanRule> list(Long taskId) {
        requireTask(taskId);
        return cleanRuleMapper.selectList(new LambdaQueryWrapper<CleanRule>()
                .eq(CleanRule::getTaskId, taskId)
                .orderByAsc(CleanRule::getSortOrder)
                .orderByAsc(CleanRule::getId));
    }

    /**
     * 整体保存任务的清洗规则：先逻辑删除旧规则，再按传入顺序插入新规则
     */
    @Transactional(rollbackFor = Exception.class)
    public void saveAll(Long taskId, List<CleanRuleDTO> rules) {
        requireTask(taskId);
        cleanRuleMapper.delete(new LambdaQueryWrapper<CleanRule>().eq(CleanRule::getTaskId, taskId));

        if (rules == null) {
            return;
        }
        int index = 0;
        for (CleanRuleDTO dto : rules) {
            validate(dto, index);
            CleanRule entity = new CleanRule();
            entity.setTaskId(taskId);
            entity.setFieldName(dto.getFieldName() == null || dto.getFieldName().isBlank()
                    ? null : dto.getFieldName().trim());
            entity.setRuleType(dto.getRuleType().trim().toUpperCase());
            entity.setRuleParam(dto.getRuleParam());
            entity.setSortOrder(index);
            cleanRuleMapper.insert(entity);
            index++;
        }
        log.info("清洗规则已保存 taskId={} count={}", taskId, index);
    }

    /**
     * 清洗预览：对任务已有结果采样执行清洗，返回前后对比
     */
    public CleanPreviewResult preview(Long taskId, List<CleanRuleDTO> ruleDtos) {
        requireTask(taskId);

        // 把 DTO 转为临时规则实体（不落库），未传则用已保存的规则
        List<CleanRule> rules = new ArrayList<>();
        if (ruleDtos != null) {
            int index = 0;
            for (CleanRuleDTO dto : ruleDtos) {
                CleanRule entity = new CleanRule();
                entity.setTaskId(taskId);
                entity.setFieldName(dto.getFieldName());
                entity.setRuleType(dto.getRuleType());
                entity.setRuleParam(dto.getRuleParam());
                entity.setSortOrder(index++);
                rules.add(entity);
            }
        } else {
            rules = list(taskId);
        }

        // 采样加载结果
        List<CrawlResult> records = resultMapper.selectList(new LambdaQueryWrapper<CrawlResult>()
                .eq(CrawlResult::getTaskId, taskId)
                .orderByAsc(CrawlResult::getId)
                .last("LIMIT " + PREVIEW_MAX_ROWS));
        List<Map<String, String>> rows = parseRows(records);

        List<String> warnings = new ArrayList<>();
        List<Map<String, String>> cleaned = cleanEngine.apply(rows, rules, warnings);

        CleanPreviewResult result = new CleanPreviewResult();
        result.setTotalRows(rows.size());
        result.setCleanedRows(cleaned.size());
        result.setRemovedRows(rows.size() - cleaned.size());
        result.setBefore(rows.subList(0, Math.min(PREVIEW_SAMPLE_SIZE, rows.size())));
        result.setAfter(cleaned.subList(0, Math.min(PREVIEW_SAMPLE_SIZE, cleaned.size())));
        result.setWarnings(warnings);
        return result;
    }

    /** 校验单条规则（保存时调用，非法直接失败） */
    private void validate(CleanRuleDTO dto, int index) {
        String label = "第" + (index + 1) + "条规则";
        if (dto.getRuleType() == null || dto.getRuleType().isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "：规则类型不能为空");
        }
        String type = dto.getRuleType().trim().toUpperCase();
        switch (type) {
            case "DEDUP":
            case "REMOVE_EMPTY":
                break;
            case "SUBSTRING":
            case "REPLACE":
            case "REGEX":
                if (dto.getFieldName() == null || dto.getFieldName().isBlank()) {
                    throw new BizException(ErrorCode.PARAM_ERROR, label + "：" + type + " 规则必须指定目标字段");
                }
                validateParam(type, dto.getRuleParam(), label);
                break;
            default:
                throw new BizException(ErrorCode.PARAM_ERROR,
                        label + "：不支持的规则类型 " + type + "（支持 DEDUP/REMOVE_EMPTY/SUBSTRING/REPLACE/REGEX）");
        }
    }

    /** 校验规则参数为合法JSON，REGEX 额外校验表达式可编译 */
    private void validateParam(String type, String param, String label) {
        if (param == null || param.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "：缺少规则参数");
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(param);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PARAM_ERROR, label + "：规则参数不是合法JSON");
        }
        if ("REGEX".equals(type)) {
            String pattern = node.path("pattern").asText("");
            try {
                Pattern.compile(pattern);
            } catch (PatternSyntaxException e) {
                throw new BizException(ErrorCode.PARAM_ERROR, label + "：正则表达式无效 - " + e.getMessage());
            }
        }
    }

    /** 把结果记录的 dataJson 解析为行 */
    private List<Map<String, String>> parseRows(List<CrawlResult> records) {
        List<Map<String, String>> rows = new ArrayList<>();
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

    private void requireTask(Long taskId) {
        CrawlTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在或已删除");
        }
    }
}
