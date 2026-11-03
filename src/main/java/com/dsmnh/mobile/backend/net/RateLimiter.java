package com.dsmnh.mobile.backend.net;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * 鉴权失败限流：同 IP 60 秒内失败 20 次封禁 60 秒（契约 §4.3）。
 * 计数口径：AUTH 签名失败与预鉴权操作失败均计入。
 */
public final class RateLimiter {
    public static final int WINDOW_MS = 60_000;
    public static final int MAX_FAILURES = 20;
    public static final long BAN_MS = 60_000;

    private record IpState(Deque<Long> failures, long banUntil) {
        private IpState {
            failures = failures == null ? new ArrayDeque<>() : failures;
        }
    }

    private final Map<String, IpState> states = new HashMap<>();

    public synchronized boolean isBanned(String ip) {
        IpState state = states.get(ip);
        if (state == null) {
            return false;
        }
        if (state.banUntil > 0 && state.banUntil <= System.currentTimeMillis()) {
            states.remove(ip);
            return false;
        }
        return state.banUntil > 0;
    }

    /** 记录一次失败；返回是否因此进入封禁。 */
    public synchronized boolean failure(String ip) {
        long now = System.currentTimeMillis();
        IpState state = states.computeIfAbsent(ip, k -> new IpState(new ArrayDeque<>(), 0));
        state.failures.addLast(now);
        while (!state.failures.isEmpty() && now - state.failures.peekFirst() > WINDOW_MS) {
            state.failures.removeFirst();
        }
        if (state.failures.size() >= MAX_FAILURES) {
            states.put(ip, new IpState(state.failures, now + BAN_MS));
            return true;
        }
        return false;
    }

    /** 测试辅助：清空全部状态。 */
    public synchronized void reset() {
        states.clear();
    }
}
