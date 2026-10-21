package com.aimanage.prototype.service;

import org.springframework.stereotype.Component;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/** Limits repeated failed sign-ins for one account and client address in this application instance. */
@Component
public class LoginAttemptLimiter {
    private static final int MAX_FAILURES = 5;
    private static final Duration WINDOW = Duration.ofMinutes(15);
    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();

    private String key(String username, String address) {
        return (username == null ? "" : username.trim().toLowerCase(Locale.ROOT)) + "|"
                + (address == null ? "" : address);
    }

    public void check(String username, String address) {
        Attempt attempt = attempts.get(key(username, address));
        if (attempt != null && Instant.now().isBefore(attempt.started.plus(WINDOW))
                && attempt.failures >= MAX_FAILURES)
            throw new BusinessException("登录失败次数过多，请 15 分钟后再试");
    }

    public void failed(String username, String address) {
        attempts.compute(key(username, address), (key, old) -> {
            Instant now = Instant.now();
            if (old == null || !now.isBefore(old.started.plus(WINDOW))) return new Attempt(now, 1);
            return new Attempt(old.started, old.failures + 1);
        });
    }

    public void succeeded(String username, String address) {
        attempts.remove(key(username, address));
    }

    private record Attempt(Instant started, int failures) {}
}
