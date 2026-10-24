package com.freecollector.server.module.captcha.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 第三方打码方案配置实体，对应表 captcha_config
 *
 * 安全约束：apiKey 以 AES 密文入库（api_key_encrypted），任何接口不得返回明文/密文。
 */
@Data
@TableName("captcha_config")
public class CaptchaConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 方案名称（用户自定义，便于切换） */
    private String configName;

    /** 第三方打码平台接口地址 */
    private String apiUrl;

    /** 请求方法：GET / POST */
    private String httpMethod;

    /** 自定义请求头JSON（非敏感部分，支持 {{api_key}} 占位符） */
    private String requestHeaders;

    /** 请求参数模板：支持 {{image_base64}} / {{image_url}} / {{api_key}} 占位符 */
    private String paramTemplate;

    /** 图片上传方式：BASE64 / IMAGE_URL */
    private String uploadMode;

    /** 识别结果提取JSONPath */
    private String resultJsonpath;

    /** 轮询间隔毫秒 */
    private Integer pollIntervalMs;

    /** 打码请求独立超时毫秒 */
    private Integer timeoutMs;

    /** 重试次数 */
    private Integer retryTimes;

    /** 平台密钥/Token（AES加密存储，禁止明文） */
    private String apiKeyEncrypted;

    /** 是否启用：0停用 1启用 */
    private Integer enabled;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    @TableLogic
    private Integer deleted;
}
