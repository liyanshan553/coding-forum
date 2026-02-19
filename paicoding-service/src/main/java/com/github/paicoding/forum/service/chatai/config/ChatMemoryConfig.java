package com.github.paicoding.forum.service.chatai.config;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI ChatMemory 配置
 * 替代原来的 ChatHistoryServiceImpl 中 Redis List + MySQL 双写的上下文管理
 *
 * <p>使用 MessageWindowChatMemory 实现滑动窗口截断：
 * maxMessages=20 对应原来的 chatHistoryContextNum=10（10轮对话 = 20条消息）</p>
 *
 * <p>会话隔离通过 conversationId 参数实现，替代原来手拼的 Redis key：
 * chat.history.{ai}.{userId}:{chatId}</p>
 */
@Configuration
public class ChatMemoryConfig {

    @Bean
    public ChatMemory chatMemory() {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(new InMemoryChatMemoryRepository())
                .maxMessages(20)
                .build();
    }
}
