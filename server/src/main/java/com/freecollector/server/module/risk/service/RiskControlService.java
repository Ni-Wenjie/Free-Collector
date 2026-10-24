package com.freecollector.server.module.risk.service;

import com.freecollector.server.common.BizException;
import com.freecollector.server.common.ErrorCode;
import com.freecollector.server.module.risk.model.RiskSettings;
import com.freecollector.server.module.system.entity.SysConfig;
import com.freecollector.server.module.system.service.SysConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 合规风控服务
 *
 * 提供 robots.txt 校验、站点级访问限速、免责声明确认三项能力。
 * 单机本地版，风控为"提醒 + 温和限制"，所有判断在本机完成，不上报外部。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RiskControlService {

    /** 配置键：robots.txt 校验开关 */
    private static final String KEY_ROBOTS_CHECK = "risk_robots_check";
    /** 配置键：访问限速开关 */
    private static final String KEY_RATE_LIMIT = "risk_rate_limit";
    /** 配置键：同站点最小请求间隔（毫秒） */
    private static final String KEY_MIN_INTERVAL = "risk_min_interval_ms";
    /** 配置键：免责声明是否已确认 */
    private static final String KEY_DISCLAIMER = "risk_disclaimer_shown";

    /** 最小间隔下限：防止误配成 0 导致限速失效 */
    private static final int MIN_INTERVAL_FLOOR = 200;
    /** 最小间隔上限：避免设置过大导致任务长时间停滞 */
    private static final int MIN_INTERVAL_CEIL = 60000;

    private final SysConfigService sysConfigService;
    private final RobotsTxtService robotsTxtService;

    /** 各站点限速锁：同一站点串行等待，不同站点互不影响 */
    private final Map<String, Object> hostLocks = new ConcurrentHashMap<>();

    /** 读取当前风控设置（配置项不存在时使用默认值） */
    public RiskSettings getSettings() {
        RiskSettings settings = new RiskSettings();
        settings.setRobotsCheckEnabled(isEnabled(KEY_ROBOTS_CHECK, true));
        settings.setRateLimitEnabled(isEnabled(KEY_RATE_LIMIT, true));
        settings.setMinIntervalMs(readInt(KEY_MIN_INTERVAL, 1500));
        settings.setDisclaimerConfirmed(isEnabled(KEY_DISCLAIMER, false));
        return settings;
    }

    /** 更新风控设置（免责声明状态不在此接口修改） */
    public RiskSettings updateSettings(RiskSettings request) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_ERROR, "设置内容不能为空");
        }
        RiskSettings current = getSettings();

        if (request.getRobotsCheckEnabled() != null) {
            sysConfigService.upsertValueByKey(KEY_ROBOTS_CHECK,
                    request.getRobotsCheckEnabled() ? "1" : "0",
                    "robots.txt校验开关：1开启(默认) 0关闭");
            current.setRobotsCheckEnabled(request.getRobotsCheckEnabled());
            // 关闭再开启时清空缓存，确保重新读取站点最新规则
            if (request.getRobotsCheckEnabled()) {
                robotsTxtService.clearCache();
            }
        }
        if (request.getRateLimitEnabled() != null) {
            sysConfigService.upsertValueByKey(KEY_RATE_LIMIT,
                    request.getRateLimitEnabled() ? "1" : "0",
                    "访问限速开关：1开启(默认) 0关闭");
            current.setRateLimitEnabled(request.getRateLimitEnabled());
        }
        if (request.getMinIntervalMs() != null) {
            int interval = request.getMinIntervalMs();
            if (interval < MIN_INTERVAL_FLOOR || interval > MIN_INTERVAL_CEIL) {
                throw new BizException(ErrorCode.PARAM_ERROR,
                        "最小请求间隔需在 " + MIN_INTERVAL_FLOOR + " ~ " + MIN_INTERVAL_CEIL + " 毫秒之间");
            }
            sysConfigService.upsertValueByKey(KEY_MIN_INTERVAL,
                    String.valueOf(interval),
                    "同站点最小请求间隔（毫秒）");
            current.setMinIntervalMs(interval);
        }
        log.info("合规风控设置已更新 robots={} rateLimit={} minInterval={}",
                current.getRobotsCheckEnabled(), current.getRateLimitEnabled(), current.getMinIntervalMs());
        return current;
    }

    /**
     * URL 合规预检（前端"合规预检"按钮 / 任务启动前调用）
     * robots 校验关闭时不发起网络请求，直接放行
     */
    public RobotsTxtService.Decision checkUrl(String url) {
        if (url == null || url.isBlank()) {
            throw new BizException(ErrorCode.PARAM_ERROR, "目标网址不能为空");
        }
        String target = url.trim();
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            throw new BizException(ErrorCode.PARAM_ERROR, "目标网址仅支持 http/https");
        }
        if (!isEnabled(KEY_ROBOTS_CHECK, true)) {
            RobotsTxtService.Decision decision = new RobotsTxtService.Decision();
            decision.setAllowed(true);
            decision.setFetched(false);
            decision.setReason("robots.txt 校验已关闭，未做站点限制检查（请自行确认合规性）");
            return decision;
        }
        return robotsTxtService.check(target);
    }

    /**
     * 任务启动前的强制校验：被 robots 禁止时抛出业务异常
     * 关闭校验开关可放行（测试场景），异常信息中明确提示
     */
    public void assertAllowedToCrawl(String url) {
        RobotsTxtService.Decision decision = checkUrl(url);
        if (!decision.isAllowed()) {
            throw new BizException(ErrorCode.FORBIDDEN,
                    "目标站点 robots.txt 不允许抓取：" + decision.getReason()
                            + "（如确需测试，可在设置中临时关闭 robots 校验）");
        }
    }

    /**
     * 站点级限速：保证同一站点两次请求间隔不小于配置值
     * 在每页数据落库后、翻页前调用
     *
     * 实现说明：每次调用固定等待一个间隔，而不是"距上次已过多少补多少"。
     * 因为调用点在页面请求完成之后，若按上一次调用的时间戳做抵扣，
     * 上一任务/上一页的处理耗时会被算进间隔里，导致实际"请求到请求"的间隔
     * 小于配置值（历史缺陷）。固定等待可确保请求间隔恒不小于配置值。
     */
    public void throttle(String url) {
        if (!isEnabled(KEY_RATE_LIMIT, true)) {
            return;
        }
        String host = hostOf(url);
        if (host.isEmpty()) {
            return;
        }
        int interval = readInt(KEY_MIN_INTERVAL, 1500);
        // 同一站点串行：后一个页面的等待从前一个页面等待结束后开始，避免并发放行
        Object lock = hostLocks.computeIfAbsent(host, k -> new Object());
        synchronized (lock) {
            try {
                Thread.sleep(interval);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 免责声明是否已确认 */
    public boolean isDisclaimerConfirmed() {
        return isEnabled(KEY_DISCLAIMER, false);
    }

    /** 确认免责声明 */
    public void confirmDisclaimer() {
        sysConfigService.upsertValueByKey(KEY_DISCLAIMER, "1",
                "免责弹窗是否已展示确认：0未确认(默认) 1已确认");
        log.info("用户已确认合规免责声明");
    }

    /** 读取布尔开关，配置不存在时返回默认值 */
    private boolean isEnabled(String key, boolean defaultValue) {
        SysConfig config = sysConfigService.getByKey(key);
        if (config == null || config.getConfigValue() == null) {
            return defaultValue;
        }
        return "1".equals(config.getConfigValue().trim());
    }

    /** 读取整型配置，非法或缺失时返回默认值 */
    private int readInt(String key, int defaultValue) {
        SysConfig config = sysConfigService.getByKey(key);
        if (config == null || config.getConfigValue() == null || config.getConfigValue().isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(config.getConfigValue().trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    private String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? "" : host;
        } catch (Exception e) {
            return "";
        }
    }
}
