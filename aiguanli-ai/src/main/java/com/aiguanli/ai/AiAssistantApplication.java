package com.aiguanli.ai;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class AiAssistantApplication {

    public static void main(String[] args) {
        // 在创建模型客户端前检查配置，不打印密钥或完整环境变量。
        String key = System.getenv("AI_API_KEY");
        if (key == null || key.isBlank()) {
            key = System.getenv("DEEPSEEK_API_KEY");
        }
        if (key == null || key.isBlank()) {
            System.err.println("缺少 DeepSeek API Key。请运行 run.ps1 并在本机隐藏输入，或设置 DEEPSEEK_API_KEY。");
            System.exit(2);
        }
        SpringApplication.run(AiAssistantApplication.class, args);
    }
}
