package com.github.paicoding.forum.service.chatai.bot;

import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.enums.ai.AiBotEnum;
import com.github.paicoding.forum.api.model.vo.user.dto.BaseUserInfoDTO;
import com.github.paicoding.forum.service.chatai.ChatFacade;
import com.github.paicoding.forum.service.user.service.RegisterService;
import com.github.paicoding.forum.service.user.service.UserService;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 基于 Spring AI 的杠精机器人（重构版）
 * <p>
 * 改造前：AsyncUtil.execute() + ReqInfoContext 手动设置 + Consumer 三层回调
 * 改造后：ChatFacade.streamChat() 返回 Flux，天然异步，subscribe() 完成回调
 * </p>
 */
@Slf4j
@Component
public class HaterBot {

    @Autowired
    private ChatFacade chatFacade;

    @Autowired
    private UserService userService;

    @Autowired
    private RegisterService registerService;

    private final Supplier<BaseUserInfoDTO> haterBotUser = Suppliers.memoizeWithExpiration(() -> {
        BaseUserInfoDTO user = userService.queryUserByLoginName(AiBotEnum.HATER_BOT.getUserName());
        if (user == null) {
            Long userId = registerService.registerSystemUser(
                    AiBotEnum.HATER_BOT.getUserName(),
                    AiBotEnum.HATER_BOT.getUserName(),
                    "https://cdn.tobebetterjavaer.com/paicoding/e0f01d775d3f67b309b394bc04d4e091.jpg");
            user = userService.queryBasicUserInfo(userId);
        }
        return user;
    }, 1, TimeUnit.HOURS);

    /**
     * 触发AI对线
     * <p>
     * 改造前调用链：
     *   AsyncUtil.execute -> ReqInfoContext.set -> ChatFacade.autoChat(Consumer)
     *     -> AbsChatService.asyncChat(Consumer) -> doAsyncAnswer(BiConsumer) -> listener callbacks
     * <p>
     * 改造后：
     *   ChatFacade.streamChat() -> Flux.reduce() -> subscribe(consumer)
     *   无需手动线程池、无需 ReqInfoContext、无回调嵌套
     */
    public void trigger(String question, String chatId, Consumer<String> consumer) {
        chatFacade.streamChat(AISourceEnum.DEEP_SEEK, question, chatId)
                .reduce("", (acc, chunk) -> acc + chunk)
                .subscribe(
                        fullReply -> {
                            log.info("AI对线回复完成, chatId={}, length={}", chatId, fullReply.length());
                            consumer.accept(fullReply);
                        },
                        error -> log.error("AI对线失败, chatId={}", chatId, error)
                );
    }

    public BaseUserInfoDTO getBotUser() {
        return haterBotUser.get();
    }
}
