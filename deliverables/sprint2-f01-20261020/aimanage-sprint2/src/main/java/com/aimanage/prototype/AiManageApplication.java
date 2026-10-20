package com.aimanage.prototype;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * “爱管理”Sprint 1 原型的启动入口。
 *
 * <p>应用启动后会加载 Web、JPA 与演示数据配置，为课堂验收提供可运行环境。</p>
 */
@SpringBootApplication
public class AiManageApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiManageApplication.class, args);
    }
}
