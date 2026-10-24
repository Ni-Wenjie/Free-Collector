package com.freecollector.server.module.task.controller;

import com.freecollector.server.common.Result;
import com.freecollector.server.module.task.dto.TaskDetailVO;
import com.freecollector.server.module.task.dto.TaskRuntimeVO;
import com.freecollector.server.module.task.dto.TaskSaveRequest;
import com.freecollector.server.module.task.dto.TemplateSaveRequest;
import com.freecollector.server.module.task.service.TaskService;
import com.freecollector.server.module.task.service.TemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 任务管理接口：CRUD + 调度控制（启动/暂停/继续/终止）
 */
@RestController
@RequestMapping("/api/task")
@RequiredArgsConstructor
public class TaskController {

    private final TaskService taskService;
    private final TemplateService templateService;

    /** 任务分页列表 */
    @GetMapping("/list")
    public Result<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status) {
        return Result.ok(taskService.list(page, size, keyword, status));
    }

    /** 任务详情（含字段规则） */
    @GetMapping("/{id}")
    public Result<TaskDetailVO> detail(@PathVariable Long id) {
        return Result.ok(taskService.detail(id));
    }

    /** 新建/编辑任务，返回任务ID */
    @PostMapping
    public Result<Long> save(@RequestBody @Valid TaskSaveRequest request) {
        return Result.ok(taskService.save(request));
    }

    /** 删除任务 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return Result.ok();
    }

    /** 启动任务（从第1页开始，入队） */
    @PostMapping("/{id}/start")
    public Result<Void> start(@PathVariable Long id) {
        taskService.start(id);
        return Result.ok();
    }

    /** 暂停任务 */
    @PostMapping("/{id}/pause")
    public Result<Void> pause(@PathVariable Long id) {
        taskService.pause(id);
        return Result.ok();
    }

    /** 继续任务（断点续爬） */
    @PostMapping("/{id}/resume")
    public Result<Void> resume(@PathVariable Long id) {
        taskService.resume(id);
        return Result.ok();
    }

    /** 终止任务 */
    @PostMapping("/{id}/stop")
    public Result<Void> stop(@PathVariable Long id) {
        taskService.stop(id);
        return Result.ok();
    }

    /** 运行状态（Redis 实时快照优先） */
    @GetMapping("/{id}/status")
    public Result<TaskRuntimeVO> status(@PathVariable Long id) {
        return Result.ok(taskService.runtimeStatus(id));
    }

    /** 采集结果分页 */
    @GetMapping("/{id}/results")
    public Result<Map<String, Object>> results(
            @PathVariable Long id,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(taskService.results(id, page, size));
    }

    /** 把任务保存为模板 */
    @PostMapping("/{id}/save-template")
    public Result<Long> saveTemplate(@PathVariable Long id,
                                     @RequestBody @Valid TemplateSaveRequest request) {
        return Result.ok(templateService.saveFromTask(id, request.getTemplateName(), request.getRemark()));
    }
}
