package com.github.paicoding.forum.web.front.chat.rest;

import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.core.ws.WebSocketResponseUtil;
import com.github.paicoding.forum.web.front.chat.helper.WsAnswerHelper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * STOMP协议的AI聊天通讯（Spring AI 重构版）
 * <p>
 * 改造：移除了对 ChatHistoryService 的直接依赖，
 * 会话历史由 Spring AI ChatMemory 通过 conversationId 自动管理。
 * </p>
 */
@Slf4j
@RestController
public class ChatRestController {
    @Autowired
    private WsAnswerHelper answerHelper;

    @MessageMapping("/chat/{session}")
    public void chat(String msg,
                     @DestinationVariable("session") String session,
                     @Header("simpSessionAttributes") Map<String, Object> attrs,
                     SimpMessageHeaderAccessor accessor) {
        String aiType = (String) attrs.get(WsAnswerHelper.AI_SOURCE_PARAM);
        WebSocketResponseUtil.execute(accessor, () -> {
            log.info("{} 用户开始了对话: {} - {}", ReqInfoContext.getReqInfo().getUser(), aiType, msg);
            AISourceEnum source = aiType == null ? null : AISourceEnum.valueOf(aiType);
            answerHelper.sendMsgToUser(source, session, msg);
        });
    }

    @MessageMapping({"/chat/{session}/{chatId}"})
    public void chat(String msg,
                     @DestinationVariable("session") String session,
                     @DestinationVariable("chatId") String chatId,
                     @Header("simpSessionAttributes") Map<String, Object> attrs,
                     SimpMessageHeaderAccessor accessor) {
        String aiType = (String) attrs.get(WsAnswerHelper.AI_SOURCE_PARAM);
        WebSocketResponseUtil.execute(accessor, () -> {
            ReqInfoContext.getReqInfo().setChatId(chatId);
            log.info("{} 用户开始了对话: {} - {}", ReqInfoContext.getReqInfo().getUser(), aiType, msg);
            AISourceEnum source = aiType == null ? null : AISourceEnum.valueOf(aiType);
            answerHelper.sendMsgToUser(source, session, msg);
        });
    }
}
