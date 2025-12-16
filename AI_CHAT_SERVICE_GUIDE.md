# AI 辅助问答功能 - 完整实现链路

本文档详细介绍 AI 聊天功能的完整实现，包含：
1. **统一 ChatService 接口设计**
2. **工厂模式实现多模型切换**（DeepSeek/ChatGPT/智谱/讯飞/阿里/豆包）
3. **基于 Redis 的每日调用频控**

---

## 一、整体架构

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                           AI 聊天系统架构图                                        │
└─────────────────────────────────────────────────────────────────────────────────┘

                         ┌─────────────────────┐
                         │   ChatRestController │  ←── WebSocket/REST 入口
                         │   (STOMP协议)        │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │     ChatFacade      │  ←── 门面类（统一入口）
                         │   自动选择AI模型     │
                         └──────────┬──────────┘
                                    │
                                    ▼
                         ┌─────────────────────┐
                         │  ChatServiceFactory │  ←── 工厂类（策略选择）
                         │  Map<AISource,Svc>  │
                         └──────────┬──────────┘
                                    │
          ┌─────────────────────────┼─────────────────────────┐
          │                         │                         │
          ▼                         ▼                         ▼
   ┌─────────────┐          ┌─────────────┐          ┌─────────────┐
   │ DeepSeek    │          │  ChatGPT    │          │  智谱/讯飞   │
   │ ServiceImpl │          │ ServiceImpl │          │ ServiceImpl │
   └──────┬──────┘          └──────┬──────┘          └──────┬──────┘
          │                        │                        │
          └────────────────────────┼────────────────────────┘
                                   │
                                   ▼
                         ┌─────────────────────┐
                         │   AbsChatService    │  ←── 抽象基类（模板方法）
                         │  - 频控检查          │
                         │  - 敏感词检测        │
                         │  - 次数统计          │
                         └──────────┬──────────┘
                                    │
                    ┌───────────────┼───────────────┐
                    ▼                               ▼
             ┌─────────────┐                 ┌─────────────┐
             │    Redis    │                 │   MySQL     │
             │ 每日使用次数  │                 │ 聊天历史记录 │
             └─────────────┘                 └─────────────┘
```

---

## 二、支持的AI模型

### 2.1 AISourceEnum 枚举

**文件**: `paicoding-api/.../enums/ai/AISourceEnum.java`

```java
@Getter
public enum AISourceEnum {
    CHAT_GPT_3_5(0, "chatGpt3.5"),
    CHAT_GPT_4(1, "chatGpt4"),
    PAI_AI(2, "技术派"),           // 模拟AI
    XUN_FEI_AI(3, "讯飞") {
        @Override
        public boolean syncSupport() { return false; }  // 仅支持异步
    },
    ZHI_PU_AI(4, "智谱") {
        @Override
        public boolean asyncSupport() { return true; }
    },
    ALI_AI(5, "阿里"),
    DEEP_SEEK(6, "DeepSeek"),
    DOU_BAO_AI(7, "豆包");

    private String name;
    private Integer code;

    /** 是否支持同步调用 */
    public boolean syncSupport() { return true; }

    /** 是否支持异步调用 */
    public boolean asyncSupport() { return true; }
}
```

### 2.2 模型特性对比

| 模型 | 同步支持 | 异步支持 | 流式返回 | 说明 |
|-----|---------|---------|---------|------|
| ChatGPT 3.5/4 | ✅ | ✅ | ✅ | OpenAI 官方 |
| DeepSeek | ✅ | ✅ | ✅ | 深度求索 |
| 智谱 AI | ✅ | ✅ | ✅ | 清华系 |
| 讯飞 AI | ❌ | ✅ | ✅ | 仅异步 |
| 阿里 AI | ✅ | ✅ | ✅ | 通义千问 |
| 豆包 AI | ✅ | ✅ | ✅ | 字节跳动 |
| PAI_AI | ✅ | ✅ | ❌ | 模拟/兜底 |

---

## 三、统一 ChatService 接口

### 3.1 接口定义

**文件**: `paicoding-service/.../chatai/service/ChatService.java`

```java
/**
 * AI 聊天服务统一接口
 * 所有 AI 模型实现类都必须实现此接口
 */
public interface ChatService {

    /**
     * 返回当前服务对应的 AI 模型类型
     */
    AISourceEnum source();

    /**
     * 是否优先使用异步方式
     * @return true: 异步优先; false: 同步优先
     */
    default boolean asyncFirst() {
        return true;
    }

    /**
     * 同步聊天（阻塞等待结果）
     *
     * @param user     用户ID
     * @param question 提问内容
     * @return 聊天结果
     */
    ChatRecordsVo chat(Long user, String question);

    /**
     * 同步聊天（带回调）
     *
     * @param user     用户ID
     * @param question 提问内容
     * @param consumer 结果回调
     */
    ChatRecordsVo chat(Long user, String question, Consumer<ChatRecordsVo> consumer);

    /**
     * 异步聊天（非阻塞，通过回调返回结果）
     *
     * @param user     用户ID
     * @param question 提问内容
     * @param consumer 异步回调
     */
    ChatRecordsVo asyncChat(Long user, String question, Consumer<ChatRecordsVo> consumer);

    /**
     * 获取聊天历史记录
     */
    ChatRecordsVo getChatHistory(Long user, AISourceEnum aiSource);
}
```

### 3.2 抽象基类（模板方法模式）

**文件**: `paicoding-service/.../chatai/service/AbsChatService.java`

```java
/**
 * 聊天服务抽象基类
 *
 * 实现了：
 * 1. 每日调用频控（Redis）
 * 2. 敏感词检测
 * 3. 聊天历史管理
 * 4. 使用次数统计
 */
@Slf4j
@Service
public abstract class AbsChatService implements ChatService {

    @Autowired
    private UserAiService userAiService;
    @Autowired
    private SensitiveService sensitiveService;
    @Autowired
    private ChatHistoryService chatHistoryService;

    @Value("${ai.maxNum.historyContextCnt:10}")
    protected Integer chatHistoryContextNum;

    // ==================== 频控相关方法 ====================

    /**
     * 查询用户今日已使用次数
     * Redis Key: chat.rates.{ai_source}-{date}
     */
    protected int queryUserdCnt(Long user) {
        Integer cnt = RedisClient.hGet(
            ChatConstants.getAiRateKeyPerDay(source()),  // chat.rates.deep_seek-2025-01-15
            String.valueOf(user),
            Integer.class
        );
        return cnt == null ? 0 : cnt;
    }

    /**
     * 使用次数 +1
     */
    protected Long incrCnt(Long user) {
        String key = ChatConstants.getAiRateKeyPerDay(source());
        Long cnt = RedisClient.hIncr(key, String.valueOf(user), 1);
        if (cnt == 1L) {
            // 第一次使用，设置 24 小时过期
            RedisClient.expire(key, 86400L);
        }
        return cnt;
    }

    // ==================== 聊天核心逻辑 ====================

    @Override
    public ChatRecordsVo chat(Long user, String question) {
        ChatRecordsVo res = initResVo(user, question);
        if (!res.hasQaCnt()) {
            return res;  // 次数用完
        }
        answer(user, res);
        return res;
    }

    /**
     * 初始化响应对象，检查使用次数
     */
    private ChatRecordsVo initResVo(Long user, String question) {
        ChatRecordsVo res = new ChatRecordsVo();
        res.setSource(source());

        // ⭐ 获取用户最大可用次数和已用次数
        int maxCnt = getMaxQaCnt(user);
        int usedCnt = queryUserdCnt(user);
        res.setMaxCnt(maxCnt);
        res.setUsedCnt(usedCnt);

        ChatItemVo item = new ChatItemVo().initQuestion(question);

        // 检查次数是否用完
        if (!res.hasQaCnt()) {
            item.initAnswer(ChatConstants.TOKEN_OVER);  // "您的免费次数已经使用完毕了!"
            res.setRecords(Arrays.asList(item));
            return res;
        }

        // 构建多轮对话上下文
        List<ChatItemVo> history = buildChatContext(user);
        history.add(0, item);
        res.setRecords(history);
        return res;
    }

    /**
     * 执行问答（包含敏感词检测）
     */
    protected AiChatStatEnum answer(Long user, ChatRecordsVo res) {
        ChatItemVo itemVo = res.getRecords().get(0);

        // ⭐ 敏感词检测
        List<String> sensitiveWords = sensitiveService.contains(itemVo.getQuestion());
        if (!CollectionUtils.isEmpty(sensitiveWords)) {
            itemVo.initAnswer(String.format(ChatConstants.SENSITIVE_QUESTION, sensitiveWords));
            return AiChatStatEnum.ERROR;
        }

        // 调用子类实现的具体 AI 接口
        AiChatStatEnum ans = doAnswer(user, itemVo);
        if (ans == AiChatStatEnum.END) {
            processAfterSuccessedAnswered(user, res);
        }
        return ans;
    }

    /**
     * 成功回答后的处理：次数+1，保存记录
     */
    protected void processAfterSuccessedAnswered(Long user, ChatRecordsVo response) {
        response.setUsedCnt(incrCnt(user).intValue());  // ⭐ 次数+1
        recordChatItem(user, response.getRecords().get(0));  // 保存聊天记录
    }

    /**
     * 获取用户最大可用次数
     */
    protected int getMaxQaCnt(Long user) {
        return userAiService.getMaxChatCnt(user);
    }

    // ==================== 子类需实现的抽象方法 ====================

    /** 同步调用 AI 接口 */
    public abstract AiChatStatEnum doAnswer(Long user, ChatItemVo chat);

    /** 异步调用 AI 接口 */
    public abstract AiChatStatEnum doAsyncAnswer(Long user, ChatRecordsVo response,
                                                  BiConsumer<AiChatStatEnum, ChatRecordsVo> consumer);
}
```

---

## 四、工厂模式实现多模型切换

### 4.1 ChatServiceFactory（核心）

**文件**: `paicoding-service/.../chatai/service/ChatServiceFactory.java`

```java
/**
 * AI 聊天服务工厂
 *
 * 核心设计：
 * 1. Spring 自动注入所有 ChatService 实现类
 * 2. 构建 AISourceEnum -> ChatService 的映射
 * 3. 根据枚举快速获取对应实现
 */
@Component
public class ChatServiceFactory {

    /** AI模型 -> 服务实现 的映射表 */
    private final Map<AISourceEnum, ChatService> chatServiceMap;

    /**
     * 构造函数注入
     * Spring 会自动注入所有实现了 ChatService 接口的 Bean
     */
    public ChatServiceFactory(List<ChatService> chatServiceList) {
        chatServiceMap = Maps.newHashMapWithExpectedSize(chatServiceList.size());

        // 遍历所有实现类，根据 source() 方法建立映射
        for (ChatService chatService : chatServiceList) {
            chatServiceMap.put(chatService.source(), chatService);
        }
        // 最终 Map 结构：
        // {
        //   DEEP_SEEK -> DeepSeekChatServiceImpl,
        //   CHAT_GPT_3_5 -> ChatGptAiServiceImpl,
        //   ZHI_PU_AI -> ZhipuAiServiceImpl,
        //   ...
        // }
    }

    /**
     * 根据 AI 类型获取对应的服务实现
     */
    public ChatService getChatService(AISourceEnum aiSource) {
        return chatServiceMap.get(aiSource);
    }
}
```

### 4.2 ChatFacade 门面类

**文件**: `paicoding-service/.../chatai/ChatFacade.java`

```java
/**
 * 聊天门面类
 *
 * 职责：
 * 1. 自动选择推荐的 AI 模型
 * 2. 智能切换同步/异步调用方式
 * 3. 缓存当前推荐模型（10分钟）
 */
@Slf4j
@Service
public class ChatFacade {

    @Autowired
    private AiConfig aiConfig;
    @Autowired
    private ChatServiceFactory chatServiceFactory;

    /** Guava 缓存：推荐的 AI 模型（10分钟过期） */
    private Supplier<AISourceEnum> aiSourceCache;

    /**
     * 获取推荐的 AI 模型
     */
    public AISourceEnum getRecommendAiSource() {
        if (aiSourceCache == null) {
            refreshAiSourceCache(Collections.emptySet());
        }
        return aiSourceCache.get();
    }

    /**
     * 刷新推荐模型缓存
     */
    public void refreshAiSourceCache(AISourceEnum ai) {
        aiSourceCache = Suppliers.memoizeWithExpiration(() -> ai, 10, TimeUnit.MINUTES);
    }

    /**
     * 按优先级选择可用的 AI 模型
     * 优先级：ChatGPT > 智谱 > 讯飞 > 阿里 > DeepSeek > 豆包 > PAI_AI
     */
    private AISourceEnum getRecommendAiSource(Set<AISourceEnum> except) {
        AISourceEnum source;
        try {
            // 检查各模型配置是否可用
            if (!except.contains(AISourceEnum.CHAT_GPT_3_5) && chatGptConfigValid()) {
                source = AISourceEnum.CHAT_GPT_3_5;
            } else if (!except.contains(AISourceEnum.ZHI_PU_AI) && zhipuConfigValid()) {
                source = AISourceEnum.ZHI_PU_AI;
            } else if (!except.contains(AISourceEnum.XUN_FEI_AI) && xunfeiConfigValid()) {
                source = AISourceEnum.XUN_FEI_AI;
            } else if (!except.contains(AISourceEnum.ALI_AI)) {
                source = AISourceEnum.ALI_AI;
            } else if (!except.contains(AISourceEnum.DEEP_SEEK)) {
                source = AISourceEnum.DEEP_SEEK;
            } else if (!except.contains(AISourceEnum.DOU_BAO_AI)) {
                source = AISourceEnum.DOU_BAO_AI;
            } else {
                source = AISourceEnum.PAI_AI;  // 兜底
            }
        } catch (Exception e) {
            source = AISourceEnum.PAI_AI;
        }

        // 检查是否在配置的支持列表中
        if (source != AISourceEnum.PAI_AI && !aiConfig.getSource().contains(source)) {
            Set<AISourceEnum> totalExcepts = Sets.newHashSet(except);
            totalExcepts.add(source);
            return getRecommendAiSource(totalExcepts);  // 递归选择下一个
        }

        log.info("当前选中的AI模型：{}", source);
        return source;
    }

    /**
     * 自动聊天：智能选择同步/异步方式
     */
    public ChatRecordsVo autoChat(AISourceEnum source, String question, Consumer<ChatRecordsVo> callback) {
        // 如果模型支持异步且优先异步，则使用异步方式
        if (source.asyncSupport() && chatServiceFactory.getChatService(source).asyncFirst()) {
            return asyncChat(source, question, callback);
        }
        return chat(source, question, callback);
    }

    /**
     * 同步聊天
     */
    public ChatRecordsVo chat(AISourceEnum source, String question, Consumer<ChatRecordsVo> callback) {
        return chatServiceFactory.getChatService(source)
                .chat(ReqInfoContext.getReqInfo().getUserId(), question, callback);
    }

    /**
     * 异步聊天
     */
    public ChatRecordsVo asyncChat(AISourceEnum source, String question, Consumer<ChatRecordsVo> callback) {
        return chatServiceFactory.getChatService(source)
                .asyncChat(ReqInfoContext.getReqInfo().getUserId(), question, callback);
    }
}
```

---

## 五、具体 AI 模型实现

### 5.1 DeepSeek 实现

**文件**: `paicoding-service/.../chatai/service/impl/deepseek/DeepSeekChatServiceImpl.java`

```java
@Slf4j
@Service
public class DeepSeekChatServiceImpl extends AbsChatService {

    @Autowired
    private DeepSeekIntegration deepSeekIntegration;

    @Override
    public AISourceEnum source() {
        return AISourceEnum.DEEP_SEEK;  // 标识自己是 DeepSeek
    }

    /**
     * 同步调用（直接返回结果）
     */
    @Override
    public AiChatStatEnum doAnswer(Long user, ChatItemVo chat) {
        if (deepSeekIntegration.directReturn(chat)) {
            return AiChatStatEnum.END;
        }
        return AiChatStatEnum.ERROR;
    }

    /**
     * 异步流式调用（SSE 方式）
     */
    @Override
    public AiChatStatEnum doAsyncAnswer(Long user, ChatRecordsVo response,
                                         BiConsumer<AiChatStatEnum, ChatRecordsVo> consumer) {
        ChatItemVo item = response.getRecords().get(0);

        // 创建 SSE 流式监听器
        AbstractStreamListener listener = new AbstractStreamListener() {
            @Override
            public void onMsg(String message) {
                // 收到流式消息，追加到答案中
                if (StringUtils.isNotBlank(lastMessage)) {
                    item.appendAnswer(message);
                    consumer.accept(AiChatStatEnum.MID, response);  // 中间状态
                }
            }

            @Override
            public void onClosed(EventSource eventSource) {
                // 连接关闭
                if (item.getAnswerType() != ChatAnswerTypeEnum.STREAM_END) {
                    item.appendAnswer("\n").setAnswerType(ChatAnswerTypeEnum.STREAM_END);
                    consumer.accept(AiChatStatEnum.END, response);
                }
            }

            @Override
            public void onError(Throwable throwable, String res) {
                // 发生错误
                item.appendAnswer("Error:" + throwable.getMessage())
                    .setAnswerType(ChatAnswerTypeEnum.STREAM_END);
                consumer.accept(AiChatStatEnum.ERROR, response);
            }
        };

        // 注册完成回调
        listener.setOnComplate((s) -> {
            item.appendAnswer("\n").setAnswerType(ChatAnswerTypeEnum.STREAM_END);
            consumer.accept(AiChatStatEnum.END, response);
        });

        // 调用 DeepSeek API
        deepSeekIntegration.streamReturn(response.getRecords(), listener);
        return AiChatStatEnum.IGNORE;
    }
}
```

### 5.2 DeepSeek API 集成

**文件**: `paicoding-service/.../chatai/service/impl/deepseek/DeepSeekIntegration.java`

```java
@Slf4j
@Component
public class DeepSeekIntegration {

    @Autowired
    private DeepSeekConf deepSeekConf;

    private OkHttpClient okHttpClient;

    @PostConstruct
    public void init() {
        this.okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(deepSeekConf.getTimeout(), TimeUnit.SECONDS)
                .readTimeout(deepSeekConf.getTimeout(), TimeUnit.SECONDS)
                .writeTimeout(deepSeekConf.getTimeout(), TimeUnit.SECONDS)
                .build();
    }

    /**
     * 流式调用 DeepSeek API
     */
    public void streamReturn(List<ChatItemVo> list, EventSourceListener listener) {
        List<ChatMsg> msgList = ChatConstants.toMsgList(list, this::toMsg);
        executeStreamChat(msgList, listener);
    }

    private void executeStreamChat(List<ChatMsg> list, EventSourceListener listener) {
        ChatReq req = new ChatReq();
        req.setModel("deepseek-chat");  // 或 "deepseek-reasoner"
        req.setMessages(list);
        req.setStream(true);

        try {
            EventSource.Factory factory = EventSources.createFactory(okHttpClient);
            String body = JsonUtil.toStr(req);

            Request request = new Request.Builder()
                    .url(deepSeekConf.getApiHost() + "/chat/completions")
                    .addHeader("Authorization", "Bearer " + deepSeekConf.getApiKey())
                    .addHeader("Content-Type", "application/json")
                    .post(RequestBody.create(MediaType.parse("application/json"), body))
                    .build();

            factory.newEventSource(request, listener);
        } catch (Exception e) {
            log.error("DeepSeek请求失败: {}", req, e);
        }
    }

    // ==================== 内部类 ====================

    @Data
    @Component
    @ConfigurationProperties(prefix = "deepseek")
    private class DeepSeekConf {
        private String apiKey;
        private String apiHost;  // https://api.deepseek.com
        private Long timeout;
    }

    @Data
    public static class ChatReq {
        private String model;
        private boolean stream;
        private List<ChatMsg> messages;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class ChatMsg {
        private String role;     // system/user/assistant
        private String content;
    }
}
```

### 5.3 ChatGPT 实现

**文件**: `paicoding-service/.../chatai/service/impl/chatgpt/ChatGptAiServiceImpl.java`

```java
@Slf4j
@Service
public class ChatGptAiServiceImpl extends AbsChatService {

    @Autowired
    private ChatGptIntegration chatGptIntegration;

    @Override
    public AISourceEnum source() {
        return AISourceEnum.CHAT_GPT_3_5;
    }

    @Override
    public AiChatStatEnum doAnswer(Long user, ChatItemVo chat) {
        if (chatGptIntegration.directReturn(user, chat)) {
            return AiChatStatEnum.END;
        }
        return AiChatStatEnum.ERROR;
    }

    @Override
    public AiChatStatEnum doAsyncAnswer(Long user, ChatRecordsVo chatRes,
                                         BiConsumer<AiChatStatEnum, ChatRecordsVo> consumer) {
        ChatItemVo item = chatRes.getRecords().get(0);

        AbstractStreamListener listener = new AbstractStreamListener() {
            @Override
            public void onMsg(String message) {
                if (StringUtils.isNotBlank(message)) {
                    item.appendAnswer(message);
                    consumer.accept(AiChatStatEnum.MID, chatRes);
                }
            }
            // ... 其他回调方法
        };

        listener.setOnComplate((s) -> {
            item.appendAnswer("\n").setAnswerType(ChatAnswerTypeEnum.STREAM_END);
            consumer.accept(AiChatStatEnum.END, chatRes);
        });

        chatGptIntegration.streamReturn(user, chatRes.getRecords(), listener);
        return AiChatStatEnum.IGNORE;
    }

    @Override
    public boolean asyncFirst() {
        return true;  // ChatGPT 优先使用异步
    }
}
```

---

## 六、Redis 每日调用频控

### 6.1 频控设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Redis 频控数据结构                            │
└─────────────────────────────────────────────────────────────────────┘

Key 格式: chat.rates.{ai_source}-{date}
数据类型: Hash
过期时间: 24小时

示例:
┌────────────────────────────────────────────────────┐
│  Key: chat.rates.deep_seek-2025-01-15              │
├───────────────────┬────────────────────────────────┤
│      Field        │           Value                │
├───────────────────┼────────────────────────────────┤
│      10001        │             5                  │  ← 用户10001今日用了5次
│      10002        │             3                  │
│      10003        │            10                  │
└───────────────────┴────────────────────────────────┘
```

### 6.2 ChatConstants 常量定义

**文件**: `paicoding-service/.../chatai/constants/ChatConstants.java`

```java
public final class ChatConstants {

    /**
     * 生成每日频控的 Redis Key
     * 格式: chat.rates.{ai_source}-{date}
     */
    public static String getAiRateKeyPerDay(AISourceEnum ai) {
        return "chat.rates." + ai.name().toLowerCase() + "-" + LocalDate.now();
    }

    /**
     * 对话历史缓存 Key
     */
    public static String getAiHistoryRecordsKey(AISourceEnum ai, Long user) {
        return "chat.history." + ai.name().toLowerCase() + "." + user;
    }

    /** 默认最大使用次数 */
    public static final int MAX_CHATGPT_QAS_CNT = 10;

    /** 最多保存的历史记录条数 */
    public static final int MAX_HISTORY_RECORD_ITEMS = 500;

    /** 两次提问最小间隔（毫秒） */
    public static final long QAS_TIME_INTERVAL = 20_000;

    /** 次数用完提示 */
    public static final String TOKEN_OVER = "您的免费次数已经使用完毕了!";

    /** 敏感词提示 */
    public static final String SENSITIVE_QUESTION = "提问中包含敏感词:%s，请联系管理员加入白名单!";
}
```

### 6.3 用户次数策略配置

**文件**: `paicoding-service/.../user/service/conf/AiConfig.java`

```java
@Data
@Component
@ConfigurationProperties(prefix = "ai")
public class AiConfig {

    @Data
    public static class AiMaxChatNumStrategyConf {
        /** 基础用户次数 */
        private Integer basic;          // 默认 5 次

        /** 公众号用户次数 */
        private Integer wechat;         // 默认 +5 次

        /** 星球用户次数 */
        private Integer star;           // 默认 +100 次

        /** 星球试用次数 */
        private Integer starTry;        // 默认 +20 次

        /** 绑定邀请码增加比例 */
        private Float invited;          // 默认 10%

        /** 每邀请一人增加比例 */
        private Float inviteNum;        // 默认 20%

        /** 多轮对话上下文条数 */
        private Integer historyContextCnt;  // 默认 10 条
    }

    /** 次数策略配置 */
    private AiMaxChatNumStrategyConf maxNum;

    /** 当前支持的 AI 模型列表 */
    private List<AISourceEnum> source;
}
```

### 6.4 UserAiService 次数计算

**文件**: `paicoding-service/.../user/service/ai/UserAiServiceImpl.java`

```java
@Service
public class UserAiServiceImpl implements UserAiService {

    @Resource
    private AiConfig aiConfig;
    @Resource
    private UserAiDao userAiDao;
    @Resource
    private AiBots aiBots;

    /**
     * 获取用户最大可用次数
     *
     * 计算规则：
     * 1. AI机器人账号 → 无限制
     * 2. 星球用户（已审核） → basic + star
     * 3. 星球用户（试用中） → basic + starTry
     * 4. 公众号用户 → basic + wechat
     * 5. 绑定邀请码 → 当前次数 * (1 + invited)
     * 6. 邀请他人 → 当前次数 + 邀请人数 * inviteNum
     */
    public int getMaxChatCnt(Long userId) {
        // AI机器人不限制
        if (aiBots.aiBots(userId)) {
            return Integer.MAX_VALUE;
        }

        UserAiDO ai = userAiDao.getOrInitAiInfo(userId);
        int strategy = ai.getStrategy();
        int cnt = 0;

        // 星球用户
        if (UserAiStrategyEnum.STAR_JAVA_GUIDE.match(strategy) ||
            UserAiStrategyEnum.STAR_TECH_PAI.match(strategy)) {
            if (Objects.equals(ai.getState(), UserAIStatEnum.FORMAL.getCode())) {
                cnt += aiConfig.getMaxNum().getStar();       // +100
            } else if (Objects.equals(ai.getState(), UserAIStatEnum.TRYING.getCode())) {
                cnt += aiConfig.getMaxNum().getStarTry();    // +20
            }
        } else {
            // 公众号用户
            if (UserAiStrategyEnum.WECHAT.match(strategy)) {
                cnt += aiConfig.getMaxNum().getWechat();     // +5
            }
        }

        // 绑定邀请码加成
        if (UserAiStrategyEnum.INVITE_USER.match(strategy)) {
            cnt = (int) (cnt + cnt * aiConfig.getMaxNum().getInvited());  // +10%
        }

        // 邀请他人加成
        if (ai.getInviteNum() > 0) {
            cnt = cnt + ai.getInviteNum() * ((int) (cnt * aiConfig.getMaxNum().getInviteNum()));
        }

        // 兜底：登录用户至少给基础次数
        if (cnt == 0) {
            cnt = aiConfig.getMaxNum().getBasic();  // 5
        }

        return cnt;
    }
}
```

---

## 七、完整调用流程

### 7.1 用户发起聊天

```
用户发送消息: "什么是Spring Boot?"
        │
        ▼
┌───────────────────────────────────────┐
│   ChatRestController (WebSocket)       │
│   @MessageMapping("/chat/{session}")  │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   WsAnswerHelper.sendMsgToUser()      │
│   选择 AI 模型                         │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   ChatFacade.autoChat()               │
│   - getRecommendAiSource() → DeepSeek │
│   - 判断使用异步还是同步               │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   ChatServiceFactory.getChatService() │
│   - 从 Map 获取 DeepSeekChatServiceImpl│
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   DeepSeekChatServiceImpl.asyncChat() │
│   (继承自 AbsChatService)              │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   AbsChatService.initResVo()          │
│   ① getMaxQaCnt() → 查询最大次数       │
│   ② queryUserdCnt() → Redis查已用次数  │
│   ③ 检查次数是否用完                   │
└───────────────────┬───────────────────┘
                    │
         ┌──────────┴──────────┐
         │                     │
    次数用完              次数充足
         │                     │
         ▼                     ▼
    返回错误提示      ┌───────────────────────────────┐
                     │   AbsChatService.answer()      │
                     │   ① sensitiveService.contains()│ ← 敏感词检测
                     │   ② doAnswer() 或 doAsyncAnswer()│
                     └───────────────┬───────────────┘
                                     │
                                     ▼
                     ┌───────────────────────────────┐
                     │   DeepSeekIntegration         │
                     │   ① 构建请求体                 │
                     │   ② 调用 API                   │
                     │   ③ SSE 流式返回               │
                     └───────────────┬───────────────┘
                                     │
                                     ▼
                     ┌───────────────────────────────┐
                     │   processAfterSuccessedAnswered│
                     │   ① incrCnt() → Redis次数+1   │
                     │   ② recordChatItem() → 保存记录│
                     └───────────────────────────────┘
```

### 7.2 Redis 操作时序

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Redis 操作时序                                │
└─────────────────────────────────────────────────────────────────────┘

1. 查询已用次数
   HGET chat.rates.deep_seek-2025-01-15 "10001"
   → 返回: 5

2. 聊天成功后，次数+1
   HINCRBY chat.rates.deep_seek-2025-01-15 "10001" 1
   → 返回: 6

3. 首次使用时，设置过期时间
   EXPIRE chat.rates.deep_seek-2025-01-15 86400
```

---

## 八、配置示例

### 8.1 application.yml

```yaml
# AI 配置
ai:
  maxNum:
    basic: 5              # 基础用户次数
    wechat: 5             # 公众号用户加成
    star: 100             # 星球用户次数
    starTry: 20           # 星球试用次数
    invited: 0.1          # 邀请码加成 10%
    inviteNum: 0.2        # 每邀请一人加成 20%
    historyContextCnt: 10 # 多轮对话上下文条数
  source:                 # 启用的 AI 模型
    - DEEP_SEEK
    - CHAT_GPT_3_5
    - ZHI_PU_AI

# DeepSeek 配置
deepseek:
  apiKey: sk-xxxxxx
  apiHost: https://api.deepseek.com
  timeout: 60

# ChatGPT 配置
chatgpt:
  apiKey: sk-xxxxxx
  apiHost: https://api.openai.com
```

---

## 九、文件清单

| 文件路径 | 职责 |
|---------|------|
| `paicoding-api/.../enums/ai/AISourceEnum.java` | AI 模型枚举 |
| `paicoding-service/.../chatai/service/ChatService.java` | 统一接口定义 |
| `paicoding-service/.../chatai/service/AbsChatService.java` | 抽象基类（频控+敏感词） |
| `paicoding-service/.../chatai/service/ChatServiceFactory.java` | 工厂类 |
| `paicoding-service/.../chatai/ChatFacade.java` | 门面类 |
| `paicoding-service/.../chatai/constants/ChatConstants.java` | 常量定义 |
| `paicoding-service/.../chatai/service/impl/deepseek/*` | DeepSeek 实现 |
| `paicoding-service/.../chatai/service/impl/chatgpt/*` | ChatGPT 实现 |
| `paicoding-service/.../user/service/conf/AiConfig.java` | 次数策略配置 |
| `paicoding-service/.../user/service/ai/UserAiServiceImpl.java` | 用户次数计算 |
| `paicoding-web/.../chat/rest/ChatRestController.java` | WebSocket 入口 |

---

## 十、设计模式总结

| 模式 | 应用位置 | 作用 |
|-----|---------|------|
| **工厂模式** | ChatServiceFactory | 根据枚举获取对应实现 |
| **策略模式** | ChatService 多实现 | 不同 AI 模型的具体调用 |
| **模板方法** | AbsChatService | 公共逻辑（频控/敏感词）抽取 |
| **门面模式** | ChatFacade | 统一对外入口，隐藏内部复杂度 |
| **观察者模式** | SSE EventSourceListener | 流式响应回调处理 |
