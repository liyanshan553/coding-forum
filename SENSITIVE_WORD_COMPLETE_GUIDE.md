# 敏感词功能完整实现指南

本文档涵盖敏感词功能的三大核心内容：
1. **动态配置实现** - 数据库配置 + 定时刷新 + 热更新机制
2. **业务应用场景** - AI聊天检测、管理后台接口
3. **MyBatis拦截器统一替换** - 注解标记 + 自动过滤的完整链路

---

## 一、整体架构

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                              敏感词系统架构图                                      │
└─────────────────────────────────────────────────────────────────────────────────┘

                         ┌─────────────────┐
                         │     MySQL       │
                         │   global_conf   │
                         └────────┬────────┘
                                  │ 每5分钟定时拉取
                                  ▼
                         ┌─────────────────────────┐
                         │ DynamicConfigContainer  │  ←── 动态配置容器
                         └────────┬────────────────┘
                                  │ 配置绑定 + 回调通知
                                  ▼
                         ┌─────────────────────────┐
                         │   SensitiveProperty     │  ←── 配置类 (enable/deny/allow)
                         └────────┬────────────────┘
                                  │ 触发 refresh() 回调
                                  ▼
                         ┌─────────────────────────┐
                         │   SensitiveService      │  ←── 敏感词核心服务
                         │   (DFA算法引擎)          │
                         └────────┬────────────────┘
                                  │
          ┌───────────────────────┼───────────────────────┐
          │                       │                       │
          ▼                       ▼                       ▼
   ┌─────────────┐        ┌─────────────┐         ┌─────────────────┐
   │  AI 聊天     │        │  管理后台    │         │ MyBatis拦截器    │
   │ contains()  │        │ findAll()   │         │ replace()       │
   │ 前置检测拦截  │        │ 测试/统计    │         │ 自动透明替换     │
   └──────┬──────┘        └─────────────┘         └────────┬────────┘
          │                                                │
          ▼                                                ▼
   ┌─────────────┐                                 ┌─────────────────┐
   │   Redis     │                                 │  CommentDO 等    │
   │ 命中统计     │                                 │ @SensitiveField │
   └─────────────┘                                 └─────────────────┘
```

---

## 二、动态配置实现

### 2.1 数据库表设计

```sql
-- 全局配置表
CREATE TABLE `global_conf` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT,
    `key` VARCHAR(255) NOT NULL COMMENT '配置键',
    `value` TEXT COMMENT '配置值',
    `comment` VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `deleted` TINYINT(4) NOT NULL DEFAULT '0',
    `create_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    `update_time` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_key` (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 敏感词配置数据
INSERT INTO global_conf (`key`, `value`, `comment`) VALUES
('paicoding.sensitive.enable', 'true', '是否启用敏感词'),
('paicoding.sensitive.deny', '赌博,色情,暴力', '黑名单'),
('paicoding.sensitive.allow', '胸腔,性能', '白名单');
```

### 2.2 动态配置容器

**文件**: `paicoding-core/.../autoconf/DynamicConfigContainer.java`

```java
@Slf4j
@Component
public class DynamicConfigContainer implements EnvironmentAware, CommandLineRunner {

    private ConfigurableEnvironment environment;

    @Getter
    public Map<String, Object> cache;  // 内存配置缓存

    @Getter
    private Map<Class, Runnable> refreshCallback = Maps.newHashMap();  // 刷新回调

    /**
     * 从数据库加载配置
     */
    private boolean loadAllConfigFromDb() {
        List<Map<String, Object>> list = SpringUtil.getBean(JdbcTemplate.class)
                .queryForList("SELECT `key`, `value` FROM global_conf WHERE deleted = 0");

        Map<String, Object> val = Maps.newHashMap();
        for (Map<String, Object> conf : list) {
            val.put(conf.get("key").toString(), conf.get("value").toString());
        }

        if (val.equals(cache)) {
            return false;  // 无变化
        }

        cache.clear();
        cache.putAll(val);
        return true;  // 有变化
    }

    /**
     * 刷新配置并触发回调
     */
    public void reloadConfig() {
        boolean changed = loadAllConfigFromDb();
        if (changed) {
            refreshConfig();
            log.info("配置刷新完成!");
        }
    }

    private void refreshConfig() {
        // 遍历所有 @ConfigurationProperties 注解的 Bean
        applicationContext.getBeansWithAnnotation(ConfigurationProperties.class)
                .values().forEach(bean -> {
            // 重新绑定配置值
            Bindable<?> target = Bindable.ofInstance(bean)
                    .withAnnotations(AnnotationUtils.findAnnotation(bean.getClass(), ConfigurationProperties.class));
            bind(target);

            // 触发回调（如 SensitiveService.refresh()）
            if (refreshCallback.containsKey(bean.getClass())) {
                refreshCallback.get(bean.getClass()).run();
            }
        });
    }

    /**
     * 注册配置变更回调
     */
    public void registerRefreshCallback(Object bean, Runnable run) {
        refreshCallback.put(bean.getClass(), run);
    }

    /**
     * 启动定时刷新任务
     */
    private void registerConfRefreshTask() {
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
            try {
                reloadConfig();
            } catch (Exception e) {
                log.warn("配置刷新异常!", e);
            }
        }, 5, 5, TimeUnit.MINUTES);  // 每5分钟执行
    }

    @Override
    public void run(String... args) {
        reloadConfig();           // 首次加载
        registerConfRefreshTask(); // 启动定时任务
    }
}
```

### 2.3 敏感词配置类

**文件**: `paicoding-core/.../senstive/SensitiveProperty.java`

```java
@Data
@Component
@ConfigurationProperties(prefix = "paicoding.sensitive")
public class SensitiveProperty {

    public static final String SENSITIVE_KEY_PREFIX = "paicoding.sensitive";

    /** 是否启用敏感词 */
    private Boolean enable;

    /** 黑名单（需过滤的词） */
    private List<String> deny;

    /** 白名单（不应被误判的词） */
    private List<String> allow;
}
```

### 2.4 敏感词服务

**文件**: `paicoding-core/.../senstive/SensitiveService.java`

```java
@Slf4j
@Service
public class SensitiveService {

    private static final String SENSITIVE_WORD_CNT_PREFIX = "sensitive_word";

    /** 敏感词引擎（volatile 保证多线程可见性） */
    private volatile SensitiveWordBs sensitiveWordBs;

    @Autowired
    private SensitiveProperty sensitiveConfig;

    @Autowired
    private DynamicConfigContainer dynamicConfigContainer;

    /**
     * 初始化/刷新敏感词引擎
     */
    @PostConstruct
    public void refresh() {
        // 注册回调：配置变更时重新执行 refresh()
        dynamicConfigContainer.registerRefreshCallback(sensitiveConfig, this::refresh);

        // 构建黑名单：系统内置 + 数据库自定义
        IWordDeny deny = () -> {
            List<String> sub = WordDenySystem.getInstance().deny();
            if (sensitiveConfig.getDeny() != null) {
                sub.addAll(sensitiveConfig.getDeny());
            }
            return sub;
        };

        // 构建白名单：系统内置 + 数据库自定义
        IWordAllow allow = () -> {
            List<String> sub = WordAllowSystem.getInstance().allow();
            if (sensitiveConfig.getAllow() != null) {
                sub.addAll(sensitiveConfig.getAllow());
            }
            return sub;
        };

        // 重建敏感词引擎（DFA算法）
        sensitiveWordBs = SensitiveWordBs.newInstance()
                .wordDeny(deny)
                .wordAllow(allow)
                .init();

        log.info("敏感词引擎初始化完成!");
    }

    /**
     * 检测文本是否包含敏感词（会统计命中次数）
     */
    public List<String> contains(String txt) {
        if (!BooleanUtils.isTrue(sensitiveConfig.getEnable())) {
            return Collections.emptyList();
        }

        List<String> ans = sensitiveWordBs.findAll(txt);

        if (!CollectionUtils.isEmpty(ans)) {
            // Redis Pipeline 批量统计命中次数
            RedisClient.PipelineAction action = RedisClient.pipelineAction();
            ans.forEach(key -> action.add(SENSITIVE_WORD_CNT_PREFIX, key,
                    (conn, k, v) -> conn.hIncrBy(k, v, 1)));
            action.execute();
        }

        return ans;
    }

    /**
     * 替换敏感词为 ***
     */
    public String replace(String txt) {
        if (BooleanUtils.isTrue(sensitiveConfig.getEnable())) {
            return sensitiveWordBs.replace(txt);
        }
        return txt;
    }

    /**
     * 查找所有敏感词（不统计）
     */
    public List<String> findAll(String txt) {
        return sensitiveWordBs.findAll(txt);
    }

    /**
     * 获取命中统计
     */
    public Map<String, Integer> getHitSensitiveWords() {
        return RedisClient.hGetAll(SENSITIVE_WORD_CNT_PREFIX, Integer.class);
    }

    /**
     * 移除敏感词统计记录
     */
    public void removeSensitiveWord(String word) {
        RedisClient.hDel(SENSITIVE_WORD_CNT_PREFIX, word);
    }
}
```

### 2.5 动态配置执行流程

```
┌─────────────────────────────────────────────────────────────────────┐
│                        应用启动流程                                   │
└─────────────────────────────────────────────────────────────────────┘

1. Spring 容器启动
        │
        ▼
2. @PostConstruct: SensitiveService.refresh()
        │   - 注册配置刷新回调
        │   - 构建敏感词引擎
        │
        ▼
3. CommandLineRunner: DynamicConfigContainer.run()
        │
        ├──▶ reloadConfig()
        │       ├── loadAllConfigFromDb()  ← SQL: SELECT * FROM global_conf
        │       └── refreshConfig()
        │               ├── bind(SensitiveProperty)  ← 重新绑定配置值
        │               └── SensitiveService.refresh()  ← 触发回调
        │
        └──▶ registerConfRefreshTask()
                └── 每5分钟执行 reloadConfig()


┌─────────────────────────────────────────────────────────────────────┐
│                        配置热更新流程                                 │
└─────────────────────────────────────────────────────────────────────┘

运维修改数据库配置
        │
        ▼
定时任务触发（每5分钟）
        │
        ▼
loadAllConfigFromDb()
        │
        ├── 对比 cache，无变化 → 结束
        │
        └── 有变化 → refreshConfig()
                        │
                        ├── 重新绑定 SensitiveProperty
                        │
                        └── 触发回调 SensitiveService.refresh()
                                │
                                └── 重建敏感词引擎（新词库生效）
```

---

## 三、业务应用场景

### 3.1 AI聊天敏感词检测

**文件**: `paicoding-service/.../chatai/service/AbsChatService.java`

```java
@Slf4j
@Service
public abstract class AbsChatService implements ChatService {

    @Autowired
    private SensitiveService sensitiveService;

    /**
     * 同步聊天 - 提问前检测敏感词
     */
    protected AiChatStatEnum answer(Long user, ChatRecordsVo res) {
        ChatItemVo itemVo = res.getRecords().get(0);

        // ⭐ 核心：检测用户提问是否包含敏感词
        List<String> sensitiveWords = sensitiveService.contains(itemVo.getQuestion());

        if (!CollectionUtils.isEmpty(sensitiveWords)) {
            // 命中敏感词 → 直接返回错误，不调用AI
            itemVo.initAnswer(String.format(ChatConstants.SENSITIVE_QUESTION, sensitiveWords));
            return AiChatStatEnum.ERROR;
        }

        // 无敏感词 → 正常调用AI
        return doAnswer(user, itemVo);
    }

    /**
     * 异步聊天 - 同样需要检测
     */
    @Override
    public ChatRecordsVo asyncChat(Long user, String question, Consumer<ChatRecordsVo> consumer) {
        ChatRecordsVo res = initResVo(user, question);

        List<String> sensitiveWord = sensitiveService.contains(res.getRecords().get(0).getQuestion());

        if (!CollectionUtils.isEmpty(sensitiveWord) && !SpringUtil.getBean(AiBots.class).aiBots(user)) {
            // 命中敏感词（机器人账号豁免）
            res.getRecords().get(0).initAnswer(String.format(ChatConstants.SENSITIVE_QUESTION, sensitiveWord));
            consumer.accept(res);
        } else {
            // 正常异步调用AI
            doAsyncAnswer(user, res, consumer);
        }
        return res;
    }
}
```

**执行流程**：
```
用户提问："xxx敏感词xxx"
        │
        ▼
sensitiveService.contains(question)
        │
        ├── 命中敏感词 → 返回错误提示，不调用AI，不扣次数
        │
        └── 未命中 → 调用 DeepSeek/ChatGPT API
```

### 3.2 管理后台接口

**文件**: `paicoding-web/.../test/rest/TestController.java`

```java
@RestController
@RequestMapping(path = "test")
public class TestController {

    @Autowired
    private SensitiveService sensitiveService;

    /**
     * 检测文本中的敏感词
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
     * 添加敏感词白名单（管理员）
     * GET /test/sensitive/addAllowWord?word=医学术语
     */
    @Permission(role = UserRole.ADMIN)
    @GetMapping(path = "sensitive/addAllowWord")
    public String addSensitiveAllowWord(String word) {
        SpringUtil.getBean(GlobalConfigService.class).addSensitiveWhiteWord(word);
        return "ok";
    }
}
```

### 3.3 白名单管理服务

**文件**: `paicoding-service/.../config/service/impl/GlobalConfigServiceImpl.java`

```java
@Service
public class GlobalConfigServiceImpl implements GlobalConfigService {

    /**
     * 添加敏感词白名单
     */
    @Override
    public void addSensitiveWhiteWord(String word) {
        String key = SensitiveProperty.SENSITIVE_KEY_PREFIX + ".allow";
        GlobalConfigReq req = new GlobalConfigReq();
        req.setKeywords(key);

        GlobalConfigDO config = configDao.getGlobalConfigByKey(key);
        if (config == null) {
            req.setValue(word);
            req.setComment("敏感词白名单");
        } else {
            req.setValue(config.getValue() + "," + word);
            req.setId(config.getId());
        }

        // 保存配置 → 触发 ConfigRefreshEvent → 刷新敏感词引擎
        save(req);

        // 清理该词的统计记录
        SpringUtil.getBean(SensitiveService.class).removeSensitiveWord(word);
    }
}
```

---

## 四、MyBatis拦截器统一替换（重点）

### 4.1 设计目标

实现**透明过滤**：业务代码无需手动调用敏感词服务，只需在实体类字段上加注解，查询结果自动过滤。

### 4.2 实现架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                     MyBatis 拦截器架构                               │
└─────────────────────────────────────────────────────────────────────┘

                    CommentMapper.selectList()
                            │
                            ▼
                    MyBatis 执行 SQL
                            │
                            ▼
              ┌─────────────────────────────┐
              │  SensitiveReadInterceptor   │  ←── 拦截 ResultSetHandler
              │  @Intercepts({              │
              │    @Signature(type=         │
              │      ResultSetHandler.class │
              │    )                         │
              │  })                          │
              └─────────────┬───────────────┘
                            │
                            ▼
              ┌─────────────────────────────┐
              │  findSensitiveObjectMeta()  │  ←── 解析实体类元数据
              │  查找 @SensitiveField 注解   │
              └─────────────┬───────────────┘
                            │
                            ▼
              ┌─────────────────────────────┐
              │  SensitiveMetaCache         │  ←── 缓存元数据（避免重复反射）
              │  ConcurrentHashMap          │
              └─────────────┬───────────────┘
                            │
                            ▼
              ┌─────────────────────────────┐
              │  replaceSensitiveResults()  │  ←── 执行敏感词替换
              │  sensitiveService.replace() │
              └─────────────┬───────────────┘
                            │
                            ▼
                    返回过滤后的结果
```

### 4.3 核心组件代码

#### （1）@SensitiveField 注解

**文件**: `paicoding-core/.../senstive/ano/SensitiveField.java`

```java
/**
 * 标记需要敏感词过滤的字段
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD})
public @interface SensitiveField {
    /**
     * 绑定的数据库字段名
     * 默认使用 Java 字段名
     */
    String bind() default "";
}
```

#### （2）实体类使用示例

**文件**: `paicoding-service/.../comment/repository/entity/CommentDO.java`

```java
@Data
@TableName("comment")
public class CommentDO extends BaseDO {

    private Long articleId;
    private Long userId;

    /**
     * 评论内容 - 标记需要敏感词过滤
     * bind = "content" 表示绑定数据库的 content 字段
     */
    @SensitiveField(bind = "content")
    private String content;

    private Long parentCommentId;
    private Long topCommentId;
    private Integer deleted;
}
```

#### （3）敏感词元数据

**文件**: `paicoding-core/.../senstive/ibatis/SensitiveObjectMeta.java`

```java
/**
 * 敏感词对象元数据
 * 存储实体类中标注了 @SensitiveField 的字段信息
 */
@Data
public class SensitiveObjectMeta {

    /** 是否启用敏感词替换 */
    private Boolean enabledSensitiveReplace;

    /** 类名 */
    private String className;

    /** 敏感字段列表 */
    private List<SensitiveFieldMeta> sensitiveFieldMetaList;

    /**
     * 构建元数据（解析实体类的注解）
     */
    public static Optional<SensitiveObjectMeta> buildSensitiveObjectMeta(Object param) {
        if (param == null) {
            return Optional.empty();
        }

        Class<?> clazz = param.getClass();
        SensitiveObjectMeta meta = new SensitiveObjectMeta();
        meta.setClassName(clazz.getName());

        List<SensitiveFieldMeta> fieldMetaList = new ArrayList<>();
        meta.setSensitiveFieldMetaList(fieldMetaList);

        // 解析所有带 @SensitiveField 注解的字段
        boolean hasSensitiveField = parseAllSensitiveFields(clazz, fieldMetaList);
        meta.setEnabledSensitiveReplace(hasSensitiveField);

        return Optional.of(meta);
    }

    /**
     * 递归解析类及其父类的敏感字段
     */
    private static boolean parseAllSensitiveFields(Class<?> clazz, List<SensitiveFieldMeta> list) {
        Class<?> tempClazz = clazz;
        boolean hasSensitiveField = false;

        // 向上遍历继承链
        while (tempClazz != null && !"java.lang.Object".equalsIgnoreCase(tempClazz.getName())) {
            for (Field field : tempClazz.getDeclaredFields()) {
                SensitiveField annotation = field.getAnnotation(SensitiveField.class);
                if (annotation != null) {
                    SensitiveFieldMeta fieldMeta = new SensitiveFieldMeta();
                    fieldMeta.setName(field.getName());           // Java字段名
                    fieldMeta.setBindField(annotation.bind());    // 绑定的DB字段名
                    list.add(fieldMeta);
                    hasSensitiveField = true;
                }
            }
            tempClazz = tempClazz.getSuperclass();
        }

        return hasSensitiveField;
    }

    @Data
    public static class SensitiveFieldMeta {
        private String name;       // Java字段名
        private String bindField;  // 绑定的DB字段名
    }
}
```

#### （4）元数据缓存

**文件**: `paicoding-core/.../senstive/ibatis/SensitiveMetaCache.java`

```java
/**
 * 敏感词元数据缓存
 * 避免每次查询都反射解析注解
 */
public class SensitiveMetaCache {

    private static ConcurrentHashMap<String, SensitiveObjectMeta> CACHE = new ConcurrentHashMap<>();

    public static SensitiveObjectMeta get(String key) {
        return CACHE.get(key);
    }

    public static SensitiveObjectMeta computeIfAbsent(String key,
            Function<String, SensitiveObjectMeta> function) {
        return CACHE.computeIfAbsent(key, function);
    }
}
```

#### （5）MyBatis 拦截器（核心）

**文件**: `paicoding-core/.../senstive/ibatis/SensitiveReadInterceptor.java`

```java
/**
 * 敏感词替换拦截器
 * 拦截 MyBatis 查询结果，自动替换敏感词
 */
@Intercepts({
    @Signature(
        type = ResultSetHandler.class,      // 拦截结果集处理器
        method = "handleResultSets",        // 拦截方法
        args = {java.sql.Statement.class}   // 方法参数
    )
})
@Component
@Slf4j
public class SensitiveReadInterceptor implements Interceptor {

    @Autowired
    private SensitiveService sensitiveService;

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        // 1. 执行原始查询，获取结果
        final List<Object> results = (List<Object>) invocation.proceed();

        if (results.isEmpty()) {
            return results;
        }

        // 2. 获取第一个非空对象
        Optional firstOpt = results.stream().filter(Objects::nonNull).findFirst();
        if (!firstOpt.isPresent()) {
            return results;
        }

        Object firstObject = firstOpt.get();

        // 3. 解析实体类的敏感词元数据（查找 @SensitiveField 注解）
        SensitiveObjectMeta sensitiveObjectMeta = findSensitiveObjectMeta(firstObject);

        // 4. 执行敏感词替换
        final ResultSetHandler statementHandler = realTarget(invocation.getTarget());
        final MetaObject metaObject = SystemMetaObject.forObject(statementHandler);
        final MappedStatement mappedStatement = (MappedStatement) metaObject.getValue("mappedStatement");

        replaceSensitiveResults(results, mappedStatement, sensitiveObjectMeta);

        return results;
    }

    /**
     * 查找对象的敏感词元数据（带缓存）
     */
    private SensitiveObjectMeta findSensitiveObjectMeta(Object firstObject) {
        // 使用 computeIfAbsent 确保只解析一次
        SensitiveMetaCache.computeIfAbsent(firstObject.getClass().getName(), s -> {
            Optional<SensitiveObjectMeta> opt = SensitiveObjectMeta.buildSensitiveObjectMeta(firstObject);
            return opt.orElse(null);
        });

        return SensitiveMetaCache.get(firstObject.getClass().getName());
    }

    /**
     * 执行敏感词替换
     */
    private void replaceSensitiveResults(Collection<Object> results,
                                          MappedStatement mappedStatement,
                                          SensitiveObjectMeta sensitiveObjectMeta) {
        for (Object obj : results) {
            if (sensitiveObjectMeta.getSensitiveFieldMetaList() == null) {
                continue;
            }

            final MetaObject objMetaObject = mappedStatement.getConfiguration().newMetaObject(obj);

            // 遍历所有敏感字段
            sensitiveObjectMeta.getSensitiveFieldMetaList().forEach(fieldMeta -> {
                // 获取字段值（优先使用 bindField，否则使用 name）
                String fieldName = StringUtils.isBlank(fieldMeta.getBindField())
                        ? fieldMeta.getName()
                        : fieldMeta.getBindField();
                Object value = objMetaObject.getValue(fieldName);

                if (value == null) {
                    return;
                }

                if (value instanceof String) {
                    // ⭐ 核心：调用敏感词替换服务
                    String strValue = (String) value;
                    String processVal = sensitiveService.replace(strValue);
                    objMetaObject.setValue(fieldMeta.getName(), processVal);

                } else if (value instanceof Collection) {
                    // 递归处理集合
                    Collection listValue = (Collection) value;
                    if (CollectionUtils.isNotEmpty(listValue)) {
                        Optional firstValOpt = listValue.stream().filter(Objects::nonNull).findFirst();
                        if (firstValOpt.isPresent()) {
                            SensitiveObjectMeta valMeta = findSensitiveObjectMeta(firstValOpt.get());
                            if (Boolean.TRUE.equals(valMeta.getEnabledSensitiveReplace())) {
                                replaceSensitiveResults(listValue, mappedStatement, valMeta);
                            }
                        }
                    }

                } else if (!ClassUtils.isPrimitiveOrWrapper(value.getClass())) {
                    // 递归处理嵌套对象
                    SensitiveObjectMeta valMeta = findSensitiveObjectMeta(value);
                    if (Boolean.TRUE.equals(valMeta.getEnabledSensitiveReplace())) {
                        replaceSensitiveResults(Arrays.asList(value), mappedStatement, valMeta);
                    }
                }
            });
        }
    }

    @Override
    public Object plugin(Object o) {
        return Plugin.wrap(o, this);
    }

    /**
     * 获取真实目标对象（处理代理）
     */
    public static <T> T realTarget(Object target) {
        if (Proxy.isProxyClass(target.getClass())) {
            MetaObject metaObject = SystemMetaObject.forObject(target);
            return realTarget(metaObject.getValue("h.target"));
        }
        return (T) target;
    }
}
```

### 4.4 完整执行流程

```
┌─────────────────────────────────────────────────────────────────────┐
│               MyBatis 拦截器敏感词替换完整流程                         │
└─────────────────────────────────────────────────────────────────────┘

用户请求：GET /article/123/comments
        │
        ▼
Controller 调用 Service
        │
        ▼
CommentService.listComments(articleId)
        │
        ▼
CommentMapper.selectList(wrapper)
        │
        ▼
┌───────────────────────────────────────┐
│          MyBatis 执行 SQL              │
│  SELECT * FROM comment WHERE ...      │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   ResultSetHandler.handleResultSets   │
│           ↓ 被拦截                     │
│   SensitiveReadInterceptor.intercept  │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   1. invocation.proceed()             │
│      执行原始查询，获取 List<CommentDO> │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   2. findSensitiveObjectMeta()        │
│      查找 CommentDO 的敏感词元数据       │
│                                        │
│      缓存检查：                         │
│      ├── 命中 → 直接返回                │
│      └── 未命中 → 反射解析注解           │
│                                        │
│      解析结果：                         │
│      className: CommentDO              │
│      enabledSensitiveReplace: true     │
│      sensitiveFieldMetaList:           │
│        - name: "content"               │
│          bindField: "content"          │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   3. replaceSensitiveResults()        │
│                                        │
│   遍历每个 CommentDO 对象：             │
│                                        │
│   for (Object obj : results) {        │
│       // 获取 content 字段值           │
│       String content = obj.content;   │
│                                        │
│       // ⭐ 调用敏感词替换              │
│       String filtered =               │
│           sensitiveService.replace(   │
│               content);               │
│                                        │
│       // 设置过滤后的值                 │
│       obj.content = filtered;         │
│   }                                    │
└───────────────────┬───────────────────┘
                    │
                    ▼
┌───────────────────────────────────────┐
│   4. 返回过滤后的 List<CommentDO>      │
└───────────────────┬───────────────────┘
                    │
                    ▼
Service 返回结果 → Controller → 前端展示


┌─────────────────────────────────────────────────────────────────────┐
│                        数据转换示例                                   │
└─────────────────────────────────────────────────────────────────────┘

原始数据（数据库）：
┌────────────────────────────────────────┐
│ id: 1                                   │
│ content: "这个赌博网站真不错"            │
│ userId: 100                             │
└────────────────────────────────────────┘

过滤后数据（返回前端）：
┌────────────────────────────────────────┐
│ id: 1                                   │
│ content: "这个***网站真不错"            │  ← "赌博" 被替换为 "***"
│ userId: 100                             │
└────────────────────────────────────────┘
```

### 4.5 如何扩展到其他实体

只需在实体类的字段上添加 `@SensitiveField` 注解：

```java
// 文章实体
@Data
@TableName("article")
public class ArticleDO extends BaseDO {

    @SensitiveField(bind = "title")
    private String title;       // 标题需要过滤

    @SensitiveField(bind = "summary")
    private String summary;     // 摘要需要过滤

    @SensitiveField(bind = "content")
    private String content;     // 内容需要过滤
}

// 用户实体
@Data
@TableName("user")
public class UserDO extends BaseDO {

    @SensitiveField(bind = "user_name")
    private String userName;    // 用户名需要过滤

    @SensitiveField(bind = "profile")
    private String profile;     // 个人简介需要过滤
}
```

---

## 五、文件清单

| 文件路径 | 职责 |
|---------|------|
| `paicoding-core/.../autoconf/DynamicConfigContainer.java` | 动态配置加载与刷新 |
| `paicoding-core/.../senstive/SensitiveProperty.java` | 敏感词配置类 |
| `paicoding-core/.../senstive/SensitiveService.java` | 敏感词核心服务 |
| `paicoding-core/.../senstive/ano/SensitiveField.java` | 敏感字段注解 |
| `paicoding-core/.../senstive/ibatis/SensitiveObjectMeta.java` | 敏感词元数据 |
| `paicoding-core/.../senstive/ibatis/SensitiveMetaCache.java` | 元数据缓存 |
| `paicoding-core/.../senstive/ibatis/SensitiveReadInterceptor.java` | MyBatis拦截器 |
| `paicoding-service/.../comment/repository/entity/CommentDO.java` | 使用注解的实体类 |
| `paicoding-service/.../chatai/service/AbsChatService.java` | AI聊天敏感词检测 |
| `paicoding-service/.../config/service/impl/GlobalConfigServiceImpl.java` | 白名单管理 |
| `paicoding-web/.../test/rest/TestController.java` | 管理后台API |

---

## 六、关键技术点总结

| 技术点 | 说明 |
|-------|------|
| `volatile` | 保证敏感词引擎多线程可见性 |
| 回调机制 | 配置变更时自动触发业务刷新 |
| DFA算法 | 敏感词匹配，时间复杂度 O(n) |
| Redis Pipeline | 批量统计命中次数，减少网络往返 |
| MyBatis Interceptor | 拦截查询结果，透明替换敏感词 |
| ConcurrentHashMap | 缓存元数据，避免重复反射 |
| 反射 + 注解 | 运行时解析 @SensitiveField |
