# 敏感词功能业务使用场景分析

本文档分析项目中所有使用敏感词功能的业务代码，帮助理解敏感词模块在实际业务中的应用方式。

---

## 一、使用场景总览

项目中敏感词功能被应用于 **3 大业务场景**：

| 场景 | 使用方式 | 核心文件 | 作用 |
|-----|---------|---------|-----|
| 评论内容展示 | 注解自动替换 | CommentDO.java | 用户查看评论时自动过滤敏感词 |
| AI 聊天问答 | 显式校验拦截 | AbsChatService.java | 用户提问前检测敏感词并拦截 |
| 运营管理后台 | 管理接口调用 | GlobalConfigServiceImpl.java / TestController.java | 白名单管理、敏感词查询 |

---

## 二、场景一：评论内容自动过滤

### 2.1 业务背景

用户发表评论后，评论内容需要在展示时自动过滤敏感词，避免不良内容传播。

### 2.2 实现原理

使用 **MyBatis 拦截器 + 自定义注解** 实现透明过滤，业务代码无需手动调用敏感词服务。

### 2.3 核心代码

#### （1）@SensitiveField 注解定义

```java
// paicoding-core/.../senstive/ano/SensitiveField.java

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface SensitiveField {
    /**
     * 绑定的db中的哪个字段
     */
    String bind() default "";
}
```

#### （2）CommentDO 实体类使用注解

```java
// paicoding-service/.../comment/repository/entity/CommentDO.java

@Data
@TableName("comment")
public class CommentDO extends BaseDO {

    private Long articleId;
    private Long userId;

    /**
     * 评论内容 - 标记需要敏感词过滤
     */
    @SensitiveField(bind = "content")
    private String content;

    private Long parentCommentId;
    private Long topCommentId;
    private Integer deleted;
}
```

#### （3）MyBatis 拦截器自动处理

```java
// paicoding-core/.../senstive/ibatis/SensitiveReadInterceptor.java

@Intercepts({
    @Signature(type = ResultSetHandler.class, method = "handleResultSets", args = {java.sql.Statement.class})
})
@Component
public class SensitiveReadInterceptor implements Interceptor {

    @Autowired
    private SensitiveService sensitiveService;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 1. 执行原始查询
        final List<Object> results = (List<Object>) invocation.proceed();

        if (results.isEmpty()) {
            return results;
        }

        // 2. 查找带 @SensitiveField 注解的字段
        Object firstObject = results.stream().filter(Objects::nonNull).findFirst().get();
        SensitiveObjectMeta sensitiveObjectMeta = findSensitiveObjectMeta(firstObject);

        // 3. 执行敏感词替换
        replaceSensitiveResults(results, mappedStatement, sensitiveObjectMeta);
        return results;
    }

    private void replaceSensitiveResults(Collection<Object> results,
                                          MappedStatement mappedStatement,
                                          SensitiveObjectMeta sensitiveObjectMeta) {
        for (Object obj : results) {
            sensitiveObjectMeta.getSensitiveFieldMetaList().forEach(fieldMeta -> {
                Object value = objMetaObject.getValue(fieldMeta.getBindField());

                if (value instanceof String) {
                    // 调用敏感词替换服务
                    String processVal = sensitiveService.replace((String) value);
                    objMetaObject.setValue(fieldMeta.getName(), processVal);
                }
                // 递归处理嵌套对象和集合...
            });
        }
    }
}
```

### 2.4 执行流程

```
用户请求查看评论
    ↓
CommentMapper.selectList()
    ↓
MyBatis 执行 SQL 查询
    ↓
SensitiveReadInterceptor.intercept() 拦截结果
    ↓
检测 CommentDO.content 字段有 @SensitiveField 注解
    ↓
调用 sensitiveService.replace(content)
    ↓
返回已过滤的评论内容给前端
```

### 2.5 业务特点

- **透明处理**：业务代码无感知，只需加注解
- **读时过滤**：数据库存储原始内容，展示时才过滤
- **支持嵌套**：自动处理集合和嵌套对象中的敏感字段

---

## 三、场景二：AI 聊天敏感词拦截

### 3.1 业务背景

用户与 AI 机器人对话时，需要在调用大模型 API 之前检测提问内容，拦截包含敏感词的问题，避免：
1. 敏感内容进入 AI 模型
2. AI 可能给出不当回答
3. 浪费 API 调用次数

### 3.2 实现原理

在 AI 聊天抽象基类中，调用大模型前显式调用 `sensitiveService.contains()` 检测。

### 3.3 核心代码

```java
// paicoding-service/.../chatai/service/AbsChatService.java

@Slf4j
@Service
public abstract class AbsChatService implements ChatService {

    @Autowired
    private SensitiveService sensitiveService;

    /**
     * 同步聊天 - 执行提问前检测敏感词
     */
    protected AiChatStatEnum answer(Long user, ChatRecordsVo res) {
        ChatItemVo itemVo = res.getRecords().get(0);
        AiChatStatEnum ans;

        // 🔴 核心：检测用户提问是否包含敏感词
        List<String> sensitiveWords = sensitiveService.contains(itemVo.getQuestion());

        if (!CollectionUtils.isEmpty(sensitiveWords)) {
            // 命中敏感词，直接返回错误提示，不调用 AI
            itemVo.initAnswer(String.format(ChatConstants.SENSITIVE_QUESTION, sensitiveWords));
            ans = AiChatStatEnum.ERROR;
        } else {
            // 无敏感词，调用子类实现的 AI 接口
            ans = doAnswer(user, itemVo);
            if (ans == AiChatStatEnum.END) {
                processAfterSuccessedAnswered(user, res);
            }
        }
        return ans;
    }

    /**
     * 异步聊天 - 同样需要检测敏感词
     */
    @Override
    public ChatRecordsVo asyncChat(Long user, String question, Consumer<ChatRecordsVo> consumer) {
        ChatRecordsVo res = initResVo(user, question);

        // 🔴 异步聊天也要检测敏感词
        List<String> sensitiveWord = sensitiveService.contains(res.getRecords().get(0).getQuestion());

        if (!CollectionUtils.isEmpty(sensitiveWord) && !SpringUtil.getBean(AiBots.class).aiBots(user)) {
            // 机器人账号不进行敏感词校验
            res.getRecords().get(0).initAnswer(String.format(ChatConstants.SENSITIVE_QUESTION, sensitiveWord));
            consumer.accept(res);
        } else {
            // 正常调用 AI
            doAsyncAnswer(user, res, ...);
        }
        return res;
    }
}
```

### 3.4 执行流程

```
用户提问："xxx敏感词xxx问题"
    ↓
AbsChatService.answer() / asyncChat()
    ↓
sensitiveService.contains(question)
    ↓
    ├── 命中敏感词 → 返回 "您的问题包含敏感词[xxx]，请修改后重试"
    │                 不调用 AI API，不扣次数
    │
    └── 未命中 → doAnswer() / doAsyncAnswer()
                 调用 DeepSeek/ChatGPT 等大模型 API
```

### 3.5 业务特点

- **前置拦截**：在调用 AI API 之前检测，节省资源
- **统计命中**：调用 `contains()` 时会自动统计敏感词命中次数（Redis Pipeline）
- **机器人豁免**：AI 机器人账号不进行敏感词校验
- **友好提示**：返回具体命中的敏感词，引导用户修改

---

## 四、场景三：运营管理后台

### 4.1 业务背景

运营人员需要：
1. 查看哪些敏感词被命中过
2. 测试某段文本是否包含敏感词
3. 将误判的词加入白名单

### 4.2 核心代码

#### （1）添加敏感词白名单

```java
// paicoding-service/.../config/service/impl/GlobalConfigServiceImpl.java

@Service
public class GlobalConfigServiceImpl implements GlobalConfigService {

    @Autowired
    private ConfigDao configDao;

    /**
     * 添加敏感词白名单
     * 将被误判的词加入 allow 列表，下次刷新后生效
     */
    @Override
    public void addSensitiveWhiteWord(String word) {
        // 1. 构建配置 key：sensitive.allow
        String key = SensitiveProperty.SENSITIVE_KEY_PREFIX + ".allow";
        GlobalConfigReq req = new GlobalConfigReq();
        req.setKeywords(key);

        // 2. 查询现有白名单配置
        GlobalConfigDO config = configDao.getGlobalConfigByKey(key);
        if (config == null) {
            req.setValue(word);
            req.setComment("敏感词白名单");
        } else {
            // 追加新词，用逗号分隔
            req.setValue(config.getValue() + "," + word);
            req.setComment(config.getComment());
            req.setId(config.getId());
        }

        // 3. 保存配置（会触发 ConfigRefreshEvent，刷新敏感词引擎）
        save(req);

        // 4. 移除该词的命中统计记录
        SpringUtil.getBean(SensitiveService.class).removeSensitiveWord(word);
    }
}
```

#### （2）测试接口（TestController）

```java
// paicoding-web/.../test/rest/TestController.java

@RestController
@RequestMapping(path = "test")
public class TestController {

    @Autowired
    private SensitiveService sensitiveService;

    /**
     * 敏感词校验 - 测试某段文本是否包含敏感词
     * GET /test/sensitive/check?txt=测试文本
     */
    @GetMapping(path = "sensitive/check")
    public List<String> sensitiveWords(String txt) {
        return sensitiveService.findAll(txt);
    }

    /**
     * 查看所有命中过的敏感词及次数
     * GET /test/sensitive/all
     */
    @GetMapping(path = "sensitive/all")
    public Map<String, Integer> showAllHitSensitiveWords() {
        return sensitiveService.getHitSensitiveWords();
    }

    /**
     * 将敏感词添加到白名单（需要管理员权限）
     * GET /test/sensitive/addAllowWord?word=医学术语
     */
    @Permission(role = UserRole.ADMIN)
    @GetMapping(path = "sensitive/addAllowWord")
    public String addSensitiveAllowWord(String word) {
        SpringUtil.getBean(GlobalConfigService.class).addSensitiveWhiteWord(word);
        return "ok";
    }

    /**
     * 强制刷新动态配置（包括敏感词配置）
     * GET /test/refresh/config
     */
    @Permission(role = UserRole.ADMIN)
    @GetMapping("refresh/config")
    public String refreshConfig() {
        DynamicConfigContainer configContainer = SpringUtil.getBean(DynamicConfigContainer.class);
        configContainer.forceRefresh();
        return JsonUtil.toStr(configContainer.getCache());
    }
}
```

### 4.3 API 接口列表

| 接口 | 方法 | 权限 | 功能 |
|-----|-----|-----|-----|
| `/test/sensitive/check?txt=xxx` | GET | 公开 | 检测文本中的敏感词 |
| `/test/sensitive/all` | GET | 公开 | 查看所有命中敏感词及统计 |
| `/test/sensitive/addAllowWord?word=xxx` | GET | ADMIN | 添加白名单 |
| `/test/refresh/config` | GET | ADMIN | 强制刷新配置 |

### 4.4 执行流程（添加白名单）

```
运营发现 "胸腔" 被误判为敏感词
    ↓
调用 /test/sensitive/addAllowWord?word=胸腔
    ↓
GlobalConfigServiceImpl.addSensitiveWhiteWord("胸腔")
    ↓
更新数据库 global_conf 表：sensitive.allow = "原值,胸腔"
    ↓
发布 ConfigRefreshEvent 事件
    ↓
DynamicConfigContainer 接收事件，更新内存配置
    ↓
SensitiveService.refresh() 被回调，重建敏感词引擎
    ↓
"胸腔" 从此不再被判定为敏感词
```

---

## 五、敏感词服务核心方法

### 5.1 SensitiveService 公开方法

```java
// paicoding-core/.../senstive/SensitiveService.java

@Service
public class SensitiveService {

    /**
     * 检测文本是否包含敏感词，并统计命中次数
     * @return 命中的敏感词列表
     */
    public List<String> contains(String txt);

    /**
     * 替换文本中的敏感词为 ***
     * @return 替换后的文本
     */
    public String replace(String txt);

    /**
     * 查找文本中所有敏感词（不统计命中次数）
     * @return 命中的敏感词列表
     */
    public List<String> findAll(String txt);

    /**
     * 获取所有命中过的敏感词及统计
     * @return key=敏感词, value=命中次数
     */
    public Map<String, Integer> getHitSensitiveWords();

    /**
     * 移除某个敏感词的统计记录
     */
    public void removeSensitiveWord(String word);
}
```

### 5.2 方法使用场景对照

| 方法 | 使用场景 | 示例 |
|-----|---------|-----|
| `contains()` | AI 聊天前检测 | 拦截敏感提问 |
| `replace()` | 评论展示时替换 | 自动过滤评论 |
| `findAll()` | 管理后台测试 | 检测文本敏感词 |
| `getHitSensitiveWords()` | 运营统计分析 | 查看热门敏感词 |
| `removeSensitiveWord()` | 白名单管理 | 清理误判记录 |

---

## 六、总结

### 6.1 三种使用模式对比

| 模式 | 调用方式 | 业务感知 | 适用场景 |
|-----|---------|---------|---------|
| 注解自动替换 | `@SensitiveField` + 拦截器 | 无感知 | UGC 内容展示（评论、文章） |
| 显式校验拦截 | `sensitiveService.contains()` | 需编码 | 输入校验（聊天、发帖前） |
| 管理接口调用 | `sensitiveService.xxx()` | 需编码 | 运营后台管理功能 |

### 6.2 架构优势

1. **分层设计**：核心服务在 core 层，业务调用在 service/web 层
2. **可扩展**：新增需要过滤的实体只需加 `@SensitiveField` 注解
3. **可观测**：Redis 统计命中次数，便于运营分析
4. **热更新**：数据库配置 + 定时刷新，无需重启

### 6.3 项目文件清单

| 文件路径 | 类型 | 说明 |
|---------|-----|-----|
| `paicoding-core/.../senstive/SensitiveService.java` | 核心服务 | 敏感词检测/替换/统计 |
| `paicoding-core/.../senstive/ano/SensitiveField.java` | 注解 | 标记敏感字段 |
| `paicoding-core/.../senstive/ibatis/SensitiveReadInterceptor.java` | 拦截器 | MyBatis 自动替换 |
| `paicoding-service/.../comment/repository/entity/CommentDO.java` | 实体 | 评论（使用注解） |
| `paicoding-service/.../chatai/service/AbsChatService.java` | AI 服务 | 聊天敏感词检测 |
| `paicoding-service/.../config/service/impl/GlobalConfigServiceImpl.java` | 配置服务 | 白名单管理 |
| `paicoding-web/.../test/rest/TestController.java` | 测试接口 | 管理后台 API |
