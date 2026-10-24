package com.freecollector.server.module.clean.controller;

import com.freecollector.server.common.Result;
import com.freecollector.server.module.clean.entity.CleanRule;
import com.freecollector.server.module.clean.model.CleanPreviewResult;
import com.freecollector.server.module.clean.model.CleanRuleDTO;
import com.freecollector.server.module.clean.service.CleanRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 数据清洗接口：规则 CRUD + 清洗预览
 */
@RestController
@RequestMapping("/api/clean")
@RequiredArgsConstructor
public class CleanRuleController {

    private final CleanRuleService cleanRuleService;

    /** 查询任务的清洗规则 */
    @GetMapping("/rules")
    public Result<List<CleanRule>> list(@RequestParam Long taskId) {
        return Result.ok(cleanRuleService.list(taskId));
    }

    /** 整体保存任务的清洗规则（空数组表示清空） */
    @PostMapping("/rules")
    public Result<Void> saveAll(@RequestParam Long taskId, @RequestBody(required = false) List<CleanRuleDTO> rules) {
        cleanRuleService.saveAll(taskId, rules);
        return Result.ok();
    }

    /** 清洗预览：rules 不传则用已保存的规则 */
    @PostMapping("/preview")
    public Result<CleanPreviewResult> preview(@RequestParam Long taskId,
                                              @RequestBody(required = false) List<CleanRuleDTO> rules) {
        return Result.ok(cleanRuleService.preview(taskId, rules));
    }
}
