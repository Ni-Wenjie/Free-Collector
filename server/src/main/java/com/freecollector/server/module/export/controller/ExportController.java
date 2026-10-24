package com.freecollector.server.module.export.controller;

import com.freecollector.server.common.Result;
import com.freecollector.server.module.export.model.ExportResultInfo;
import com.freecollector.server.module.export.service.ExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 数据导出接口
 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExportService exportService;

    /**
     * 导出任务采集结果到本机文件
     *
     * @param id         任务ID
     * @param format     csv / json / xlsx，默认 csv
     * @param applyClean 是否应用清洗规则，默认 true
     */
    @GetMapping("/task/{id}")
    public Result<ExportResultInfo> export(@PathVariable Long id,
                                           @RequestParam(defaultValue = "csv") String format,
                                           @RequestParam(defaultValue = "true") boolean applyClean) {
        return Result.ok(exportService.export(id, format, applyClean));
    }
}
