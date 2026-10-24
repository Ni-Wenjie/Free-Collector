package com.freecollector.server.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.task.entity.CrawlField;
import com.freecollector.server.module.task.entity.CrawlTask;
import com.freecollector.server.module.task.entity.TaskTemplate;
import com.freecollector.server.module.task.mapper.CrawlFieldMapper;
import com.freecollector.server.module.task.mapper.CrawlTaskMapper;
import com.freecollector.server.module.task.mapper.TaskTemplateMapper;
import com.freecollector.server.module.task.model.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务模板服务：把任务配置保存为快照，支持导入/导出复用
 *
 * 安全说明：模板中的代理地址与 Cookie 保留 AES 密文，不出现明文；
 *           因此跨机器导入时敏感配置无法解密，需要重新填写（已知限制）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TemplateService {

    private final TaskTemplateMapper templateMapper;
    private final CrawlTaskMapper taskMapper;
    private final CrawlFieldMapper fieldMapper;
    private final ObjectMapper objectMapper;

    /**
     * 把现有任务保存为模板
     */
    @Transactional(rollbackFor = Exception.class)
    public Long saveFromTask(Long taskId, String templateName, String remark) {
        if (templateName == null || templateName.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "模板名称不能为空");
        }
        CrawlTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在或已删除");
        }

        ObjectNode root = objectMapper.createObjectNode();
        root.put("version", 1);

        ObjectNode t = root.putObject("task");
        t.put("taskName", task.getTaskName());
        t.put("targetUrl", task.getTargetUrl());
        t.put("speedLevel", task.getSpeedLevel());
        t.put("simulateHuman", task.getSimulateHuman());
        t.put("rotateUa", task.getRotateUa());
        t.put("advancedConfig", task.getAdvancedConfig());
        t.put("remark", task.getRemark());
        // 敏感配置只保留密文，模板文件中不出现明文
        t.put("proxyUrlEncrypted", task.getProxyUrlEncrypted());
        t.put("cookieEncrypted", task.getCookieEncrypted());

        ArrayNode fields = root.putArray("fields");
        for (CrawlField field : loadFields(taskId)) {
            ObjectNode f = fields.addObject();
            f.put("fieldName", field.getFieldName());
            f.put("fieldType", field.getFieldType());
            f.put("locatorType", field.getLocatorType());
            f.put("locatorExpression", field.getLocatorExpression());
            f.put("extractAttr", field.getExtractAttr());
            f.put("sortOrder", field.getSortOrder());
        }

        TaskTemplate entity = new TaskTemplate();
        entity.setTemplateName(templateName.trim());
        entity.setSourceTaskId(taskId);
        entity.setTemplateJson(root.toString());
        entity.setRemark(remark);
        templateMapper.insert(entity);
        log.info("任务模板已保存 id={} fromTask={}", entity.getId(), taskId);
        return entity.getId();
    }

    /** 模板列表 */
    public List<Map<String, Object>> list() {
        List<TaskTemplate> templates = templateMapper.selectList(
                new LambdaQueryWrapper<TaskTemplate>().orderByDesc(TaskTemplate::getId));
        List<Map<String, Object>> result = new ArrayList<>();
        for (TaskTemplate template : templates) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", template.getId());
            item.put("templateName", template.getTemplateName());
            item.put("sourceTaskId", template.getSourceTaskId());
            item.put("remark", template.getRemark());
            item.put("createTime", template.getCreateTime());
            item.put("fieldCount", countFields(template.getTemplateJson()));
            result.add(item);
        }
        return result;
    }

    /**
     * 从模板导入为新任务（草稿状态，需用户确认后再启动）
     */
    @Transactional(rollbackFor = Exception.class)
    public Long importToTask(Long templateId) {
        TaskTemplate template = requireTemplate(templateId);
        try {
            JsonNode root = objectMapper.readTree(template.getTemplateJson());
            JsonNode t = root.path("task");

            CrawlTask task = new CrawlTask();
            task.setTaskName(t.path("taskName").asText("未命名任务") + "（导入）");
            task.setTargetUrl(t.path("targetUrl").asText());
            if (!task.getTargetUrl().startsWith("http://") && !task.getTargetUrl().startsWith("https://")) {
                throw new BizException(ErrorCode.PARAM_ERROR, "模板中的网址不合法");
            }
            task.setSpeedLevel(t.path("speedLevel").asText("SLOW"));
            task.setSimulateHuman(t.path("simulateHuman").asInt(0));
            task.setRotateUa(t.path("rotateUa").asInt(0));
            task.setAdvancedConfig(t.path("advancedConfig").asText(null));
            task.setRemark(t.path("remark").asText(null));
            task.setProxyUrlEncrypted(t.path("proxyUrlEncrypted").asText(null));
            task.setCookieEncrypted(t.path("cookieEncrypted").asText(null));
            task.setStatus(TaskStatus.DRAFT);
            task.setTotalCount(0);
            task.setSuccessCount(0);
            task.setFailCount(0);
            taskMapper.insert(task);

            int index = 0;
            for (JsonNode f : root.path("fields")) {
                CrawlField field = new CrawlField();
                field.setTaskId(task.getId());
                field.setFieldName(f.path("fieldName").asText("字段" + (index + 1)));
                field.setFieldType(f.path("fieldType").asText("TEXT"));
                field.setLocatorType(f.path("locatorType").asText("AUTO"));
                field.setLocatorExpression(f.path("locatorExpression").asText(""));
                field.setExtractAttr(f.path("extractAttr").asText("text"));
                field.setSortOrder(index);
                fieldMapper.insert(field);
                index++;
            }

            log.info("模板已导入为任务 templateId={} taskId={}", templateId, task.getId());
            return task.getId();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("模板导入失败 templateId={}", templateId, e);
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模板内容格式错误，无法导入");
        }
    }

    /**
     * 导出模板内容（供前端保存为本地 JSON 文件）
     */
    public Map<String, Object> export(Long templateId) {
        TaskTemplate template = requireTemplate(templateId);
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("templateName", template.getTemplateName());
            data.put("remark", template.getRemark());
            data.put("content", objectMapper.readTree(template.getTemplateJson()));
            return data;
        } catch (Exception e) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "模板内容解析失败");
        }
    }

    /** 删除模板（逻辑删除） */
    public void delete(Long templateId) {
        requireTemplate(templateId);
        templateMapper.deleteById(templateId);
        log.info("任务模板已删除 id={}", templateId);
    }

    private TaskTemplate requireTemplate(Long id) {
        TaskTemplate template = templateMapper.selectById(id);
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "模板不存在或已删除");
        }
        return template;
    }

    private List<CrawlField> loadFields(Long taskId) {
        return fieldMapper.selectList(new LambdaQueryWrapper<CrawlField>()
                .eq(CrawlField::getTaskId, taskId)
                .orderByAsc(CrawlField::getSortOrder)
                .orderByAsc(CrawlField::getId));
    }

    private int countFields(String json) {
        try {
            return objectMapper.readTree(json).path("fields").size();
        } catch (Exception e) {
            return 0;
        }
    }
}
