# AI评论机器人 - 源码阅读路线图

## 阅读顺序总览

```
Phase 1: 入口层（理解触发机制）
    ↓
Phase 2: 机器人层（理解异步调度）
    ↓
Phase 3: AI服务层（理解聊天架构）
    ↓
Phase 4: 集成层（理解模型对接）
    ↓
Phase 5: 回调层（理解闭环流程）
```

---

## Phase 1: 入口层 - 评论触发

### 1.1 先读这个文件
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/comment/service/impl/CommentWriteServiceImpl.java
```

### 1.2 重点关注方法（按顺序）

| 顺序 | 方法 | 作用 | 行号参考 |
|------|------|------|----------|
| 1 | `saveComment()` | 入口方法，保存评论 | 找@Override |
| 2 | `addComment()` | 核心逻辑，调用haterBotTrigger | 内部私有方法 |
| 3 | `haterBotTrigger()` | **关键**：触发检测逻辑 | 搜索这个方法名 |
| 4 | `aiReply()` | 回调方法，保存AI回复 | 搜索这个方法名 |

### 1.3 阅读时思考的问题
```
□ 触发条件有几种？分别是什么？
□ "@杠精机器人" 这个字符串是硬编码还是配置的？
□ 会话标识 sourceBizId 是怎么构建的？为什么这样设计？
□ 为什么 aiReply() 要用 SpringUtil.getBean() 而不是 this？
```

### 1.4 关联阅读
```
paicoding-api/src/main/java/com/github/paicoding/forum/api/model/enums/ai/AiBotEnum.java
```
- 理解机器人配置是如何定义的
- 看 userName / nickName / prompt 三个字段的用途

---

## Phase 2: 机器人层 - 异步调度

### 2.1 核心文件
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/bot/HaterBot.java
```

### 2.2 重点关注

| 顺序 | 代码块 | 理解要点 |
|------|--------|----------|
| 1 | `Suppliers.memoizeWithExpiration()` | Guava缓存机制 |
| 2 | `trigger()` 方法 | 异步执行入口 |
| 3 | `AsyncUtil.execute()` | 线程池提交 |
| 4 | `ReqInfoContext.addReqInfo()` | 上下文设置 |
| 5 | `chatFacade.autoChat()` | AI调用 |
| 6 | `consumer.accept()` | 回调触发 |

### 2.3 阅读时思考的问题
```
□ 为什么用 Guava Suppliers 而不是 @Cacheable？
□ 1小时过期时间是怎么权衡的？
□ ReqInfoContext 设置了哪些字段？每个字段的作用？
□ chatId 在后续流程中怎么用的？
□ 为什么最后要 ReqInfoContext.clear()？
```

### 2.4 关联阅读
```
paicoding-core/src/main/java/com/github/paicoding/forum/core/async/AsyncUtil.java
```
重点看：
- 线程池配置参数（core/max/queue/handler）
- `TtlExecutors.getTtlExecutorService()` 的作用
- 为什么用 `SynchronousQueue` 而不是有界队列

```
paicoding-api/src/main/java/com/github/paicoding/forum/api/model/context/ReqInfoContext.java
```
重点看：
- `TransmittableThreadLocal` 和普通 ThreadLocal 的区别
- ReqInfo 包含哪些字段

---

## Phase 3: AI服务层 - 聊天架构

### 3.1 门面类
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/ChatFacade.java
```

重点方法：
| 方法 | 作用 |
|------|------|
| `autoChat()` | 自动选择同步/异步模式 |
| `asyncChat()` | 异步聊天入口 |
| `getRecommendAiSource()` | AI模型选择策略 |

### 3.2 抽象服务类（**最重要**）
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/AbsChatService.java
```

**按顺序阅读这些方法**：
| 顺序 | 方法 | 核心逻辑 |
|------|------|----------|
| 1 | `asyncChat()` | 入口，包含敏感词校验 |
| 2 | `initResVo()` | 构建响应对象 |
| 3 | `buildChatContext()` | **关键**：构建聊天上下文 |
| 4 | `doAsyncAnswer()` | 抽象方法，子类实现 |
| 5 | `processAfterSuccessedAnswered()` | 后置处理 |

### 3.3 阅读时思考的问题
```
□ 敏感词校验为什么要判断 aiBots(user)？
□ buildChatContext() 为什么要过滤 PROMPT_TAG 之前的消息？
□ 模板方法模式在这里是怎么体现的？
□ 为什么用 BiConsumer 而不是直接返回结果？
```

### 3.4 历史记录服务
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/history/ChatHistoryServiceImpl.java
```

重点看 `listHistory()` 方法：
- 如何从 Redis 获取历史记录
- `aiBots.autoBuildPrompt()` 在这里被调用
- 提示词是怎么被添加到历史记录中的

### 3.5 机器人管理服务
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/bot/AiBots.java
```
- `aiBots()` 判断是否为机器人用户
- `autoBuildPrompt()` 自动构建提示词

---

## Phase 4: 集成层 - 模型对接

### 4.1 具体实现类
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/impl/deepseek/DeepSeekChatServiceImpl.java
```

重点看 `doAsyncAnswer()` 方法：
- 如何创建 `AbstractStreamListener`
- 各个回调方法的作用（onOpen/onMsg/onClosed/onError）
- `setOnComplate()` 回调的触发时机

### 4.2 HTTP集成类
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/impl/deepseek/DeepSeekIntegration.java
```

按顺序阅读：
| 顺序 | 方法/类 | 理解要点 |
|------|---------|----------|
| 1 | `DeepSeekConf` | 配置类，看配置项 |
| 2 | `init()` | OkHttp客户端初始化 |
| 3 | `streamReturn()` | 流式请求入口 |
| 4 | `executeStreamChat()` | 构建HTTP请求 |
| 5 | `toMsg()` | **关键**：消息转换逻辑 |
| 6 | `ChatMsg` / `ChatReq` | 请求实体结构 |

### 4.3 阅读时思考的问题
```
□ toMsg() 是怎么区分 system/user/assistant 角色的？
□ PROMPT_TAG 前缀在这里是怎么处理的？
□ 为什么用 OkHttp SSE 而不是 WebSocket？
□ 超时时间是怎么配置的？
```

### 4.4 常量定义
```
paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/constants/ChatConstants.java
```
- `PROMPT_TAG = "prompt-"` 的定义
- Redis Key 的构建规则
- 各种提示文案

---

## Phase 5: 回调层 - 闭环流程

### 5.1 回到入口文件
```
CommentWriteServiceImpl.java -> aiReply() 方法
```

理解完整闭环：
```
触发 → 异步执行 → AI调用 → 回调 → 保存评论 → 完成
```

### 5.2 思考整体架构
```
□ 整个流程中有几次异步？
□ 事务边界在哪里？
□ 如果AI调用失败，会发生什么？
□ 如果保存评论失败，会发生什么？
```

---

## 源码文件清单（按阅读顺序）

```
# Phase 1: 入口层
1. paicoding-api/.../enums/ai/AiBotEnum.java
2. paicoding-service/.../comment/service/impl/CommentWriteServiceImpl.java

# Phase 2: 机器人层
3. paicoding-service/.../chatai/bot/HaterBot.java
4. paicoding-service/.../chatai/bot/AiBots.java
5. paicoding-core/.../async/AsyncUtil.java
6. paicoding-api/.../context/ReqInfoContext.java

# Phase 3: AI服务层
7. paicoding-service/.../chatai/ChatFacade.java
8. paicoding-service/.../chatai/service/AbsChatService.java
9. paicoding-service/.../chatai/service/history/ChatHistoryServiceImpl.java
10. paicoding-service/.../chatai/constants/ChatConstants.java

# Phase 4: 集成层
11. paicoding-service/.../chatai/service/impl/deepseek/DeepSeekChatServiceImpl.java
12. paicoding-service/.../chatai/service/impl/deepseek/DeepSeekIntegration.java
```

---

## 调试技巧

### 本地调试入口
```java
// 在 CommentWriteServiceImpl.haterBotTrigger() 打断点
// 发表评论内容包含 "@杠精机器人" 即可触发
```

### 关键日志
```java
// HaterBot.java
log.info("评论「{}」 开启了在线互怼模式", comment);

// DeepSeekChatServiceImpl.java
log.debug("DeepSeek返回内容: {}", lastMessage);
```

### Redis查看
```bash
# 查看聊天历史
redis-cli KEYS "chat.history.*"

# 查看具体会话
redis-cli LRANGE "chat.history.deep_seek.{userId}:comment:{topCommentId}_{userId}" 0 -1
```

---

## 画图理解

建议你画两张图：

### 1. 类关系图
```
CommentWriteServiceImpl
    │
    └──► HaterBot
            │
            ├──► ChatFacade
            │       │
            │       └──► ChatServiceFactory
            │               │
            │               └──► DeepSeekChatServiceImpl
            │                       │
            │                       └──► DeepSeekIntegration
            │
            └──► AiBots
                    │
                    └──► ChatHistoryServiceImpl
```

### 2. 时序图
```
User → CommentWriteService → HaterBot → ChatFacade → AbsChatService
                                                          │
                                                          ▼
                                               DeepSeekChatServiceImpl
                                                          │
                                                          ▼
                                               DeepSeekIntegration
                                                          │
                                                          ▼
                                                    DeepSeek API
                                                          │
                                                          ▼
                                               (回调链路反向执行)
```

---

## 面试时如何讲解

### 30秒版本
> 用户在评论区@杠精机器人，系统检测到关键词后异步触发AI对话。通过会话ID隔离不同用户的对话上下文，调用DeepSeek API获取回复后，以机器人身份发表评论，形成人机互动。

### 2分钟版本
> 这个功能分为五层：
> 1. **触发层**：CommentWriteService检测评论内容是否包含@机器人，或者是否回复机器人的评论
> 2. **调度层**：HaterBot通过AsyncUtil异步执行，避免阻塞主流程，同时设置ReqInfoContext传递会话标识
> 3. **服务层**：AbsChatService使用模板方法模式，统一处理敏感词校验、历史记录构建、调用次数统计
> 4. **集成层**：DeepSeekIntegration通过OkHttp SSE与AI模型交互，流式返回结果
> 5. **回调层**：AI返回后通过Consumer回调，将回复保存为新评论
>
> 技术亮点：会话隔离用topCommentId+userId组合，保证多人对话不混乱；用Guava缓存机器人用户信息；用TTL线程池传递上下文。

---

## 扩展阅读

如果想更深入理解，可以继续看：

1. **工厂模式**：`ChatServiceFactory.java` - 多AI模型切换
2. **频率限制**：`AbsChatService.queryUserdCnt()` - Redis计数
3. **用户注册**：`RegisterService.registerSystemUser()` - 系统用户自动创建
4. **敏感词**：`SensitiveService.contains()` - 敏感词检测
