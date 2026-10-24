package com.freecollector.server.common;

import lombok.Getter;

/**
 * 业务异常：业务层校验失败时抛出，由全局异常处理器统一转为 Result
 */
@Getter
public class BizException extends RuntimeException {

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
