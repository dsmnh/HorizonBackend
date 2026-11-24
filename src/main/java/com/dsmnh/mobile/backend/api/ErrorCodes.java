package com.dsmnh.mobile.backend.api;

/** 登记表 §3 的 RES 层错误码常量；只增不改义。 */
public final class ErrorCodes {
    public static final String AUTH_REQUIRED = "AUTH_REQUIRED";
    public static final String BAD_PARAMS = "BAD_PARAMS";
    public static final String BAD_NAME = "BAD_NAME";
    public static final String BAD_TEXT = "BAD_TEXT";
    public static final String BAD_CREDENTIALS = "BAD_CREDENTIALS";
    public static final String NAME_TAKEN = "NAME_TAKEN";
    public static final String LOGIN_LOCKED = "LOGIN_LOCKED";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    public static final String BIND_CODE_INVALID = "BIND_CODE_INVALID";
    public static final String BIND_CODE_USED = "BIND_CODE_USED";
    public static final String RECOVER_CODE_INVALID = "RECOVER_CODE_INVALID";
    public static final String RECOVER_CODE_USED = "RECOVER_CODE_USED";
    public static final String UUID_BOUND = "UUID_BOUND";
    public static final String UUID_NOT_BOUND = "UUID_NOT_BOUND";
    public static final String NODE_CODE_INVALID = "NODE_CODE_INVALID";
    public static final String NODE_CODE_USED = "NODE_CODE_USED";
    public static final String CLAIM_CODE_INVALID = "CLAIM_CODE_INVALID";
    public static final String CLAIM_CODE_USED = "CLAIM_CODE_USED";
    public static final String OWNER_EXISTS = "OWNER_EXISTS";
    public static final String NODE_UNKNOWN = "NODE_UNKNOWN";
    public static final String NODE_OFFLINE = "NODE_OFFLINE";
    public static final String FRIEND_REQUEST_EXPIRED = "FRIEND_REQUEST_EXPIRED";
    public static final String BLOCKED = "BLOCKED";
    public static final String CHAT_RATE_LIMITED = "CHAT_RATE_LIMITED";
    public static final String TEAM_NAME_TAKEN = "TEAM_NAME_TAKEN";
    public static final String TEAM_NOT_FOUND = "TEAM_NOT_FOUND";
    public static final String TEAM_NOT_OPEN = "TEAM_NOT_OPEN";
    public static final String ALREADY_IN_TEAM = "ALREADY_IN_TEAM";
    public static final String NOT_IN_TEAM = "NOT_IN_TEAM";
    public static final String NO_INVITE = "NO_INVITE";
    public static final String NO_APPLY = "NO_APPLY";
    public static final String INVITE_EXPIRED = "INVITE_EXPIRED";
    public static final String APPLY_EXPIRED = "APPLY_EXPIRED";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String UNKNOWN_OP = "UNKNOWN_OP";
    /** 1.6.5：目标身份只存在于官方（GTNHLib）队伍，中央队伍写入不适用（契约 §8.8）。 */
    public static final String OFFICIAL_TEAM_MODE = "OFFICIAL_TEAM_MODE";
    /** 1.6.5：官方队伍映射缺失或与既有映射冲突（契约 §8.8）。 */
    public static final String MAP_CONFLICT = "MAP_CONFLICT";
    /** 1.6.6：官方队伍投影缺失、未就绪或已过期，写操作不得下发（契约 §8.9）。 */
    public static final String PROJECTION_STALE = "PROJECTION_STALE";
    /** 1.6.6：该次官方写入会触发合队，需携带 confirm:true 重试（契约 §8.9）。 */
    public static final String NEEDS_CONFIRM = "NEEDS_CONFIRM";

    private ErrorCodes() {}
}
