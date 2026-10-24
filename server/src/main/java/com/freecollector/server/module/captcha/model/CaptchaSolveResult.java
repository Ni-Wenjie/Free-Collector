package com.freecollector.server.module.captcha.model;

import lombok.Data;

/**
 * 打码结果
 */
@Data
public class CaptchaSolveResult {

    /** 是否识别成功 */
    private boolean success;

    /** 识别出的验证码文本 */
    private String value;

    /** 失败原因（面向用户，可直接展示） */
    private String errorMsg;

    /** 实际请求次数（含轮询） */
    private int attempts;

    /** 耗时毫秒 */
    private long elapsedMs;

    /** 平台原始响应（截断至2000字符，便于高阶面板排障） */
    private String rawResponse;

    public static CaptchaSolveResult ok(String value, int attempts, long elapsed, String raw) {
        CaptchaSolveResult r = new CaptchaSolveResult();
        r.setSuccess(true);
        r.setValue(value);
        r.setAttempts(attempts);
        r.setElapsedMs(elapsed);
        r.setRawResponse(truncate(raw));
        return r;
    }

    public static CaptchaSolveResult fail(String errorMsg, int attempts, long elapsed, String raw) {
        CaptchaSolveResult r = new CaptchaSolveResult();
        r.setSuccess(false);
        r.setErrorMsg(errorMsg);
        r.setAttempts(attempts);
        r.setElapsedMs(elapsed);
        r.setRawResponse(truncate(raw));
        return r;
    }

    private static String truncate(String raw) {
        if (raw == null) {
            return null;
        }
        return raw.length() > 2000 ? raw.substring(0, 2000) + "...(已截断)" : raw;
    }
}
