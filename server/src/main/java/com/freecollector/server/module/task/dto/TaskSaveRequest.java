package com.freecollector.server.module.task.dto;

import com.freecollector.server.module.crawler.model.ExtractRule;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;

/**
 * 任务保存请求（新建 / 编辑共用）
 */
@Data
public class TaskSaveRequest {

    /** 任务ID：null 表示新建，非空表示编辑 */
    private Long id;

    @NotBlank(message = "任务名称不能为空")
    private String taskName;

    @NotBlank(message = "目标网址不能为空")
    private String targetUrl;

    /** 访问速度：SLOW / NORMAL / FAST */
    private String speedLevel = "SLOW";

    /** 模拟真人浏览 */
    private boolean simulateHuman = false;

    /** 自动轮换UA */
    private boolean rotateUa = false;

    /** 代理地址（明文传入，后端加密入库；留空表示清除） */
    private String proxyUrl;

    /** Cookie 原始字符串，格式 a=1; b=2（明文传入，后端加密入库；留空表示清除） */
    private String cookie;

    /** 最大翻页数（1 表示只采当前页） */
    private Integer maxPages = 5;

    /** 字段规则 */
    private List<ExtractRule> fields;

    /** 打码方案ID（关联 captcha_config.id，null 表示不使用打码） */
    private Long captchaConfigId;

    /**
     * 高阶配置（普通模式为 null，全部走默认值）：
     * 键与 crawler AdvancedOptions 字段一一对应，如
     * pageTimeoutMs / retryTimes / waitUntil / waitSelector / extraHeaders /
     * injectScripts / userAgent / ignoreHttpsErrors / blockImages / blockMedia
     */
    private java.util.Map<String, Object> advancedConfig;

    private String remark;
}
