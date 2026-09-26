package com.aiinterviewer.common;

/**
 * 业务错误码。前两位与 HTTP 状态对齐，便于前端按同一语义处理。
 */
public enum ErrorCode {

    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权访问"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "资源冲突"),
    TOO_MANY_REQUESTS(42900, "发言太频繁，请稍候再试"),
    QUOTA_EXCEEDED(42901, "今日 token 配额已用尽，请明天再来"),
    INTERNAL(50000, "服务器内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** 错误码对应的 HTTP 状态（前三位） */
    public int httpStatus() {
        return code / 100;
    }
}
