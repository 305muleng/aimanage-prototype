package com.aimanage.prototype.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LoginAttemptLimiterTest {
    @Test void repeatedFailuresAreLimitedPerAccountAndAddress() {
        var limiter = new LoginAttemptLimiter();
        for (int i = 0; i < 5; i++) limiter.failed("developer", "127.0.0.1");
        assertThatThrownBy(() -> limiter.check("developer", "127.0.0.1"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("15 分钟");
        assertThatCode(() -> limiter.check("developer", "127.0.0.2")).doesNotThrowAnyException();
        limiter.succeeded("developer", "127.0.0.1");
        assertThatCode(() -> limiter.check("developer", "127.0.0.1")).doesNotThrowAnyException();
    }
}
