package com.freecollector.server.module.captcha.strategy;

import com.freecollector.server.module.captcha.model.CaptchaConfigModel;
import com.freecollector.server.module.captcha.model.CaptchaSolveRequest;
import com.freecollector.server.module.captcha.model.CaptchaSolveResult;

/**
 * 打码策略接口（策略模式）
 *
 * 扩展方式：新增一个实现类并标注 @Component，工厂会自动注册；
 * 任务配置中的 strategyType 与 implementation.getType() 对应即可切换。
 */
public interface CaptchaSolverStrategy {

    /** 策略类型标识，如 HTTP_FORWARD */
    String getType();

    /**
     * 执行识别
     *
     * @param config  打码方案配置
     * @param request 待识别图片
     * @return 识别结果（失败也返回结果对象，不抛业务异常）
     */
    CaptchaSolveResult solve(CaptchaConfigModel config, CaptchaSolveRequest request);
}
