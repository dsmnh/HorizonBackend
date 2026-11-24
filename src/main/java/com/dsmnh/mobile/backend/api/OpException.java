package com.dsmnh.mobile.backend.api;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 业务失败：code 为登记表错误码，detail 作为 ":detail" 后缀。 */
public class OpException extends RuntimeException {
    public final String code;
    public final String detail;
    /** 可选结构化载荷（如 NEEDS_CONFIRM 的合队信息）；非空时随失败回包一起下发。 */
    public final ObjectNode body;

    public OpException(String code) {
        this(code, null, null);
    }

    public OpException(String code, String detail) {
        this(code, detail, null);
    }

    public OpException(String code, ObjectNode body) {
        this(code, null, body);
    }

    private OpException(String code, String detail, ObjectNode body) {
        super(detail == null ? code : code + ":" + detail);
        this.code = code;
        this.detail = detail;
        this.body = body;
    }

    public String wire() {
        return detail == null ? code : code + ":" + detail;
    }
}
