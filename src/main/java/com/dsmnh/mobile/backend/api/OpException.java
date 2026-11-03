package com.dsmnh.mobile.backend.api;

/** 业务失败：code 为登记表错误码，detail 作为 ":detail" 后缀。 */
public class OpException extends RuntimeException {
    public final String code;
    public final String detail;

    public OpException(String code) {
        this(code, null);
    }

    public OpException(String code, String detail) {
        super(detail == null ? code : code + ":" + detail);
        this.code = code;
        this.detail = detail;
    }

    public String wire() {
        return detail == null ? code : code + ":" + detail;
    }
}
