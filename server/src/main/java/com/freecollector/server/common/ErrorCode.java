package com.freecollector.server.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 统一错误码
 * 0：成功；1xxx：客户端/参数类错误；5xxx：服务端内部错误
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    SUCCESS(0, "成功"),

    PARAM_ERROR(1001, "参数错误"),
    NOT_FOUND(1004, "资源不存在"),
    FORBIDDEN(1003, "访问被拒绝"),

    INTERNAL_ERROR(5000, "系统内部错误"),
    DB_ERROR(5001, "数据库访问异常"),
    REDIS_ERROR(5002, "Redis访问异常"),
    CRAWLER_ERROR(6001, "爬虫引擎执行异常"),
    CAPTCHA_ERROR(6002, "第三方打码接口调用异常");

    private final Integer code;
    private final String message;
}
