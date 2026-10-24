package com.freecollector.server.module.captcha.model;

import lombok.Data;

/**
 * 第三方打码方案配置（运行时载体，对应数据库 captcha_config 表）
 *
 * 软件不内置验证码识别模型，只描述如何把图片转发给外部平台。
 * 采用策略模式，新增平台只需新增策略实现。
 */
@Data
public class CaptchaConfigModel {

    /** 方案ID（数据库主键，可为空表示临时测试方案） */
    private Long id;

    /** 方案名称，便于多套方案切换 */
    private String configName;

    /** 策略类型：HTTP_FORWARD（通用HTTP转发）/ 后续可扩展具体平台策略 */
    private String strategyType = "HTTP_FORWARD";

    /** 第三方接口地址，仅允许 http/https */
    private String apiUrl;

    /** 请求方法：GET / POST */
    private String httpMethod = "POST";

    /** 自定义请求头JSON，支持 {{api_key}} 占位符 */
    private String requestHeaders;

    /** 参数模板，支持 {{image_base64}} / {{image_url}} / {{api_key}} 占位符 */
    private String paramTemplate;

    /** 图片上传方式：BASE64 / IMAGE_URL */
    private String uploadMode = "BASE64";

    /** 识别结果提取JSONPath，如 $.data.result */
    private String resultJsonPath;

    /** 轮询间隔毫秒：平台异步返回结果时按此间隔轮询 */
    private Integer pollIntervalMs = 3000;

    /** 单次识别总超时毫秒（独立超时，不阻塞爬虫主任务） */
    private Integer timeoutMs = 30000;

    /** 网络异常重试次数 */
    private Integer retryTimes = 3;

    /** 平台密钥/Token（运行时明文，来自数据库解密；禁止落日志） */
    private String apiKey;

    /** 是否启用 */
    private Boolean enabled = true;
}
