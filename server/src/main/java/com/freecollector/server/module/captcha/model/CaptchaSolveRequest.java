package com.freecollector.server.module.captcha.model;

import lombok.Data;

import java.util.Map;

/**
 * 打码请求：待识别图片 + 附加参数
 * 图片二选一：base64 或 url
 */
@Data
public class CaptchaSolveRequest {

    /** 图片Base64（不含 data:image/png;base64, 前缀亦可） */
    private String imageBase64;

    /** 图片URL（部分平台支持传URL由平台下载） */
    private String imageUrl;

    /** 附加参数，可在参数模板中用 {{key}} 引用，如验证码类型 type */
    private Map<String, String> extraParams;
}
