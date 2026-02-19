package com.github.paicoding.forum.service.comment.service.impl;

import com.github.paicoding.forum.api.model.enums.NotifyTypeEnum;
import com.github.paicoding.forum.api.model.enums.YesOrNoEnum;
import com.github.paicoding.forum.api.model.exception.ExceptionUtil;
import com.github.paicoding.forum.api.model.vo.comment.CommentSaveReq;
import com.github.paicoding.forum.api.model.vo.constants.StatusEnum;
import com.github.paicoding.forum.api.model.vo.notify.NotifyMsgEvent;
import com.github.paicoding.forum.core.util.NumUtil;
import com.github.paicoding.forum.core.util.SpringUtil;
import com.github.paicoding.forum.service.article.repository.entity.ArticleDO;
import com.github.paicoding.forum.service.article.service.ArticleReadService;
import com.github.paicoding.forum.service.comment.converter.CommentConverter;
import com.github.paicoding.forum.service.comment.repository.dao.CommentDao;
import com.github.paicoding.forum.service.comment.repository.entity.CommentDO;
import com.github.paicoding.forum.service.comment.service.CommentAiDuelService;
import com.github.paicoding.forum.service.comment.service.CommentWriteService;
import com.github.paicoding.forum.service.user.service.UserFootService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

/**
 * 评论Service
 *
 * @author louzai
 * @date 2022-07-24
 */
@Slf4j
@Service
public class CommentWriteServiceImpl implements CommentWriteService {

    @Autowired
    private CommentDao commentDao;

    @Autowired
    private ArticleReadService articleReadService;

    @Autowired
    private UserFootService userFootWriteService;
    @Autowired
    private CommentAiDuelService commentAiDuelService;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveComment(CommentSaveReq commentSaveReq) {
        // 保存评论
        CommentDO comment;
        if (NumUtil.nullOrZero(commentSaveReq.getCommentId())) {
            comment = addComment(commentSaveReq);
        } else {
            comment = updateComment(commentSaveReq);
        }
        return comment.getId();
    }

    private CommentDO addComment(CommentSaveReq commentSaveReq) {
        // 0.获取父评论信息，校验是否存在
        CommentDO parentComment = getParentCommentUser(commentSaveReq.getParentCommentId());
        Long parentUser = parentComment == null ? null : parentComment.getUserId();

        // 1. 保存评论内容
        CommentDO commentDO = CommentConverter.toDo(commentSaveReq);
        Date now = new Date();
        commentDO.setCreateTime(now);
        commentDO.setUpdateTime(now);
        commentDao.save(commentDO);

        // 2. 保存足迹信息 : 文章的已评信息 + 评论的已评信息
        ArticleDO article = articleReadService.queryBasicArticle(commentSaveReq.getArticleId());
        if (article == null) {
            throw ExceptionUtil.of(StatusEnum.ARTICLE_NOT_EXISTS, commentSaveReq.getArticleId());
        }
        userFootWriteService.saveCommentFoot(commentDO, article.getUserId(), parentUser);

        // 3. 触发 AI 评论机器人
        commentAiDuelService.triggerIfNeeded(commentDO, parentComment, reply -> aiReply(commentAiDuelService.botUserId(), reply, commentDO));

        // 4. 发布添加/回复评论事件
        SpringUtil.publishEvent(new NotifyMsgEvent<>(this, NotifyTypeEnum.COMMENT, commentDO));
        if (NumUtil.upZero(parentUser)) {
            // 评论回复事件
            SpringUtil.publishEvent(new NotifyMsgEvent<>(this, NotifyTypeEnum.REPLY, commentDO));
        }
        return commentDO;
    }

    private CommentDO updateComment(CommentSaveReq commentSaveReq) {
        // 更新评论
        CommentDO commentDO = commentDao.getById(commentSaveReq.getCommentId());
        if (commentDO == null) {
            throw ExceptionUtil.of(StatusEnum.COMMENT_NOT_EXISTS, commentSaveReq.getCommentId());
        }
        commentDO.setContent(commentSaveReq.getCommentContent());
        commentDao.updateById(commentDO);
        return commentDO;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long commentId, Long userId) {
        CommentDO commentDO = commentDao.getById(commentId);
        // 1.校验评论，是否越权，文章是否存在
        if (commentDO == null) {
            throw ExceptionUtil.of(StatusEnum.COMMENT_NOT_EXISTS, "评论ID=" + commentId);
        }
        if (!Objects.equals(commentDO.getUserId(), userId)) {
            throw ExceptionUtil.of(StatusEnum.FORBID_ERROR_MIXED, "无权删除评论");
        }
        // 获取文章信息
        ArticleDO article = articleReadService.queryBasicArticle(commentDO.getArticleId());
        if (article == null) {
            throw ExceptionUtil.of(StatusEnum.ARTICLE_NOT_EXISTS, commentDO.getArticleId());
        }

        // 2.删除评论、足迹
        commentDO.setDeleted(YesOrNoEnum.YES.getCode());
        commentDao.updateById(commentDO);
        CommentDO parentComment = getParentCommentUser(commentDO.getParentCommentId());
        userFootWriteService.removeCommentFoot(commentDO, article.getUserId(), parentComment == null ? null : parentComment.getUserId());

        // 3. 发布删除评论事件
        SpringUtil.publishEvent(new NotifyMsgEvent<>(this, NotifyTypeEnum.DELETE_COMMENT, commentDO));
        if (NumUtil.upZero(commentDO.getParentCommentId())) {
            // 评论
            SpringUtil.publishEvent(new NotifyMsgEvent<>(this, NotifyTypeEnum.DELETE_REPLY, commentDO));
        }
    }


    private CommentDO getParentCommentUser(Long parentCommentId) {
        if (NumUtil.nullOrZero(parentCommentId)) {
            return null;

        }
        CommentDO parent = commentDao.getById(parentCommentId);
        if (parent == null) {
            throw ExceptionUtil.of(StatusEnum.COMMENT_NOT_EXISTS, "父评论=" + parentCommentId);
        }
        return parent;
    }


    /**
     * AI 机器人回帖
     *
     * @param aiUserId      机器人用户id
     * @param replyContent  回复内容
     * @param parentComment 被回复的评论
     */
    private void aiReply(Long aiUserId, String replyContent, CommentDO parentComment) {
        CommentSaveReq save = new CommentSaveReq();
        save.setArticleId(parentComment.getArticleId());
        save.setCommentContent(replyContent);
        save.setUserId(aiUserId);
        save.setParentCommentId(parentComment.getId());
        save.setTopCommentId(NumUtil.upZero(parentComment.getTopCommentId()) ? parentComment.getTopCommentId() : parentComment.getId());
        SpringUtil.getBean(CommentWriteService.class).saveComment(save);
    }
}
