package com.aiguanli.ai;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

@Service
public class AssistantService {

    private final ChatClient chatClient;
    private final ChatMemory memory = MessageWindowChatMemory.builder().maxMessages(20).build();

    public AssistantService(ChatClient.Builder builder) throws IOException {
        var prompt = new ClassPathResource("prompts/system.md");
        String systemPrompt = prompt.getContentAsString(StandardCharsets.UTF_8);
        this.chatClient = builder.clone().defaultSystem(systemPrompt)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(memory).build()).build();
    }

    /** 一次性提问不与其他会话共享记忆。 */
    public String ask(String question) {
        String conversationId = UUID.randomUUID().toString();
        try {
            return ask(conversationId, question);
        } finally {
            clear(conversationId);
        }
    }

    public String ask(String conversationId, String question) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("问题不能为空。");
        }
        if (conversationId == null || conversationId.isBlank()) {
            throw new IllegalArgumentException("会话标识不能为空。");
        }
        // 调用失败时恢复原记忆，避免下一次请求带入未成功处理的问题。
        var previousMessages = memory.get(conversationId);
        try {
            String answer = chatClient.prompt().user(question)
                    .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                    .call().content();
            if (answer == null || answer.isBlank()) {
                throw new IllegalStateException("模型返回了空回答。");
            }
            return answer;
        } catch (RuntimeException ex) {
            memory.clear(conversationId);
            if (!previousMessages.isEmpty()) {
                memory.add(conversationId, previousMessages);
            }
            throw ex;
        }
    }

    public void clear(String conversationId) {
        memory.clear(conversationId);
    }
}
