package com.freecollector.server.module.captcha.factory;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.captcha.strategy.CaptchaSolverStrategy;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 打码策略工厂：按 strategyType 选择实现
 * 新增平台适配器只需实现 CaptchaSolverStrategy 并注册为 Bean，此处自动收集
 */
@Component
public class CaptchaStrategyFactory {

    private final Map<String, CaptchaSolverStrategy> strategies = new HashMap<>();

    public CaptchaStrategyFactory(List<CaptchaSolverStrategy> strategyList) {
        for (CaptchaSolverStrategy strategy : strategyList) {
            strategies.put(strategy.getType().toUpperCase(), strategy);
        }
    }

    /**
     * 获取策略；未指定类型时默认通用HTTP转发
     */
    public CaptchaSolverStrategy get(String type) {
        String key = (type == null || type.isBlank()) ? "HTTP_FORWARD" : type.trim().toUpperCase();
        CaptchaSolverStrategy strategy = strategies.get(key);
        if (strategy == null) {
            throw new BizException(ErrorCode.CAPTCHA_ERROR, "不支持的打码策略类型: " + type);
        }
        return strategy;
    }

    /** 已注册策略列表，高阶面板展示用 */
    public List<String> registeredTypes() {
        return strategies.keySet().stream().sorted().toList();
    }
}
