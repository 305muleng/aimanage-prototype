package com.aiguanli.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.rag")
public record RagProperties(boolean enabled, String apiKey, String baseUrl,
        String embeddingsPath, String model, String indexFile, double threshold, int topK) {

    /** 不包含密钥；不同模型/服务的向量不能混用。 */
    public String embeddingSignature() {
        return baseUrl + "|" + embeddingsPath + "|" + model;
    }

    // 配置对象禁止通过默认 record.toString() 输出密钥。
    @Override
    public String toString() {
        return "RagProperties[enabled=" + enabled + ", model=" + model + "]";
    }
}
