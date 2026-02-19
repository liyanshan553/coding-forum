# AI 评论机器人实现链路

本说明梳理了“@杠精机器人/人机对线”能力的端到端实现链路，涵盖触发条件、上下文构建、异步大模型调用、回调落库与历史隔离等关键节点。

## 1. 触发入口：评论写入
- `CommentWriteServiceImpl.saveComment()` 在创建评论后调用 `haterBotTrigger` 检测是否需要唤起机器人。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/comment/service/impl/CommentWriteServiceImpl.java†L40-L76】
- 触发条件与上下文：
  - 顶级评论：内容包含 `@杠精机器人` 时触发，保存前移除触发词；会话标识取当前顶级评论 ID。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/comment/service/impl/CommentWriteServiceImpl.java†L79-L107】
  - 回复评论：当父评论作者就是机器人时自动触发；会话标识沿用原顶级评论 ID，确保同串对话复用上下文。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/comment/service/impl/CommentWriteServiceImpl.java†L100-L118】
  - 最终会话 ID 组合为 `comment:{topCommentId}_{userId}`，在多人参与时以用户维度隔离上下文，防止交叉引用。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/comment/service/impl/CommentWriteServiceImpl.java†L117-L130】

## 2. 异步调用大模型
- `HaterBot.trigger()` 使用 `AsyncUtil.execute` 在后台线程执行对话，请求前将当前请求上下文 `ReqInfoContext` 的 `userId/chatId` 设置为机器人账号与会话 ID，确保后续链路能获取身份与会话标识。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/bot/HaterBot.java†L32-L79】
- 统一入口 `ChatFacade.autoChat()` 选择推荐模型（默认 DeepSeek），若该模型支持异步且优先异步则走 `asyncChat`，将回调透传以接收流式/完成态消息。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/ChatFacade.java†L113-L151】

## 3. 回调与落库
- DeepSeek 异步响应落到 `consumer.accept(item.getAnswer())` 后回调 `CommentWriteService.aiReply()`，以机器人身份构造二级评论写回数据库和事件流，实现自动回复。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/bot/HaterBot.java†L69-L104】【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/comment/service/impl/CommentWriteServiceImpl.java†L132-L150】

## 4. 会话隔离与历史记录
- `ChatHistoryServiceImpl.saveRecord()` 以 `AISource + userId + chatId` 维度将问答对写入 MySQL 与 Redis 列表，同时维护会话元信息（标题、更新时刻、Q&A 次数），并限制历史长度，保障不同用户、不同顶级评论的上下文隔离。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/history/ChatHistoryServiceImpl.java†L62-L110】
- `ChatHistoryServiceImpl.listHistory()` 在读取历史时也会为特定机器人自动补齐提示词，确保对话链路在上下文侧保持一致。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/history/ChatHistoryServiceImpl.java†L53-L76】

## 5. 机器人账号与提示词
- `HaterBot` 通过 `Suppliers.memoizeWithExpiration` 缓存机器人用户信息，若本地未初始化会自动注册系统用户；同时暴露 `addPrompt` 供历史补齐时植入提示词，确保人格一致性。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/bot/HaterBot.java†L18-L31】【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/bot/HaterBot.java†L81-L108】

## 链路小结
1) 评论写入阶段检测触发词或机器人回复对象，生成 `topCommentId + userId` 组合会话 ID。2) 异步调用大模型并绑定会话上下文。3) 回调将机器人答案写成二级评论。4) 对话历史按 `source/user/chatId` 维度存储并裁剪，辅以提示词维持人格与隔离。整体链路可对多个并发对线场景做用户级隔离，支撑流式异步回复与上下文记录，技术含量体现在触发判定、上下文隔离、异步回调与历史管理的组合实现。
