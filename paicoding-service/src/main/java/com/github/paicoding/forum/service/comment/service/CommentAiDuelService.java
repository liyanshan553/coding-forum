package com.github.paicoding.forum.service.comment.service;

import com.github.paicoding.forum.api.model.enums.ai.AiBotEnum;
import com.github.paicoding.forum.service.chatai.bot.HaterBot;
import com.github.paicoding.forum.service.comment.repository.entity.CommentDO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * 评论区 AI 对线服务
 */
@Slf4j
@Service
public class CommentAiDuelService {

    @Autowired
    private HaterBot haterBot;

    /**
     * 检测是否命中对线条件，命中则异步触发 AI 回复
     *
     * @param comment    当前评论
     * @param parent     父评论（顶级评论时为 null）
     * @param onAiReply  AI 回复回调
     */
    public void triggerIfNeeded(CommentDO comment, CommentDO parent, Consumer<String> onAiReply) {
        if (comment == null || StringUtils.isBlank(comment.getContent())) {
            return;
        }

        DuelTrigger trigger = detectTrigger(comment, parent);
        if (!trigger.triggered) {
            return;
        }

        log.info("评论「{}」 开启了在线互怼模式", comment);
        haterBot.trigger(comment.getContent(), trigger.chatId, onAiReply);
    }

    public Long botUserId() {
        return haterBot.getBotUser().getUserId();
    }

    private DuelTrigger detectTrigger(CommentDO comment, CommentDO parent) {
        Long topCommentId;
        boolean trigger = false;

        if (parent == null) {
            String tag = "@" + AiBotEnum.HATER_BOT.getNickName();
            if (comment.getContent().contains(tag)) {
                comment.setContent(StringUtils.replace(comment.getContent(), tag, ""));
                trigger = true;
            }
            topCommentId = comment.getId();
        } else {
            trigger = Objects.equals(botUserId(), parent.getUserId());
            topCommentId = comment.getTopCommentId();
        }

        DuelTrigger duelTrigger = new DuelTrigger();
        duelTrigger.triggered = trigger;
        duelTrigger.chatId = "comment:" + topCommentId + "_" + comment.getUserId();
        return duelTrigger;
    }

    private static class DuelTrigger {
        private boolean triggered;
        private String chatId;
    }
}
