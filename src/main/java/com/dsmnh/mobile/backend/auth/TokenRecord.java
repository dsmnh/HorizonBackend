package com.dsmnh.mobile.backend.auth;

/** 已签发凭证的内存映像：会话（App 平面）或节点（节点平面），token 只在两端持有。 */
public record TokenRecord(Kind kind, String token, String kid,
                          long accountId, String sessionId, long nodeId) {
    public enum Kind { SESSION, NODE }

    public static TokenRecord session(String token, String kid, long accountId, String sessionId) {
        return new TokenRecord(Kind.SESSION, token, kid, accountId, sessionId, -1);
    }

    public static TokenRecord node(String token, String kid, long nodeId) {
        return new TokenRecord(Kind.NODE, token, kid, -1, null, nodeId);
    }
}
