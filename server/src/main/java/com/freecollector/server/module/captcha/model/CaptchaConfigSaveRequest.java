package com.freecollector.server.module.captcha.model;

import lombok.Data;

/**
 * 打码方案保存请求（新建/编辑共用）
 */
@Data
public class CaptchaConfigSaveRequest {

    /** 方案ID：null 新建，非空编辑 */
    private Long id;

    private String configName;

    private String apiUrl;

    /** GET / POST，默认 POST */
    private String httpMethod = "POST";

    /** 自定义请求头JSON字符串，支持 {{api_key}} 占位符 */
    private String requestHeaders;

    /** 参数模板，支持 {{image_base64}} / {{image_url}} / {{api_key}} 占位符 */
    private String paramTemplate;

    /** BASE64 / IMAGE_URL，默认 BASE64 */
    private String uploadMode = "BASE64";

    /** 结果提取JSONPath，如 $.data.result */
    private String resultJsonPath;

    private Integer pollIntervalMs = 3000;

    private Integer timeoutMs = 30000;

    private Integer retryTimes = 3;

    /**
     * 平台密钥（明文传入，后端AES加密入库）：
     * null 表示保持不变（编辑时留空不清除），空串表示清除
     */
    private String apiKey;

    /** 是否启用，默认 true */
    private Boolean enabled = true;

    private String remark;
}
