# AI评论机器人（杠精机器人）完整实现指南

## 一、功能概述

### 1.1 业务场景
在技术社区中，用户在评论区 `@杠精机器人` 即可触发AI自动回复，实现人机互动的评论对线功能。该功能将**业务场景**（评论互动）与**AI技术**（大模型接入）深度结合。

### 1.2 核心亮点
| 亮点 | 技术实现 |
|------|----------|
| 触发检测 | `@杠精机器人` 关键词检测 + 回复用户判断 |
| 会话隔离 | `topCommentId + userId` 构建唯一会话标识 |
| 异步执行 | TTL线程池 + Consumer回调模式 |
| 用户缓存 | Guava Suppliers.memoizeWithExpiration |
| 提示词注入 | 聊天历史构建时自动补齐System Prompt |

---

## 二、完整调用链路图

```
用户发表评论（@杠精机器人）
        │
        ▼
┌─────────────────────────────────────┐
│  CommentWriteServiceImpl.saveComment │
│  ├── addComment()                    │
│  │   ├── 保存评论到数据库             │
│  │   ├── 保存足迹信息                 │
│  │   └── haterBotTrigger() ─────────┼──► 触发AI机器人
│  └── 发布评论事件                     │
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  HaterBot.trigger()                  │
│  ├── AsyncUtil.execute() 异步执行    │
│  ├── 设置ReqInfoContext上下文        │
│  │   ├── userId = 机器人ID           │
│  │   └── chatId = 会话隔离标识        │
│  └── ChatFacade.autoChat() ─────────┼──► 调用AI聊天
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  ChatFacade.autoChat()               │
│  ├── 判断异步/同步模式               │
│  └── asyncChat() ───────────────────┼──► 异步聊天
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  AbsChatService.asyncChat()          │
│  ├── initResVo() 构建响应对象        │
│  │   └── buildChatContext() 构建上下文│
│  │       └── ChatHistoryService      │
│  │           └── listHistory()       │
│  │               └── AiBots.autoBuildPrompt() ◄── 注入提示词
│  ├── 敏感词校验（机器人跳过）         │
│  └── doAsyncAnswer() ───────────────┼──► 调用具体实现
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  DeepSeekChatServiceImpl             │
│  └── doAsyncAnswer()                 │
│      └── DeepSeekIntegration         │
│          └── streamReturn() ────────┼──► SSE流式请求
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  DeepSeek API 返回结果               │
│  └── AbstractStreamListener回调      │
│      └── consumer.accept() ─────────┼──► 回调处理
└─────────────────────────────────────┘
        │
        ▼
┌─────────────────────────────────────┐
│  HaterBot Consumer回调               │
│  └── aiReply()                       │
│      └── CommentWriteService         │
│          └── saveComment() ─────────┼──► 保存AI回复评论
└─────────────────────────────────────┘
```

---

## 三、核心代码实现

### 3.1 机器人配置枚举

**文件**: `paicoding-api/.../enums/ai/AiBotEnum.java`

```java
@Getter
public enum AiBotEnum {
    HATER_BOT("haterBot", "杠精机器人",
        "你现在是一个名叫\"杠精机器人\"的专业杠精，接下来我给你一个一段文本，你来回复我，回复内容限制在800字符内"),
    ;

    private String userName;   // 系统用户名，对应user表
    private String nickName;   // 显示昵称，用于@检测
    private String prompt;     // AI提示词
}
```

**设计要点**：
- `userName`: 系统级唯一标识，用于数据库存储
- `nickName`: 用户可见名称，用于评论中@检测
- `prompt`: 预设的System Prompt，定义AI人格

---

### 3.2 评论触发检测

**文件**: `paicoding-service/.../comment/service/impl/CommentWriteServiceImpl.java`

```java
@Autowired
private HaterBot haterBot;

private void haterBotTrigger(CommentDO comment, CommentDO parent) {
    boolean trigger = false;
    Long haterBotUserId = haterBot.getBotUser().getUserId();
    Long topCommentId = 0L;

    if (parent == null) {
        // 场景1: 顶级评论，检测是否@了机器人
        String tag = "@" + AiBotEnum.HATER_BOT.getNickName();  // "@杠精机器人"
        if (comment.getContent().contains(tag)) {
            // 移除@标记，避免传给AI时带上
            comment.setContent(StringUtils.replace(comment.getContent(), tag, ""));
            trigger = true;
        }
        topCommentId = comment.getId();
    } else {
        // 场景2: 回复评论，判断被回复用户是否为机器人
        if (Objects.equals(haterBotUserId, parent.getUserId())) {
            trigger = true;
        }
        topCommentId = comment.getTopCommentId();
    }

    if (trigger) {
        log.info("评论「{}」 开启了在线互怼模式", comment);
        // 构建会话隔离标识: comment:顶级评论ID_用户ID
        haterBot.trigger(comment.getContent(),
            "comment:" + topCommentId + "_" + comment.getUserId(),
            reply -> aiReply(haterBotUserId, reply, comment));
    }
}
```

**触发条件**：
| 场景 | 条件 | 会话标识 |
|------|------|----------|
| 顶级评论 | 内容包含 `@杠精机器人` | `comment:{评论ID}_{用户ID}` |
| 回复评论 | 被回复者是机器人 | `comment:{顶级评论ID}_{用户ID}` |

**会话隔离设计**：
```
topCommentId + userId 组合的作用：
├── 同一顶级评论下，不同用户的对话相互独立
├── 同一用户在不同顶级评论下的对话相互独立
└── 避免多人参与时上下文交叉混乱
```

---

### 3.3 HaterBot核心实现

**文件**: `paicoding-service/.../chatai/bot/HaterBot.java`

```java
@Component
public class HaterBot {
    @Autowired
    private ChatFacade chatFacade;
    @Autowired
    private UserService userService;
    @Autowired
    private RegisterService registerService;

    // Guava缓存：1小时过期，避免频繁查库
    private Supplier<BaseUserInfoDTO> haterBotUser = Suppliers.memoizeWithExpiration(() -> {
        BaseUserInfoDTO user = userService.queryUserByLoginName(AiBotEnum.HATER_BOT.getUserName());
        if (user == null) {
            // 兜底：自动注册系统用户
            Long userId = registerService.registerSystemUser(
                AiBotEnum.HATER_BOT.getUserName(),
                AiBotEnum.HATER_BOT.getUserName(),
                "https://cdn.tobebetterjavaer.com/paicoding/xxx.jpg");
            user = userService.queryBasicUserInfo(userId);
        }
        return user;
    }, 1, TimeUnit.HOURS);

    /**
     * 触发AI机器人
     */
    public void trigger(String question, String sourceBizId, Consumer<String> consumer) {
        BaseUserInfoDTO user = haterBotUser.get();

        // 异步执行，不阻塞主流程
        AsyncUtil.execute(() -> {
            // 设置AI机器人问答上下文
            ReqInfoContext.ReqInfo reqInfo = new ReqInfoContext.ReqInfo();
            reqInfo.setUser(user);
            reqInfo.setUserId(user.getUserId());
            reqInfo.setChatId(sourceBizId);  // 会话隔离标识
            ReqInfoContext.addReqInfo(reqInfo);

            // 调用AI聊天，指定使用DeepSeek模型
            chatFacade.autoChat(AISourceEnum.DEEP_SEEK, question, vo -> {
                ChatItemVo item = vo.getRecords().get(0);
                // 只处理最终结果
                if (item.getAnswerType() == ChatAnswerTypeEnum.JSON
                        || item.getAnswerType() == ChatAnswerTypeEnum.TEXT
                        || item.getAnswerType() == ChatAnswerTypeEnum.STREAM_END) {
                    try {
                        consumer.accept(item.getAnswer());  // 回调保存评论
                    } finally {
                        ReqInfoContext.clear();  // 清理上下文
                    }
                }
            });
        });
    }

    /**
     * 获取机器人用户信息
     */
    public BaseUserInfoDTO getBotUser() {
        return haterBotUser.get();
    }

    /**
     * 为机器人添加提示词（在构建聊天上下文时调用）
     */
    public ChatItemVo addPrompt(Long userId) {
        if (Objects.equals(userId, getBotUser().getUserId())) {
            return new ChatItemVo()
                .setQuestion(ChatConstants.PROMPT_TAG + AiBotEnum.HATER_BOT.getPrompt());
        }
        return null;
    }
}
```

**关键技术点**：

#### 3.3.1 Guava缓存机制
```java
Suppliers.memoizeWithExpiration(() -> {
    // 查询/创建用户逻辑
}, 1, TimeUnit.HOURS);
```
- 缓存1小时，避免每次触发都查数据库
- 懒加载：首次调用时才执行查询
- 自动刷新：过期后下次调用重新加载

#### 3.3.2 TTL线程池上下文传递
```java
AsyncUtil.execute(() -> {
    ReqInfoContext.addReqInfo(reqInfo);  // 设置上下文
    // ... 异步逻辑
    ReqInfoContext.clear();  // 清理上下文
});
```

**AsyncUtil实现** (`paicoding-core/.../async/AsyncUtil.java`):
```java
// 使用阿里TTL包装线程池，支持上下文传递
executorService = TtlExecutors.getTtlExecutorService(executorService);
```

---

### 3.4 AI回复保存

**文件**: `CommentWriteServiceImpl.java`

```java
private void aiReply(Long aiUserId, String replyContent, CommentDO parentComment) {
    CommentSaveReq save = new CommentSaveReq();
    save.setArticleId(parentComment.getArticleId());
    save.setCommentContent(replyContent);
    save.setUserId(aiUserId);  // 机器人用户ID
    save.setParentCommentId(parentComment.getId());
    // 顶级评论ID：如果父评论已有，则沿用；否则父评论就是顶级评论
    save.setTopCommentId(NumUtil.upZero(parentComment.getTopCommentId())
        ? parentComment.getTopCommentId()
        : parentComment.getId());

    // 通过Spring容器获取代理对象，确保事务生效
    SpringUtil.getBean(CommentWriteService.class).saveComment(save);
}
```

---

### 3.5 提示词自动注入

**文件**: `paicoding-service/.../chatai/service/history/ChatHistoryServiceImpl.java`

```java
@Autowired
private AiBots aiBots;

@Override
public List<ChatItemVo> listHistory(AISourceEnum source, Long userId, String chatId, Integer size) {
    List<ChatItemVo> list = RedisClient.lRange(
        getChatIdKey(source, userId, chatId), 0, size, ChatItemVo.class);

    // 关键：为机器人自动补齐提示词
    ChatItemVo prompt = aiBots.autoBuildPrompt(userId);
    if (prompt != null) {
        list.add(prompt);  // 添加到历史记录末尾（最早的消息）
    }
    return list;
}
```

**AiBots服务** (`paicoding-service/.../chatai/bot/AiBots.java`):
```java
@Service
public class AiBots {
    @Autowired
    private HaterBot haterBot;

    public boolean aiBots(Long userId) {
        return Objects.equals(userId, haterBot.getBotUser().getUserId());
    }

    public ChatItemVo autoBuildPrompt(Long userId) {
        return haterBot.addPrompt(userId);
    }
}
```

**提示词处理** (`DeepSeekIntegration.java`):
```java
private List<ChatMsg> toMsg(ChatItemVo item) {
    List<ChatMsg> list = new ArrayList<>(2);
    if (item.getQuestion().startsWith(ChatConstants.PROMPT_TAG)) {
        // 提示词 → system角色
        list.add(new ChatMsg("system",
            item.getQuestion().substring(ChatConstants.PROMPT_TAG.length())));
    } else {
        // 用户问答 → user角色
        list.add(new ChatMsg("user", item.getQuestion()));
        if (StringUtils.isNotBlank(item.getAnswer())) {
            list.add(new ChatMsg("assistant", item.getAnswer()));
        }
    }
    return list;
}
```

---

### 3.6 敏感词豁免

**文件**: `AbsChatService.java`

```java
@Override
public ChatRecordsVo asyncChat(Long user, String question, Consumer<ChatRecordsVo> consumer) {
    // ...
    List<String> sensitiveWord = sensitiveService.contains(
        res.getRecords().get(0).getQuestion());

    // 关键：机器人不进行敏感词校验
    if (!CollectionUtils.isEmpty(sensitiveWord)
            && !SpringUtil.getBean(AiBots.class).aiBots(user)) {
        res.getRecords().get(0).initAnswer(
            String.format(ChatConstants.SENSITIVE_QUESTION, sensitiveWord));
        consumer.accept(res);
    } else {
        // 正常执行AI问答
        doAsyncAnswer(user, newRes, callback);
    }
    // ...
}
```

---

## 四、数据流转图

```
┌──────────────────────────────────────────────────────────────┐
│                        数据流转                               │
├──────────────────────────────────────────────────────────────┤
│                                                              │
│  [用户评论] ──► [触发检测] ──► [会话标识生成]                  │
│       │              │              │                        │
│       │              │              ▼                        │
│       │              │    "comment:123_456"                  │
│       │              │    (顶级评论ID_用户ID)                 │
│       │              │                                       │
│       ▼              ▼              ▼                        │
│  ┌─────────────────────────────────────────┐                │
│  │          ReqInfoContext 上下文           │                │
│  │  ├── userId: 机器人ID                    │                │
│  │  ├── chatId: comment:123_456            │                │
│  │  └── user: 机器人用户信息                │                │
│  └─────────────────────────────────────────┘                │
│                      │                                       │
│                      ▼                                       │
│  ┌─────────────────────────────────────────┐                │
│  │           Redis 聊天历史                 │                │
│  │  Key: chat.history.deep_seek.{botId}:   │                │
│  │       comment:123_456                   │                │
│  │  Value: [                               │                │
│  │    {question: "prompt-你是杠精.."},      │                │
│  │    {question: "xxx", answer: "yyy"},    │                │
│  │    ...                                  │                │
│  │  ]                                      │                │
│  └─────────────────────────────────────────┘                │
│                      │                                       │
│                      ▼                                       │
│  ┌─────────────────────────────────────────┐                │
│  │         DeepSeek API 请求               │                │
│  │  messages: [                            │                │
│  │    {role: "system", content: "你是..."},│                │
│  │    {role: "user", content: "问题1"},    │                │
│  │    {role: "assistant", content: "回复1"},│               │
│  │    {role: "user", content: "最新问题"}  │                │
│  │  ]                                      │                │
│  └─────────────────────────────────────────┘                │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

---

## 五、技术亮点总结

### 5.1 业务与技术结合
| 维度 | 说明 |
|------|------|
| 业务价值 | 评论区AI互动，提升用户参与度 |
| 技术深度 | 异步执行、上下文传递、会话管理 |
| 扩展性 | 枚举配置，易于添加新机器人类型 |

### 5.2 会话隔离设计
```
sourceBizId = "comment:" + topCommentId + "_" + userId
```
- **避免上下文污染**: 不同用户/不同评论树的对话相互独立
- **支持多轮对话**: 同一用户在同一评论树下可以连续对话
- **历史记录复用**: 基于chatId查询Redis中的历史记录

### 5.3 异步解耦架构
```
主线程                    异步线程
   │                         │
   ├─ 保存评论 ──────────────┤
   │                         ├─ 设置上下文
   ├─ 返回用户 ◄────────────┤
   │                         ├─ 调用AI API
   │                         ├─ 等待响应...
   │                         ├─ 保存AI回复
   │                         └─ 清理上下文
```

### 5.4 提示词注入机制
```
普通用户聊天历史: [Q1, A1, Q2, A2, ...]
机器人聊天历史:   [Q1, A1, Q2, A2, ..., PROMPT]
                                          ↑
                              listHistory()时自动添加
```

---

## 六、面试问答

### Q1: 为什么使用 `topCommentId + userId` 作为会话标识？
**A**:
1. 顶级评论ID保证同一评论树下的对话连贯
2. 用户ID保证不同用户的对话隔离
3. 避免多人参与时上下文交叉，如A和B同时@机器人，各自的对话不会混在一起

### Q2: 为什么机器人要豁免敏感词检测？
**A**:
1. 机器人的"提问"实际是用户的评论内容
2. 用户评论已在发布时做过审核
3. 避免机器人因触发敏感词而无法正常工作

### Q3: Guava缓存在这里的作用？
**A**:
1. 机器人用户信息相对固定，无需每次查库
2. `memoizeWithExpiration` 提供1小时过期，平衡缓存效率和数据一致性
3. 懒加载模式，首次调用才查询，启动时不占用资源

### Q4: 为什么用 `SpringUtil.getBean(CommentWriteService.class).saveComment()` 而不是 `this.saveComment()`？
**A**:
1. 直接调用 `this.saveComment()` 不会经过Spring AOP代理
2. `@Transactional` 注解不会生效
3. 通过容器获取代理对象，确保事务正确开启

### Q5: 如何保证异步线程中的上下文正确传递？
**A**:
1. 使用阿里 `TransmittableThreadLocal (TTL)` 替代普通 ThreadLocal
2. 线程池用 `TtlExecutors.getTtlExecutorService()` 包装
3. 支持线程池复用场景下的上下文传递

---

## 七、扩展方向

### 7.1 添加新机器人
```java
// 1. 在枚举中添加新机器人
public enum AiBotEnum {
    HATER_BOT(...),
    HELPER_BOT("helperBot", "小助手", "你是一个友善的技术问答助手..."),
    ;
}

// 2. 创建新的Bot组件
@Component
public class HelperBot {
    // 类似HaterBot实现
}

// 3. 在AiBots中注册
@Service
public class AiBots {
    @Autowired
    private HaterBot haterBot;
    @Autowired
    private HelperBot helperBot;

    public boolean aiBots(Long userId) {
        return Objects.equals(userId, haterBot.getBotUser().getUserId())
            || Objects.equals(userId, helperBot.getBotUser().getUserId());
    }
}
```

### 7.2 支持更多触发方式
- 私信触发
- 特定标签触发
- 定时任务触发（如每日总结）

### 7.3 增强对话能力
- 接入RAG检索增强
- 支持文章内容作为上下文
- 多模态回复（图片、代码块）
