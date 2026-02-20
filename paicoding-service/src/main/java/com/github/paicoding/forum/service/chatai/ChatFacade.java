package com.github.paicoding.forum.service.chatai;

import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.core.senstive.SensitiveService;
import com.github.paicoding.forum.service.user.service.conf.AiConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

/**
 * AI 聊天门面类（Spring AI 重构版）
 * <p>
 * 替代原来的 ChatServiceFactory + AbsChatService 模板方法 + 5 套 *ChatServiceImpl，
 * 统一使用 Spring AI 的 ChatClient 流式接口。
 * </p>
 */
@Slf4j
@Service
public class ChatFacade {

    /**
     * 管理端手动指定的默认模型
     */
    private volatile AISourceEnum manualDefaultSource;

    @Autowired
    private AiConfig aiConfig;

    @Autowired
    private Map<AISourceEnum, ChatModel> chatModelRegistry;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private SensitiveService sensitiveService;

    /**
     * 获取推荐的AI模型（简化版，替代原来的 if-else 链 + Guava 缓存）
     */
    public AISourceEnum getRecommendAiSource() {
        if (manualDefaultSource != null && chatModelRegistry.containsKey(manualDefaultSource)) {
            return manualDefaultSource;
        }

        List<AISourceEnum> sources = aiConfig.getSource();
        if (sources != null) {
            for (AISourceEnum source : sources) {
                if (chatModelRegistry.containsKey(source)) {
                    return source;
                }
            }
        }
        return AISourceEnum.DEEP_SEEK;
    }

    /**
     * 刷新默认模型选择
     *
     * @param source 手动指定的模型，传空时清空并恢复配置推荐
     */
    public void refreshAiSourceCache(AISourceEnum source) {
        if (source == null) {
            manualDefaultSource = null;
            return;
        }

        if (!chatModelRegistry.containsKey(source)) {
            log.warn("指定模型 {} 未注册，忽略本次切换", source);
            return;
        }

        manualDefaultSource = source;
    }

    /**
     * 同步问答
     *
     * @param source         AI模型来源
     * @param question       用户问题
     * @param conversationId 会话ID（对应原来的 chatId，如 comment:{topCommentId}_{userId}）
     * @return AI回复内容
     */
    public String chat(AISourceEnum source, String question, String conversationId) {
        List<String> hits = sensitiveService.contains(question);
        if (!CollectionUtils.isEmpty(hits)) {
            return String.format("提问中包含敏感词: %s", hits);
        }

        ChatModel model = resolveModel(source);
        return ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build()
                .prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .call()
                .content();
    }

    /**
     * 流式问答（替代原来的 BiConsumer 三层回调链）
     * <p>
     * 返回 Flux<String>，每个元素是一个流式 chunk，
     * 调用方通过 subscribe() 处理，天然异步，无需手动管理线程池。
     * </p>
     *
     * @param source         AI模型来源
     * @param question       用户问题
     * @param conversationId 会话ID
     * @return 流式响应
     */
    public Flux<String> streamChat(AISourceEnum source, String question, String conversationId) {
        List<String> hits = sensitiveService.contains(question);
        if (!CollectionUtils.isEmpty(hits)) {
            return Flux.just(String.format("提问中包含敏感词: %s", hits));
        }

        ChatModel model = resolveModel(source);
        return ChatClient.builder(model)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .build()
                .prompt()
                .user(question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, conversationId))
                .stream()
                .content();
    }

    private ChatModel resolveModel(AISourceEnum source) {
        ChatModel model = chatModelRegistry.get(source);
        if (model == null) {
            log.warn("未找到AI模型 {}，使用默认 DeepSeek", source);
            model = chatModelRegistry.get(AISourceEnum.DEEP_SEEK);
        }
        return model;
    }
}
