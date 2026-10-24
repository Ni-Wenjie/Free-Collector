package com.freecollector.server.module.crawler.model;

import lombok.Getter;

/**
 * 访问速度档位（普通模式仅暴露三档，底层参数由档位推导）
 *
 * 产品分层：普通用户无需理解并发/间隔，选择档位即可；
 * 高阶用户可在 AdvancedOptions 中覆盖具体数值。
 */
@Getter
public enum SpeedProfile {

    /** 慢：默认档位，低并发长间隔，对目标站点最友好 */
    SLOW(1, 2000, 4000, "慢速：低并发、长间隔，最安全"),

    /** 普通：适中并发与间隔 */
    NORMAL(2, 800, 2000, "普通：适中并发与间隔"),

    /** 快：高并发短间隔，风险最高，前端需红色风险提示 */
    FAST(3, 100, 500, "快速：高并发、短间隔，可能触发网站风控，请自行承担风险");

    /** 建议并发数（模块五任务队列使用） */
    private final int concurrency;

    /** 两次请求之间的最小随机停顿（毫秒） */
    private final int minDelayMs;

    /** 两次请求之间的最大随机停顿（毫秒） */
    private final int maxDelayMs;

    /** 面向用户的说明文案 */
    private final String description;

    SpeedProfile(int concurrency, int minDelayMs, int maxDelayMs, String description) {
        this.concurrency = concurrency;
        this.minDelayMs = minDelayMs;
        this.maxDelayMs = maxDelayMs;
        this.description = description;
    }

    /** 宽松解析：未知值一律回落到 SLOW，保证普通模式安全默认 */
    public static SpeedProfile parse(String value) {
        if (value == null || value.isBlank()) {
            return SLOW;
        }
        for (SpeedProfile p : values()) {
            if (p.name().equalsIgnoreCase(value.trim())) {
                return p;
            }
        }
        return SLOW;
    }
}
