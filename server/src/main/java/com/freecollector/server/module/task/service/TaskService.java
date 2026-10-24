package com.freecollector.server.module.task.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.crawler.model.ExtractRule;
import com.freecollector.server.module.risk.service.RiskControlService;
import com.freecollector.server.module.task.config.TaskProperties;
import com.freecollector.server.module.task.dto.TaskDetailVO;
import com.freecollector.server.module.task.dto.TaskListItemVO;
import com.freecollector.server.module.task.dto.TaskRuntimeVO;
import com.freecollector.server.module.task.dto.TaskSaveRequest;
import com.freecollector.server.module.task.entity.CrawlField;
import com.freecollector.server.module.task.entity.CrawlResult;
import com.freecollector.server.module.task.entity.CrawlTask;
import com.freecollector.server.module.task.mapper.CrawlFieldMapper;
import com.freecollector.server.module.task.mapper.CrawlResultMapper;
import com.freecollector.server.module.task.mapper.CrawlTaskMapper;
import com.freecollector.server.module.task.model.TaskStatus;
import com.freecollector.server.util.AesCryptoUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务管理服务：任务 CRUD、启动/暂停/继续/终止、状态查询、结果查询
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TaskService {

    private final CrawlTaskMapper taskMapper;
    private final CrawlFieldMapper fieldMapper;
    private final CrawlResultMapper resultMapper;
    private final TaskQueueService queueService;
    private final TaskProperties taskProperties;
    private final ObjectMapper objectMapper;
    private final com.freecollector.server.module.captcha.mapper.CaptchaConfigMapper captchaConfigMapper;
    private final RiskControlService riskControlService;

    /**
     * 任务分页列表
     *
     * @param page    页码（从1开始）
     * @param size    每页条数
     * @param keyword 任务名称/网址模糊搜索
     * @param status  状态过滤，空表示全部
     */
    public Map<String, Object> list(int page, int size, String keyword, String status) {
        LambdaQueryWrapper<CrawlTask> wrapper = new LambdaQueryWrapper<>();
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(CrawlTask::getTaskName, keyword.trim())
                    .or().like(CrawlTask::getTargetUrl, keyword.trim()));
        }
        if (status != null && !status.isBlank()) {
            wrapper.eq(CrawlTask::getStatus, status.trim());
        }
        wrapper.orderByDesc(CrawlTask::getId);

        Page<CrawlTask> result = taskMapper.selectPage(new Page<>(page, size), wrapper);
        List<TaskListItemVO> items = new ArrayList<>();
        for (CrawlTask task : result.getRecords()) {
            items.add(TaskListItemVO.from(task, readMaxPages(task)));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", items);
        data.put("total", result.getTotal());
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    /** 任务详情（含字段规则） */
    public TaskDetailVO detail(Long id) {
        CrawlTask task = requireTask(id);
        TaskDetailVO vo = TaskDetailVO.from(task);
        vo.setMaxPages(readMaxPages(task));
        vo.setAdvancedConfig(readAdvancedConfigMap(task));
        vo.setFields(loadRules(id));
        return vo;
    }

    /**
     * 任务运行状态：优先读 Redis 实时快照，没有则回落到数据库
     */
    public TaskRuntimeVO runtimeStatus(Long id) {
        CrawlTask task = requireTask(id);
        TaskRuntimeVO runtime = queueService.getRuntime(id);
        if (runtime != null) {
            return runtime;
        }
        TaskRuntimeVO vo = new TaskRuntimeVO();
        vo.setTaskId(id);
        vo.setStatus(task.getStatus());
        vo.setMaxPages(readMaxPages(task));
        vo.setCurrentPage(parsePage(task.getCheckpointCursor()));
        vo.setSuccessCount(task.getSuccessCount() == null ? 0 : task.getSuccessCount());
        vo.setFailCount(task.getFailCount() == null ? 0 : task.getFailCount());
        vo.setMessage("等待运行");
        return vo;
    }

    /** 采集结果分页查询（data_json 由前端解析展示） */
    public Map<String, Object> results(Long taskId, int page, int size) {
        requireTask(taskId);
        Page<CrawlResult> result = resultMapper.selectPage(new Page<>(page, size),
                new LambdaQueryWrapper<CrawlResult>()
                        .eq(CrawlResult::getTaskId, taskId)
                        .orderByDesc(CrawlResult::getId));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("records", result.getRecords());
        data.put("total", result.getTotal());
        data.put("page", page);
        data.put("size", size);
        return data;
    }

    /**
     * 新建或编辑任务
     */
    @Transactional(rollbackFor = Exception.class)
    public Long save(TaskSaveRequest request) {
        validateUrl(request.getTargetUrl());
        validateProxy(request.getProxyUrl());
        if (request.getFields() == null || request.getFields().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请至少添加一个采集字段");
        }
        int maxPages = request.getMaxPages() == null ? taskProperties.getDefaultMaxPages()
                : Math.max(1, Math.min(request.getMaxPages(), 100));

        CrawlTask task;
        if (request.getId() == null) {
            task = new CrawlTask();
            task.setStatus(TaskStatus.DRAFT);
            task.setTotalCount(0);
            task.setSuccessCount(0);
            task.setFailCount(0);
        } else {
            task = requireTask(request.getId());
            // 运行中的任务禁止修改，避免规则与执行不一致
            if (TaskStatus.RUNNING.equals(task.getStatus()) || TaskStatus.READY.equals(task.getStatus())) {
                throw new BizException(ErrorCode.FORBIDDEN, "任务正在运行或排队中，请先暂停或终止后再编辑");
            }
        }

        task.setTaskName(request.getTaskName().trim());
        task.setTargetUrl(request.getTargetUrl().trim());
        task.setSpeedLevel(normalizeSpeed(request.getSpeedLevel()));
        task.setSimulateHuman(request.isSimulateHuman() ? 1 : 0);
        task.setRotateUa(request.isRotateUa() ? 1 : 0);
        task.setRemark(request.getRemark());
        // 绑定打码方案前校验存在性（高阶能力，普通模式为 null）
        if (request.getCaptchaConfigId() != null
                && captchaConfigMapper.selectById(request.getCaptchaConfigId()) == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "所选打码方案不存在或已删除");
        }
        task.setCaptchaConfigId(request.getCaptchaConfigId());
        task.setAdvancedConfig(mergeAdvancedConfig(task.getAdvancedConfig(), maxPages, request.getAdvancedConfig()));

        // 代理与 Cookie 仅在传入时更新：null 表示保持原值，空串表示清除
        if (request.getProxyUrl() != null) {
            task.setProxyUrlEncrypted(request.getProxyUrl().isBlank()
                    ? "" : AesCryptoUtil.encrypt(request.getProxyUrl().trim()));
        }
        if (request.getCookie() != null) {
            task.setCookieEncrypted(request.getCookie().isBlank()
                    ? "" : AesCryptoUtil.encrypt(request.getCookie().trim()));
        }

        if (request.getId() == null) {
            taskMapper.insert(task);
        } else {
            taskMapper.updateById(task);
            // updateById 默认忽略 null 字段，"解除打码方案绑定"需显式置空才能落库
            if (request.getCaptchaConfigId() == null) {
                taskMapper.update(null, Wrappers.<CrawlTask>lambdaUpdate()
                        .eq(CrawlTask::getId, task.getId())
                        .set(CrawlTask::getCaptchaConfigId, null));
            }
            // 字段整体替换：先逻辑删除旧字段，再插入新字段
            fieldMapper.delete(new LambdaQueryWrapper<CrawlField>()
                    .eq(CrawlField::getTaskId, request.getId()));
        }

        insertFields(task.getId(), request.getFields());
        log.info("任务已保存 id={} name={} maxPages={}", task.getId(), task.getTaskName(), maxPages);
        return task.getId();
    }

    /**
     * 删除任务（逻辑删除）。运行中/排队中禁止删除。
     */
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        CrawlTask task = requireTask(id);
        if (!TaskStatus.canDelete(task.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "任务正在运行或排队中，请先终止后再删除");
        }
        taskMapper.deleteById(id);
        fieldMapper.delete(new LambdaQueryWrapper<CrawlField>().eq(CrawlField::getTaskId, id));
        queueService.deleteRuntime(id);
        log.info("任务已删除 id={}", id);
    }

    /** 启动任务：重置进度，从第1页开始，入队等待执行 */
    public void start(Long id) {
        CrawlTask task = requireTask(id);
        if (!TaskStatus.canStart(task.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前状态不允许启动");
        }
        if (loadRules(id).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "任务没有采集字段，请先编辑添加");
        }
        // 合规风控：robots.txt 禁止时拦截启动（设置中可关闭校验，默认开启）
        riskControlService.assertAllowedToCrawl(task.getTargetUrl());

        queueService.clearControl(id);

        CrawlTask update = new CrawlTask();
        update.setId(id);
        update.setStatus(TaskStatus.READY);
        update.setCheckpointCursor("");   // 从头开始
        update.setSuccessCount(0);
        update.setFailCount(0);
        update.setTotalCount(0);
        taskMapper.updateById(update);

        // 生成新运行令牌使旧运行（如有）的收尾失效，然后再入队
        queueService.newRunToken(id);
        queueService.enqueue(id);

        TaskRuntimeVO runtime = new TaskRuntimeVO();
        runtime.setTaskId(id);
        runtime.setStatus("QUEUED");
        runtime.setMaxPages(readMaxPages(task));
        runtime.setCurrentPage(1);
        runtime.setMessage("已进入队列，等待执行");
        queueService.saveRuntime(runtime);

        log.info("任务已启动 id={}", id);
    }

    /** 暂停：工作线程在当前页结束时停下，断点保留 */
    public void pause(Long id) {
        CrawlTask task = requireTask(id);
        if (!TaskStatus.canPause(task.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前状态不允许暂停");
        }
        queueService.requestPause(id);
        updateStatus(id, TaskStatus.PAUSED);

        TaskRuntimeVO runtime = queueService.getRuntime(id);
        if (runtime == null) {
            runtime = new TaskRuntimeVO();
            runtime.setTaskId(id);
        }
        runtime.setStatus("PAUSED");
        runtime.setMessage("已暂停，等待当前页处理完成");
        queueService.saveRuntime(runtime);
        log.info("任务已请求暂停 id={}", id);
    }

    /** 继续：从断点页继续采集 */
    public void resume(Long id) {
        CrawlTask task = requireTask(id);
        if (!TaskStatus.canResume(task.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "只有已暂停的任务可以继续");
        }
        // 合规风控：续爬前重新确认 robots 允许（命中缓存开销很小）
        riskControlService.assertAllowedToCrawl(task.getTargetUrl());

        queueService.clearControl(id);

        CrawlTask update = new CrawlTask();
        update.setId(id);
        update.setStatus(TaskStatus.READY);
        taskMapper.updateById(update);

        // 生成新运行令牌：被暂停的旧运行收尾时将因令牌失效而静默退出
        queueService.newRunToken(id);
        queueService.enqueue(id);

        TaskRuntimeVO runtime = new TaskRuntimeVO();
        runtime.setTaskId(id);
        runtime.setStatus("QUEUED");
        runtime.setMaxPages(readMaxPages(task));
        runtime.setCurrentPage(parsePage(task.getCheckpointCursor()));
        runtime.setMessage("已进入队列，将从断点继续");
        queueService.saveRuntime(runtime);
        log.info("任务已继续 id={} startPage={}", id, parsePage(task.getCheckpointCursor()));
    }

    /** 终止：工作线程在当前页结束时停止，不自动继续 */
    public void stop(Long id) {
        CrawlTask task = requireTask(id);
        if (!TaskStatus.canStop(task.getStatus())) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前状态不允许终止");
        }
        queueService.requestStop(id);
        updateStatus(id, TaskStatus.STOPPED);

        TaskRuntimeVO runtime = queueService.getRuntime(id);
        if (runtime == null) {
            runtime = new TaskRuntimeVO();
            runtime.setTaskId(id);
        }
        runtime.setStatus("STOPPED");
        runtime.setMessage("已终止，等待当前页处理完成");
        queueService.saveRuntime(runtime);
        log.info("任务已请求终止 id={}", id);
    }

    private CrawlTask requireTask(Long id) {
        CrawlTask task = taskMapper.selectById(id);
        if (task == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "任务不存在或已删除");
        }
        return task;
    }

    private void validateUrl(String url) {
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "仅支持 http/https 网址");
        }
    }

    private void validateProxy(String proxy) {
        if (proxy == null || proxy.isBlank()) {
            return;
        }
        String lower = proxy.trim().toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("socks5://"))) {
            throw new BizException(ErrorCode.PARAM_ERROR, "代理地址仅支持 http/https/socks5 协议");
        }
    }

    private void insertFields(Long taskId, List<ExtractRule> rules) {
        int index = 0;
        for (ExtractRule rule : rules) {
            if (rule.getLocatorExpression() == null || rule.getLocatorExpression().isBlank()) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "字段【" + rule.getFieldName() + "】缺少定位表达式");
            }
            CrawlField field = new CrawlField();
            field.setTaskId(taskId);
            field.setFieldName(rule.getFieldName() == null || rule.getFieldName().isBlank()
                    ? "字段" + (index + 1) : rule.getFieldName().trim());
            field.setFieldType(rule.getFieldType() == null ? "TEXT" : rule.getFieldType());
            field.setLocatorType(rule.getLocatorType() == null ? "AUTO" : rule.getLocatorType());
            field.setLocatorExpression(rule.getLocatorExpression().trim());
            field.setExtractAttr(rule.getExtractAttr() == null ? "text" : rule.getExtractAttr());
            field.setSortOrder(index);
            fieldMapper.insert(field);
            index++;
        }
    }

    private List<ExtractRule> loadRules(Long taskId) {
        List<CrawlField> fields = fieldMapper.selectList(
                new LambdaQueryWrapper<CrawlField>()
                        .eq(CrawlField::getTaskId, taskId)
                        .orderByAsc(CrawlField::getSortOrder)
                        .orderByAsc(CrawlField::getId));
        List<ExtractRule> rules = new ArrayList<>();
        for (CrawlField field : fields) {
            ExtractRule rule = new ExtractRule();
            rule.setFieldName(field.getFieldName());
            rule.setFieldType(field.getFieldType());
            rule.setLocatorType(field.getLocatorType());
            rule.setLocatorExpression(field.getLocatorExpression());
            rule.setExtractAttr(field.getExtractAttr());
            rule.setSortOrder(field.getSortOrder() == null ? 0 : field.getSortOrder());
            rules.add(rule);
        }
        return rules;
    }

    /** 读取 advanced_config 中的 maxPages */
    private int readMaxPages(CrawlTask task) {
        if (task.getAdvancedConfig() == null || task.getAdvancedConfig().isBlank()) {
            return taskProperties.getDefaultMaxPages();
        }
        try {
            JsonNode node = objectMapper.readTree(task.getAdvancedConfig());
            int value = node.path("maxPages").asInt(taskProperties.getDefaultMaxPages());
            return value <= 0 ? taskProperties.getDefaultMaxPages() : value;
        } catch (Exception e) {
            return taskProperties.getDefaultMaxPages();
        }
    }

    /**
     * 合并高级配置 JSON：
     *  - maxPages 始终由表单字段独立维护，覆盖写入；
     *  - extra 为高阶面板上报的键值对（对应 AdvancedOptions 字段），整体替换旧的高阶键；
     *  - extra 为 null 表示未开启高阶配置，仅保留 maxPages（清除旧高阶键，回归默认行为）。
     */
    private String mergeAdvancedConfig(String existing, int maxPages, Map<String, Object> extra) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("maxPages", maxPages);
        if (extra != null) {
            // 高阶键整体替换：以本次上报为准，防止前端删掉的配置残留
            extra.forEach((key, value) -> {
                if (key != null && !key.isBlank() && !"maxPages".equals(key) && value != null) {
                    node.set(key, objectMapper.valueToTree(value));
                }
            });
        }
        return node.toString();
    }

    /** 读取 advanced_config 中除 maxPages 外的高阶键（供详情回显高阶面板） */
    private Map<String, Object> readAdvancedConfigMap(CrawlTask task) {
        if (task.getAdvancedConfig() == null || task.getAdvancedConfig().isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(task.getAdvancedConfig());
            Map<String, Object> map = new LinkedHashMap<>();
            node.fields().forEachRemaining(e -> {
                if (!"maxPages".equals(e.getKey())) {
                    map.put(e.getKey(), objectMapper.convertValue(e.getValue(), Object.class));
                }
            });
            return map.isEmpty() ? null : map;
        } catch (Exception e) {
            return null;
        }
    }

    private void updateStatus(Long id, String status) {
        CrawlTask update = new CrawlTask();
        update.setId(id);
        update.setStatus(status);
        taskMapper.updateById(update);
    }

    private String normalizeSpeed(String speed) {
        if (speed == null) {
            return "SLOW";
        }
        String value = speed.trim().toUpperCase();
        return ("NORMAL".equals(value) || "FAST".equals(value)) ? value : "SLOW";
    }

    private int parsePage(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return 1;
        }
        try {
            return Math.max(1, Integer.parseInt(cursor.trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }
}
