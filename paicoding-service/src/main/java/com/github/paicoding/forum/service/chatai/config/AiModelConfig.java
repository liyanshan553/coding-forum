package com.github.paicoding.forum.service.chatai.config;

import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.service.chatai.service.impl.xunfei.XunFeiChatModelAdapter;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.ai.zhipuai.api.ZhiPuAiApi;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Spring AI 多模型统一注册配置
 * 替代原来的 5 套 *Integration + *ChatServiceImpl + ChatServiceFactory
 */
@Configuration
public class AiModelConfig {

    /**
     * DeepSeek 走 OpenAI 兼容协议
     */
    @Bean("deepSeekModel")
    public ChatModel deepSeekModel(
            @Value("${deepseek.apiKey:}") String apiKey,
            @Value("${deepseek.apiHost:https://api.deepseek.com}") String apiHost) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(apiHost)
                        .apiKey(apiKey)
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("deepseek-chat")
                        .build())
                .build();
    }

    /**
     * ChatGPT (OpenAI 原生)
     */
    @Bean("chatGptModel")
    public ChatModel chatGptModel(
            @Value("${chatgpt.conf.CHAT_GPT_3_5.apiHost:https://api.openai.com/}") String apiHost,
            @Value("${chatgpt.conf.CHAT_GPT_3_5.keys[0]:}") String apiKey) {
        return OpenAiChatModel.builder()
                .openAiApi(OpenAiApi.builder()
                        .baseUrl(apiHost)
                        .apiKey(apiKey)
                        .build())
                .defaultOptions(OpenAiChatOptions.builder()
                        .model("gpt-3.5-turbo")
                        .build())
                .build();
    }

    /**
     * 智谱 GLM
     */
    @Bean("zhipuModel")
    public ChatModel zhipuModel(
            @Value("${zhipu.apiSecretKey:}") String apiKey) {
        return new ZhiPuAiChatModel(new ZhiPuAiApi(apiKey));
    }

    /**
     * 模型路由表：AISourceEnum → ChatModel
     * 替代原来的 ChatServiceFactory
     */
    @Bean
    public Map<AISourceEnum, ChatModel> chatModelRegistry(
            @Qualifier("deepSeekModel") ChatModel deepSeek,
            @Qualifier("chatGptModel") ChatModel chatGpt,
            @Qualifier("zhipuModel") ChatModel zhipu,
            XunFeiChatModelAdapter xunfei) {
        Map<AISourceEnum, ChatModel> registry = new HashMap<>();
        registry.put(AISourceEnum.DEEP_SEEK, deepSeek);
        registry.put(AISourceEnum.CHAT_GPT_3_5, chatGpt);
        registry.put(AISourceEnum.ZHI_PU_AI, zhipu);
        registry.put(AISourceEnum.XUN_FEI_AI, xunfei);
        return registry;
    }
}
