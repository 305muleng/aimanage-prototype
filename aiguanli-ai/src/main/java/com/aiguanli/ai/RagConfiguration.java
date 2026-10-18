package com.aiguanli.ai;

import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.support.RetryTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.rag.enabled", havingValue = "true")
@EnableConfigurationProperties(RagProperties.class)
public class RagConfiguration {

    @Bean
    EmbeddingModel ragEmbeddingModel(RagProperties properties) {
        if (properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalArgumentException("启用 RAG 需要 EMBEDDING_API_KEY，请运行 run.ps1 -Rag 在本机隐藏输入。");
        }
        var api = OpenAiApi.builder().baseUrl(properties.baseUrl()).apiKey(properties.apiKey())
                .embeddingsPath(properties.embeddingsPath()).build();
        var options = OpenAiEmbeddingOptions.builder().model(properties.model()).encodingFormat("float").build();
        return new OpenAiEmbeddingModel(api, MetadataMode.NONE, options,
                RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build());
    }
}
