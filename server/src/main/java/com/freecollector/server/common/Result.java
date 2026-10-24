package com.freecollector.server.common;

import lombok.Data;

import java.io.Serializable;

/**
 * 统一响应体：所有REST接口返回该结构
 *
 * @param <T> 业务数据类型
 */
@Data
public class Result<T> implements Serializable {

    /** 业务码：0成功，其余见 ErrorCode */
    private Integer code;

    /** 提示信息（面向用户，前端可直接弹窗展示） */
    private String message;

    /** 业务数据 */
    private T data;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.setCode(ErrorCode.SUCCESS.getCode());
        r.setMessage(ErrorCode.SUCCESS.getMessage());
        r.setData(data);
        return r;
    }

    public static Result<Void> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.getMessage());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        Result<T> r = new Result<>();
        r.setCode(errorCode.getCode());
        r.setMessage(message);
        return r;
    }
}
