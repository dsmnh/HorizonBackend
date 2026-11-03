package com.dsmnh.mobile.backend.util;

import java.security.SecureRandom;
import java.util.Base64;

/** 协议标识符生成：token、nonce、sid 与人工输入码。 */
public final class Ids {
    private static final SecureRandom RANDOM = new SecureRandom();
    /** 人工输入码字母表：去掉易混淆的 0/O/1/I/L。 */
    private static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";

    private Ids() {}

    /** 鉴权 token：32 字节随机数的 base64url（无填充，43 字符）。 */
    public static String token() {
        return base64Url(32);
    }

    /** 握手 nonce：8 字节随机数的 base64url（无填充，11 字符），契约 §4.2。 */
    public static String nonce() {
        return base64Url(8);
    }

    /** 订阅 sid：与 nonce 同规格。 */
    public static String sid() {
        return base64Url(8);
    }

    /** 一次性输入码（绑定码/恢复码/节点注册码），8 字符。 */
    public static String code() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    private static String base64Url(int byteCount) {
        byte[] bytes = new byte[byteCount];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
