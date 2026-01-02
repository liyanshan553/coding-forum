# Resume Claim Check: Engineers Hub

## AI 融合网关描述核对
- **存在的能力**：`ChatFacade` 统一封装 AI 访问入口，并基于 `ChatServiceFactory` 按 `AISourceEnum` 注册各模型（ChatGPT、智谱、讯飞、阿里、DeepSeek、豆包等），在运行时选择可用源并自动切换同步/异步调用方式。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/ChatFacade.java†L33-L184】【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/ChatServiceFactory.java†L15-L26】【F:paicoding-api/src/main/java/com/github/paicoding/forum/api/model/enums/ai/AISourceEnum.java†L9-L58】
- **限流/计数现状**：抽象聊天模板 `AbsChatService` 用 Redis Hash 记录“按模型+用户+日”的提问次数，并在成功回答后累加、到期 24 小时失效，属于简单计数限额而非多级分层限流；异步场景通过回调/WebSocket 推送响应，但代码中未见 Redis 分级限流或更复杂的流控策略。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/AbsChatService.java†L48-L200】【F:paicoding-web/src/main/java/com/github/paicoding/forum/web/front/chat/helper/WsAnswerHelper.java†L17-L72】
- **真实性结论**：可以表述为“策略+工厂模式统一 AI 接入、支持多模型和同步/异步回调”，但“流式响应与 Redis 分级限流保障高可用”在现有实现里仅有基础计数和回调推送，未体现分级限流或专门的流式管控，写简历时需弱化或补充实现细节。

## 高性能内容风控描述核对
- **存在的能力**：`SensitiveService` 在启动时注册到 `DynamicConfigContainer` 的回调，合并系统词库 + 自定义 allow/deny 列表初始化敏感词引擎，并在命中后通过 Redis 统计命中次数；动态配置刷新来自数据库的 `global_conf` 表，可在定时任务触发时重新绑定配置，实现热更新。【F:paicoding-core/src/main/java/com/github/paicoding/forum/core/senstive/SensitiveService.java†L27-L124】【F:paicoding-core/src/main/java/com/github/paicoding/forum/core/autoconf/DynamicConfigContainer.java†L36-L185】
- **差异点**：实现基于第三方敏感词库（houbb sensitive-word）而非明确的自研 DFA 中间件；文档未体现“双缓存机制”或“秒级生效”保障。简历建议表述为“基于可热更新配置的敏感词服务（支持 allow/deny 合并与命中统计）”，避免强调自研 DFA 与双缓存。

## ES 搜索能力描述核对
- **存在的能力**：搜索提示接口会在 `elasticsearch.open=true` 时走 ES `multiMatch` 查询 `article` 索引的 `title`/`short_title` 字段，命中后回表 MySQL 获取文章主键与标题用于下拉提示；未开启 ES 时则回退到 MySQL `LIKE` 查询。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleReadServiceImpl.java†L206-L244】【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/constant/EsIndexConstant.java†L12-L18】【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/constant/EsFieldConstant.java†L12-L24】
- **差异点**：仓库中未见 Canal 订阅/增量同步或 ES 索引写入逻辑，ES 仅用于查询侧；简历不宜写“Canal 增量同步 ES”或“实时索引构建”，可表述为“ES 搜索提示 + MySQL 回表”。

## 高并发激励系统描述核对
- **存在的能力**：`UserActivityRankServiceImpl` 使用 Redis ZSet 维护日/月活跃度榜单，并用“用户+日期+行为”哈希键做幂等防重复，支持点赞/收藏/评论/发文/关注等行为加减分，同时为排行榜设置 TTL 控制生命周期。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/rank/service/impl/UserActivityRankServiceImpl.java†L33-L188】
- **差异点**：当前逻辑未使用 Redis BitMap，而是通过 Hash 记录行为幂等与 ZSet 计分。简历可强调“ZSet 排行 + Hash 幂等防刷”，但不宜声称 BitMap 方案。

## 总体建议
- 保留已实现的统一 AI 接入、敏感词热更新和活跃度排行亮点，删除或弱化“分级限流/流式网关”、“自研 DFA 双缓存”、“BitMap 防刷”等尚未落地的表述，以确保与代码一致。

## 新增简历用语核对
- **敏感词热更新表述**：现有实现确有 MySQL 配置热刷新、allow/deny 合并及命中计数（`SensitiveService` 注册到动态配置容器，初始化引擎并将命中写入 Redis），可以保留，但应避免强调“秒级”“双缓存”之类未在代码中体现的高阶特性。【F:paicoding-core/src/main/java/com/github/paicoding/forum/core/senstive/SensitiveService.java†L34-L82】
- **AI 辅助问答表述**：统一接口 + 工厂模式、多模型切换（ChatGPT/智谱/讯飞/阿里/DeepSeek/豆包等）与按天 Redis 计数限额均已实现，可据实书写，但“分级限流”“高可用网关”应弱化为“基础频控”。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/ChatFacade.java†L33-L173】【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/AbsChatService.java†L54-L178】
- **ES 搜索表述**：可表述为“ES multiMatch 查询文章标题/短标题用于搜索提示，并回表 MySQL 取详情”，避免写“Canal 增量同步”或“实时索引构建”等未在代码中体现的能力。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/article/service/impl/ArticleReadServiceImpl.java†L206-L244】
- **知识贡献激励表述**：差异化加分（发文+10、评论+3、点赞/收藏+2 等）、Hash 幂等防重复、ZSet 维护日/月榜并设置 TTL 均落实，属于常见玩法，可描述为“Redis ZSet 排行 + Hash 幂等防刷”，无需强调“高并发”或“独创性”。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/rank/service/impl/UserActivityRankServiceImpl.java†L68-L150】

## AI 评论机器人描述核对
- **触发方式与对话隔离**：评论保存时会检测顶级评论是否 @杠精机器人或回复对象是否为机器人用户，命中后构造 `comment:{topCommentId}_{userId}` 作为聊天会话 ID，既保留顶级楼层语境又按用户拆分上下文，避免多人混聊干扰。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/comment/service/impl/CommentWriteServiceImpl.java†L93-L127】
- **异步调用与自动落库**：`HaterBot.trigger` 在独立线程设置机器人身份与会话 ID 后调用 `ChatFacade.autoChat`（DeepSeek 源）获取回复，回调中将答案写回评论表并通过事件发布通知，形成“人机对线”的闭环体验。【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/bot/HaterBot.java†L30-L73】【F:paicoding-service/src/main/java/com/github/paicoding/forum/service/comment/service/impl/CommentWriteServiceImpl.java†L115-L136】
- **技术含量评估**：实现覆盖触发检测、会话隔离、异步 AI 调用、落库与事件链路，远不止简单的模板回复，作为“评论场景 AI 机器人/人机对线”亮点是可信的；但若写“多模态”“复杂意图识别”则超出现有实现。建议按现有能力陈述，突出“上下文隔离 + 异步对话回写”。
