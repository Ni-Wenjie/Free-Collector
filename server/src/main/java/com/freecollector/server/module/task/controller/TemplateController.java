package com.freecollector.server.module.task.controller;

import com.freecollector.server.common.Result;
import com.freecollector.server.module.task.service.TemplateService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 任务模板接口：列表 / 导入为任务 / 导出 / 删除
 */
@RestController
@RequestMapping("/api/template")
@RequiredArgsConstructor
public class TemplateController {

    private final TemplateService templateService;

    /** 模板列表 */
    @GetMapping("/list")
    public Result<List<Map<String, Object>>> list() {
        return Result.ok(templateService.list());
    }

    /** 从模板导入为新任务，返回新任务ID */
    @PostMapping("/{id}/import")
    public Result<Long> importToTask(@PathVariable Long id) {
        return Result.ok(templateService.importToTask(id));
    }

    /** 导出模板内容（JSON） */
    @GetMapping("/{id}/export")
    public Result<Map<String, Object>> export(@PathVariable Long id) {
        return Result.ok(templateService.export(id));
    }

    /** 删除模板 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        templateService.delete(id);
        return Result.ok();
    }
}
