package com.freecollector.server.module.risk.model;

import lombok.Data;

/**
 * 合规风控设置（前端设置面板展示/提交）
 */
@Data
public class RiskSettings {

    /** robots.txt 校验开关：true 采集前校验目标地址是否被禁止 */
    private Boolean robotsCheckEnabled = true;

    /** 访问限速开关：true 时同一站点两次页面请求保持最小间隔 */
    private Boolean rateLimitEnabled = true;

    /** 同一站点最小请求间隔（毫秒），仅限速开关开启时生效 */
    private Integer minIntervalMs = 1500;

    /** 免责声明是否已确认（只读，通过独立接口确认） */
    private Boolean disclaimerConfirmed = false;
}
