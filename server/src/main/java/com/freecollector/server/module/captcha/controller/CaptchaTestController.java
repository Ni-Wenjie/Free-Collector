package com.freecollector.server.module.captcha.controller;

import com.freecollector.server.common.Result;
import com.freecollector.server.module.captcha.factory.CaptchaStrategyFactory;
import com.freecollector.server.module.captcha.model.CaptchaConfigModel;
import com.freecollector.server.module.captcha.model.CaptchaSolveRequest;
import com.freecollector.server.module.captcha.model.CaptchaSolveResult;
import com.freecollector.server.module.captcha.service.CaptchaGatewayService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 打码适配器测试接口（模块三联调用）
 * 本模块不落库，方案持久化与UI在模块七高阶配置面板实现
 */
@RestController
@RequestMapping("/api/captcha")
@RequiredArgsConstructor
public class CaptchaTestController {

    private final CaptchaGatewayService captchaGatewayService;
    private final CaptchaStrategyFactory strategyFactory;

    /**
     * 测试打码：请求体包含 config 与 request 两部分
     * 示例：
     * {
     *   "config": {
     *     "configName": "测试方案",
     *     "apiUrl": "https://platform.example.com/api/upload",
     *     "httpMethod": "POST",
     *     "requestHeaders": "{\"Authorization\":\"Bearer {{api_key}}\"}",
     *     "paramTemplate": "{\"image\":\"{{image_base64}}\"}",
     *     "resultJsonPath": "$.data.result",
     *     "apiKey": "your-key",
     *     "timeoutMs": 15000
     *   },
     *   "request": { "imageBase64": "iVBORw0..." }
     * }
     */
    @PostMapping("/test/solve")
    public Result<CaptchaSolveResult> testSolve(@RequestBody CaptchaTestPayload payload) {
        if (payload.getConfig() == null) {
            return Result.fail(com.freecollector.server.common.ErrorCode.PARAM_ERROR, "config 不能为空");
        }
        return Result.ok(captchaGatewayService.solveBlocking(payload.getConfig(), payload.getRequest()));
    }

    /** 已注册的打码策略类型，供高阶面板下拉选择 */
    @GetMapping("/strategies")
    public Result<List<String>> strategies() {
        return Result.ok(strategyFactory.registeredTypes());
    }

    /** 测试请求体包装 */
    @Data
    public static class CaptchaTestPayload {
        private CaptchaConfigModel config;
        private CaptchaSolveRequest request = new CaptchaSolveRequest();
    }
}
