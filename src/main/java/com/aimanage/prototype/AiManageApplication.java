package com.aimanage.prototype;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * “爱管理”应用的启动入口。
 *
 * <p>应用启动后加载 Web、JPA 与按配置启用的演示数据。</p>
 */
@SpringBootApplication
public class AiManageApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiManageApplication.class, args);
    }
}
