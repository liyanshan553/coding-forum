package com.github.paicoding.forum.service.chatai.service.impl.xunfei;

import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 讯飞星火 ChatModel 适配器
 * <p>
 * Spring AI 不原生支持讯飞的 WebSocket 协议，
 * 通过实现 ChatModel 接口桥接，使讯飞可以统一纳入 Spring AI 体系。
 * </p>
 */
@Slf4j
@Component
public class XunFeiChatModelAdapter implements ChatModel {

    @Autowired
    private XunFeiIntegration xunFeiIntegration;

    @Override
    public ChatResponse call(Prompt prompt) {
        String question = extractUserMessage(prompt.getInstructions());
        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder fullAnswer = new StringBuilder();

        OkHttpClient client = xunFeiIntegration.getOkHttpClient();
        String url = xunFeiIntegration.buildXunFeiUrl();
        if (url == null) {
            return new ChatResponse(List.of(new Generation(new AssistantMessage("讯飞URL构建失败"))));
        }

        Request request = new Request.Builder().url(url).build();
        client.newWebSocket(request, new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull Response response) {
                webSocket.send(xunFeiIntegration.buildSendMsg("adapter", question));
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                XunFeiIntegration.ResponseData data = xunFeiIntegration.parse2response(text);
                if (data.successReturn()) {
                    data.getPayload().getChoices().getText().forEach(t -> {
                        if (t.getContent() != null) {
                            fullAnswer.append(t.getContent());
                        }
                        if (t.getReasoning_content() != null) {
                            fullAnswer.append(t.getReasoning_content());
                        }
                    });
                    if (data.endResponse()) {
                        webSocket.close(1001, "完成");
                        future.complete(fullAnswer.toString());
                    }
                } else {
                    webSocket.close(data.getHeader().getCode(), data.getHeader().getMessage());
                    future.complete("讯飞AI返回异常: " + data.getHeader().getMessage());
                }
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, Response response) {
                log.error("讯飞WebSocket连接失败", t);
                future.complete("讯飞AI连接失败: " + t.getMessage());
            }
        });

        try {
            String result = future.get(30, TimeUnit.SECONDS);
            return new ChatResponse(List.of(new Generation(new AssistantMessage(result))));
        } catch (Exception e) {
            log.error("讯飞AI调用超时", e);
            return new ChatResponse(List.of(new Generation(new AssistantMessage("讯飞AI调用超时"))));
        }
    }

    private String extractUserMessage(List<Message> messages) {
        return messages.stream()
                .filter(m -> m instanceof org.springframework.ai.chat.messages.UserMessage)
                .map(Message::getText)
                .reduce("", (a, b) -> a + "\n" + b)
                .trim();
    }
}
