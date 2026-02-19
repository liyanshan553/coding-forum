package com.github.paicoding.forum.web.front.chat.helper;

import com.github.paicoding.forum.api.model.context.ReqInfoContext;
import com.github.paicoding.forum.api.model.enums.ai.AISourceEnum;
import com.github.paicoding.forum.api.model.vo.chat.ChatItemVo;
import com.github.paicoding.forum.api.model.vo.chat.ChatRecordsVo;
import com.github.paicoding.forum.core.mdc.MdcUtil;
import com.github.paicoding.forum.core.ws.WebSocketResponseUtil;
import com.github.paicoding.forum.service.chatai.ChatFacade;
import com.github.paicoding.forum.service.user.service.LoginService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;

/**
 * WebSocket 聊天辅助类（Spring AI 重构版）
 * <p>
 * 改造前：调用 chatFacade.autoChat(question, Consumer<ChatRecordsVo> callback)，
 *         回调中通过 WebSocket 推送 ChatRecordsVo 给前端。
 * 改造后：调用 chatFacade.streamChat() 返回 Flux<String>，
 *         每个 chunk 包装成 ChatRecordsVo 推送给前端，流完成时推送最终结果。
 * </p>
 */
@Slf4j
@Component
public class WsAnswerHelper {
    public static final String AI_SOURCE_PARAM = "AI";

    @Autowired
    private ChatFacade chatFacade;

    public void sendMsgToUser(AISourceEnum ai, String session, String question) {
        AISourceEnum source = ai != null ? ai : chatFacade.getRecommendAiSource();
        String chatId = ReqInfoContext.getReqInfo() != null && ReqInfoContext.getReqInfo().getChatId() != null
                ? ReqInfoContext.getReqInfo().getChatId()
                : session;

        StringBuilder fullAnswer = new StringBuilder();

        chatFacade.streamChat(source, question, chatId)
                .doOnNext(chunk -> {
                    fullAnswer.append(chunk);
                    // 每个流式 chunk 推送给前端
                    ChatRecordsVo vo = buildStreamResponse(source, question, fullAnswer.toString(), false);
                    response(session, vo);
                })
                .doOnComplete(() -> {
                    // 流完成，推送最终结果
                    ChatRecordsVo vo = buildStreamResponse(source, question, fullAnswer.toString(), true);
                    response(session, vo);
                    log.info("AI流式回复完成, session={}", session);
                })
                .doOnError(error -> log.error("AI流式回复异常, session={}", session, error))
                .subscribe();
    }

    /**
     * 将返回结果推送给用户
     */
    public void response(String session, ChatRecordsVo response) {
        WebSocketResponseUtil.sendMsgToUser(session, "/chat/rsp", response);
    }

    public void execute(Map<String, Object> attributes, Runnable func) {
        try {
            ReqInfoContext.ReqInfo reqInfo = (ReqInfoContext.ReqInfo) attributes.get(LoginService.SESSION_KEY);
            ReqInfoContext.addReqInfo(reqInfo);
            String traceId = (String) attributes.get(MdcUtil.TRACE_ID_KEY);
            MdcUtil.add(MdcUtil.TRACE_ID_KEY, traceId);

            func.run();
        } finally {
            ReqInfoContext.clear();
            MdcUtil.clear();
        }
    }

    private ChatRecordsVo buildStreamResponse(AISourceEnum source, String question, String answer, boolean completed) {
        ChatRecordsVo vo = new ChatRecordsVo();
        vo.setSource(source);
        ChatItemVo item = new ChatItemVo();
        item.setQuestion(question);
        item.setAnswer(answer);
        vo.setRecords(Arrays.asList(item));
        return vo;
    }
}
