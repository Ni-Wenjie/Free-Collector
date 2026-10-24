package com.freecollector.server.module.risk.controller;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.common.Result;
import com.freecollector.server.module.risk.model.RiskSettings;
import com.freecollector.server.module.risk.service.RiskControlService;
import com.freecollector.server.module.risk.service.RobotsTxtService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 合规风控接口
 *
 * 说明：所有检查均在本机完成，不上报任何数据到外部服务。
 */
@RestController
@RequestMapping("/api/risk")
@RequiredArgsConstructor
public class RiskController {

    private final RiskControlService riskControlService;

    /** 读取风控设置 */
    @GetMapping("/settings")
    public Result<RiskSettings> getSettings() {
        return Result.ok(riskControlService.getSettings());
    }

    /** 更新风控设置（robots 校验开关 / 限速开关 / 最小间隔） */
    @PutMapping("/settings")
    public Result<RiskSettings> updateSettings(@RequestBody RiskSettings request) {
        return Result.ok(riskControlService.updateSettings(request));
    }

    /** 单条 URL 合规预检：请求体 {"url": "https://..."} */
    @PostMapping("/check-url")
    public Result<RobotsTxtService.Decision> checkUrl(@RequestBody Map<String, String> body) {
        String url = body == null ? null : body.get("url");
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "请求体缺少 url 字段");
        }
        return Result.ok(riskControlService.checkUrl(url));
    }

    /** 查询免责声明确认状态 */
    @GetMapping("/disclaimer")
    public Result<Map<String, Object>> disclaimerStatus() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("confirmed", riskControlService.isDisclaimerConfirmed());
        return Result.ok(data);
    }

    /** 确认免责声明 */
    @PostMapping("/disclaimer/confirm")
    public Result<Void> confirmDisclaimer() {
        riskControlService.confirmDisclaimer();
        return Result.ok();
    }
}
