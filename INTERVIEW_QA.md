# Engineers Hub 面试问答手册

## 一、敏感词热更新

### Q1：敏感词热更新是怎么实现的？

**回答**：

整体分三层：

1. **配置加载**：敏感词存在数据库 `global_conf` 表，应用启动时通过 `DynamicConfigContainer` 加载到内存
2. **定时刷新**：后台有个定时任务，每 5 分钟从数据库拉取配置，和内存做对比，有变化就刷新
3. **回调机制**：配置变更后，触发 `SensitiveService.refresh()` 重建敏感词引擎

```java
// SensitiveService.java
@PostConstruct
public void refresh() {
    // 注册配置刷新回调
    dynamicConfigContainer.registerRefreshCallback(sensitiveConfig, this::refresh);

    // 合并系统词库 + 自定义词库
    IWordDeny deny = () -> {
        List<String> sub = WordDenySystem.getInstance().deny();
        sub.addAll(sensitiveConfig.getDeny());  // 数据库配置的deny词
        return sub;
    };

    // 重建敏感词引擎
    sensitiveWordBs = SensitiveWordBs.newInstance()
            .wordDeny(deny)
            .wordAllow(allow)
            .init();
}
```

---

### Q2：allow/deny 是什么意思？为什么要合并？

**回答**：

- **deny**：黑名单，需要过滤的敏感词，如"赌博"、"色情"
- **allow**：白名单，不应该被误判的词，如"胸"在医学文章里不算敏感词

**合并逻辑**：
```java
IWordDeny deny = () -> {
    List<String> sub = WordDenySystem.getInstance().deny();  // 系统内置词库
    sub.addAll(sensitiveConfig.getDeny());                   // 数据库自定义词库
    return sub;
};
```

系统内置了一套基础词库，我们再从数据库加载运营配置的词，两者合并使用。这样既有基础保障，又能灵活扩展。

---

### Q3：命中统计是怎么做的？

**回答**：

用 Redis Hash 存储，key 是 `sensitive_word`，field 是敏感词，value 是命中次数。

```java
// SensitiveService.java:77-80
public List<String> contains(String txt) {
    List<String> ans = sensitiveWordBs.findAll(txt);

    // 敏感词命中次数+1，用 Pipeline 批量操作
    RedisClient.PipelineAction action = RedisClient.pipelineAction();
    ans.forEach(key -> action.add(SENSITIVE_WORD_CNT_PREFIX, key,
        (connection, k, v) -> connection.hIncrBy(k, v, 1)));
    action.execute();
    return ans;
}
```

**为什么用 Pipeline？** 如果一段文本命中了 5 个敏感词，用 Pipeline 可以把 5 次 HINCRBY 合并成 1 次网络请求，减少网络开销。

---

### Q4：敏感词匹配用的什么算法？

**回答**：

用的是开源库 `sensitive-word`，底层是 **DFA（确定有限自动机）** 算法。

**原理**：把所有敏感词构建成一棵 Trie 树，匹配时只需要遍历一次文本，时间复杂度是 O(n)，n 是文本长度，和敏感词数量无关。

---

### Q5：如果敏感词库很大（比如 10 万个词），会有什么问题？

**回答**：

1. **内存占用**：DFA 会把词库加载到内存，10 万词大概几十 MB，一般能接受
2. **刷新阻塞**：`refresh()` 时重建引擎会有短暂阻塞，可以考虑双缓冲（新引擎构建好再切换）
3. **数据库压力**：定时全量拉取，可以改成增量更新（记录配置版本号）

---

### Q6：为什么用 volatile 修饰 sensitiveWordBs？

```java
private volatile SensitiveWordBs sensitiveWordBs;
```

**回答**：

因为 `sensitiveWordBs` 在定时刷新时会被重新赋值，而 `contains()` 方法在多线程环境下被调用。用 `volatile` 保证可见性——一个线程修改后，其他线程能立即看到新值。

---

## 二、AI 辅助问答

### Q1：统一 ChatService 接口是怎么设计的？

**回答**：

用了**工厂模式 + 策略模式**：

```java
// ChatServiceFactory.java
@Component
public class ChatServiceFactory {
    private final Map<AISourceEnum, ChatService> chatServiceMap;

    // Spring 自动注入所有 ChatService 实现类
    public ChatServiceFactory(List<ChatService> chatServiceList) {
        chatServiceMap = Maps.newHashMapWithExpectedSize(chatServiceList.size());
        for (ChatService chatService : chatServiceList) {
            chatServiceMap.put(chatService.source(), chatService);
        }
    }

    public ChatService getChatService(AISourceEnum aiSource) {
        return chatServiceMap.get(aiSource);
    }
}
```

**ChatService 接口**定义了统一方法：
- `chat(userId, question)` - 同步聊天
- `asyncChat(userId, question, callback)` - 异步聊天
- `source()` - 返回模型类型（DeepSeek/ChatGPT 等）

每个模型有自己的实现类：`DeepSeekChatServiceImpl`、`ChatGptAiServiceImpl` 等。

---

### Q2：怎么切换模型？

**回答**：

通过配置切换：

```java
// ChatFacade.java
public AISourceEnum getRecommendAiSource() {
    // 按优先级检查哪个模型可用
    if (ChatGPT 配置了 Key) return AISourceEnum.CHAT_GPT_3_5;
    if (智谱 配置了 Key) return AISourceEnum.ZHI_PU_AI;
    if (DeepSeek 配置了) return AISourceEnum.DEEP_SEEK;
    // ...
    return AISourceEnum.PAI_AI;  // 兜底
}
```

运营只需要在配置文件里填哪个模型的 API Key，系统自动选择可用的模型。

---

### Q3：频控是怎么实现的？

**回答**：

基于 Redis Hash 实现每日调用次数限制：

```java
// AbsChatService.java
// Key: ai_rate_limit_{source}_{yyyyMMdd}，Field: userId，Value: 调用次数
protected int queryUserdCnt(Long user) {
    Integer cnt = RedisClient.hGet(
        ChatConstants.getAiRateKeyPerDay(source()),  // 按天生成 Key
        String.valueOf(user),
        Integer.class);
    return cnt == null ? 0 : cnt;
}

protected Long incrCnt(Long user) {
    String key = ChatConstants.getAiRateKeyPerDay(source());
    Long cnt = RedisClient.hIncr(key, String.valueOf(user), 1);
    if (cnt == 1L) {
        RedisClient.expire(key, 86400L);  // 第一次调用时设置 24 小时过期
    }
    return cnt;
}
```

**次数规则**（UserAiServiceImpl.java:54-96）：
- 基础用户：5 次/天
- 微信登录用户：+5 次
- 星球用户：+100 次
- 邀请新用户：+10%

---

### Q4：同步和异步聊天有什么区别？

**回答**：

- **同步**：调用 API 后等待返回，适合响应快的场景
- **异步**：调用后立即返回，结果通过回调或 WebSocket 推送，适合流式返回

```java
// AbsChatService.java
public ChatRecordsVo asyncChat(Long user, String question, Consumer<ChatRecordsVo> consumer) {
    // ...
    AiChatStatEnum needReturn = doAsyncAnswer(user, newRes, (ans, vo) -> {
        if (ans == AiChatStatEnum.END) {
            processAfterSuccessedAnswered(user, newRes);  // 保存记录、扣次数
        }
        consumer.accept(newRes);  // 回调通知
    });
    // ...
}
```

---

### Q5：大模型 API 调用失败怎么处理？

**回答**：

有降级机制：

```java
// ChatFacade.java
public void refreshAiSourceCache(Set<AISourceEnum> except) {
    refreshAiSourceCache(getRecommendAiSource(except));  // 排除失败的模型，选下一个
}

// 调用失败时
if (ans == AiChatStatEnum.ERROR) {
    SpringUtil.getBean(ChatFacade.class).refreshAiSourceCache(Sets.newHashSet(source()));
}
```

比如 DeepSeek 挂了，自动切换到 ChatGPT 或其他可用模型。

---

## 三、知识贡献激励

### Q1：计分规则是怎么设计的？

**回答**：

```java
// UserActivityRankServiceImpl.java:68-96
if (activityScore.getPraise() != null) {
    field += "praise";
    score = BooleanUtils.isTrue(activityScore.getPraise()) ? 2 : -2;  // 点赞+2，取消-2
} else if (activityScore.getCollect() != null) {
    field += "collect";
    score = BooleanUtils.isTrue(activityScore.getCollect()) ? 2 : -2;  // 收藏+2，取消-2
} else if (activityScore.getRate() != null) {
    field += "rate";
    score = BooleanUtils.isTrue(activityScore.getRate()) ? 3 : -3;    // 评论+3
} else if (BooleanUtils.isTrue(activityScore.getPublishArticle())) {
    field += "publish";
    score += 10;  // 发文+10
} else if (activityScore.getFollowedUserId() != null) {
    field = activityScore.getFollowedUserId() + "_follow";
    score = BooleanUtils.isTrue(activityScore.getFollow()) ? 2 : -2;  // 关注+2
}
```

| 行为 | 积分 | 取消 |
|-----|-----|-----|
| 发文 | +10 | - |
| 评论 | +3 | -3 |
| 点赞 | +2 | -2 |
| 收藏 | +2 | -2 |
| 关注 | +2 | -2 |

---

### Q2：幂等防刷是怎么实现的？

**回答**：

用 Redis Hash 记录用户当天的操作明细：

```java
// Key: activity_rank_{userId}_{yyyyMMdd}
// Field: {articleId}_praise / {articleId}_collect 等
// Value: 加过的分数

final String userActionKey = ACTIVITY_SCORE_KEY + userId +
    DateUtil.format(DateTimeFormatter.ofPattern("yyyyMMdd"), System.currentTimeMillis());

Integer ans = RedisClient.hGet(userActionKey, field, Integer.class);
if (ans == null) {
    // 之前没加过分，执行加分
    if (score > 0) {
        RedisClient.hSet(userActionKey, field, score);  // 记录加分记录
        RedisClient.zIncrBy(todayRankKey, String visitorId visitorId userId), score);  // 更新排行榜
    }
} else if (ans > 0 && score < 0) {
    // 之前加过分，现在取消（如取消点赞）
    RedisClient.hDel(userActionKey, field);  // 删除记录
    RedisClient.zIncrBy(todayRankKey, String.valueOf(userId), score);  // 扣分
}
```

**核心逻辑**：
1. 加分前先查 Hash，存在就不加（幂等）
2. 取消时查 Hash，存在才扣分，然后删除记录
3. 这样用户反复点赞/取消，积分不会异常

---

### Q3：日榜和月榜怎么实现的？

**回答**：

用 Redis ZSet，key 按日期动态生成：

```java
// 日榜 Key: activity_rank_20241205
private String todayRankKey() {
    return ACTIVITY_SCORE_KEY + DateUtil.format(
        DateTimeFormatter.ofPattern("yyyyMMdd"), System.currentTimeMillis());
}

// 月榜 Key: activity_rank_202412
private String monthRankKey() {
    return ACTIVITY_SCORE_KEY + DateUtil.format(
        DateTimeFormatter.ofPattern("yyyyMM"), System.currentTimeMillis());
}
```

**加分时同时更新日榜和月榜**：
```java
RedisClient.zIncrBy(todayRankKey, String.valueOf(userId), score);
RedisClient.zIncrBy(monthRankKey, String.valueOf(userId), score);
```

**查询 Top N**：
```java
List<ImmutablePair<String, Double>> rankList = RedisClient.zTopNScore(rankKey, size);
```

---

### Q4：TTL 是怎么控制的？

**回答**：

```java
// 日榜保存 31 天
Long ttl = RedisClient.ttl(todayRankKey);
if (!NumUtil.upZero(ttl)) {
    RedisClient.expire(todayRankKey, 31 * DateUtil.ONE_DAY_SECONDS);
}

// 月榜保存 1 年
ttl = RedisClient.ttl(monthRankKey);
if (!NumUtil.upZero(ttl)) {
    RedisClient.expire(monthRankKey, 12 * DateUtil.ONE_MONTH_SECONDS);
}

// 用户每日操作记录保存 31 天
RedisClient.expire(userActionKey, 31 * DateUtil.ONE_DAY_SECONDS);
```

**为什么这么设计**：
- 日榜 31 天后自动删除，不占用 Redis 内存
- 月榜 1 年后删除，可以查历史
- 用户操作记录 31 天够用（用于幂等判断）

---

### Q5：高并发下会有什么问题？

**回答**：

**潜在问题**：
1. **Redis 操作非原子**：查 Hash → 判断 → 写 ZSet 不是原子操作，极端情况可能重复加分
2. **并发写同一个 Key**：多个用户同时操作，ZSet 的 ZINCRBY 是原子的没问题

**解决方案**：
- 目前的实现对于社区场景够用，积分少量偏差可接受
- 如果要求严格，可以用 Lua 脚本保证原子性

---

## 四、通用问题

### Q1：为什么选这三个功能做亮点？

**回答**：
1. **敏感词**：UGC 平台必须有内容管控，体现对安全的考虑
2. **AI 问答**：技术热点，体现学习能力和对新技术的应用
3. **激励体系**：产品运营角度，体现对业务的理解

三个点覆盖了**安全、技术、业务**，比较全面。

---

### Q2：项目中遇到过什么困难？

**回答**：

**敏感词刷新的并发问题**：
最开始没加 `volatile`，测试时发现偶尔配置更新后不生效。排查发现是多线程可见性问题，加了 `volatile` 解决。

**AI 接口超时**：
大模型 API 有时候响应很慢，同步调用会阻塞用户。后来改成异步 + 流式返回，用户体验好多了。

---

### Q3：如果让你优化，你会怎么做？

**回答**：

1. **敏感词**：改成增量更新，不用每次全量加载
2. **AI 问答**：加入对话历史持久化，支持多轮上下文
3. **积分系统**：加入 Lua 脚本保证原子性，或者考虑用消息队列异步处理
