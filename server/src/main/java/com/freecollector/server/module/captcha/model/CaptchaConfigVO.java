package com.freecollector.server.module.captcha.model;

import com.freecollector.server.module.captcha.entity.CaptchaConfig;
import lombok.Data;

/**
 * 打码方案列表/详情视图：脱敏返回，只告知"是否已配置密钥"
 */
@Data
public class CaptchaConfigVO {

    private Long id;
    private String configName;
    private String apiUrl;
    private String httpMethod;
    private String requestHeaders;
    private String paramTemplate;
    private String uploadMode;
    private String resultJsonPath;
    private Integer pollIntervalMs;
    private Integer timeoutMs;
    private Integer retryTimes;

    /** 是否已配置密钥（绝不返回密钥本身） */
    private boolean apiKeyConfigured;

    private Boolean enabled;
    private String remark;
    private String createTime;

    public static CaptchaConfigVO from(CaptchaConfig entity) {
        CaptchaConfigVO vo = new CaptchaConfigVO();
        vo.setId(entity.getId());
        vo.setConfigName(entity.getConfigName());
        vo.setApiUrl(entity.getApiUrl());
        vo.setHttpMethod(entity.getHttpMethod());
        vo.setRequestHeaders(entity.getRequestHeaders());
        vo.setParamTemplate(entity.getParamTemplate());
        vo.setUploadMode(entity.getUploadMode());
        vo.setResultJsonPath(entity.getResultJsonpath());
        vo.setPollIntervalMs(entity.getPollIntervalMs());
        vo.setTimeoutMs(entity.getTimeoutMs());
        vo.setRetryTimes(entity.getRetryTimes());
        vo.setApiKeyConfigured(entity.getApiKeyEncrypted() != null && !entity.getApiKeyEncrypted().isBlank());
        vo.setEnabled(entity.getEnabled() != null && entity.getEnabled() == 1);
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime() == null ? null : entity.getCreateTime().toString().replace('T', ' '));
        return vo;
    }
}
