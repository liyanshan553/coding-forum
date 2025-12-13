# 敏感词热更新功能 - 完整代码链路

## 一、功能概述

### 1.1 功能描述

实现敏感词库的动态热更新能力，无需重启应用即可生效新的敏感词规则。

### 1.2 核心特性

| 特性 | 说明 |
|-----|------|
| 热更新 | 敏感词规则存储在 MySQL，定时刷新到内存 |
| 词库合并 | 支持 allow（白名单）和 deny（黑名单）合并 |
| 命中统计 | 敏感词命中次数写入 Redis，供运营分析 |
| 无感发布 | 规则变更不影响线上服务，5分钟内自动生效 |

### 1.3 技术架构

```
┌─────────────────────────────────────────────────────────────────────────┐
│                              整体架构                                    │
└─────────────────────────────────────────────────────────────────────────┘

                    ┌─────────────────┐
                    │   MySQL         │
                    │   global_conf   │
                    └────────┬────────┘
                             │ 5分钟定时拉取
                             ▼
                    ┌─────────────────────────┐
                    │ DynamicConfigContainer  │
                    │ 动态配置容器              │
                    └────────┬────────────────┘
                             │ 配置绑定 + 回调
                             ▼
                    ┌─────────────────────────┐
                    │   SensitiveProperty     │
                    │   敏感词配置类            │
                    └────────┬────────────────┘
                             │ 触发刷新回调
                             ▼
                    ┌─────────────────────────┐
                    │   SensitiveService      │
                    │   敏感词服务              │
                    └────────┬────────────────┘
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
   ┌───────────┐      ┌───────────┐       ┌───────────┐
   │ AI 聊天   │      │ 文章发布  │       │  评论     │
   │ 敏感词检查 │      │ 敏感词替换 │       │ 敏感词检查 │
   └─────┬─────┘      └───────────┘       └───────────┘
         │
         ▼ 命中统计
   ┌───────────┐
   │  Redis    │
   │  Hash     │
   └───────────┘
```

---

## 二、数据库设计

### 2.1 配置表结构

```sql
-- 全局配置表：global_conf
CREATE TABLE `global_conf` (
    `id` BIGINT(20) UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    `key` VARCHAR(255) NOT NULL COMMENT '配置键',
    `value` TEXT COMMENT '配置值',
    `comment` VARCHAR(255) DEFAULT NULL COMMENT '备注',
    `deleted` TINYINT(4) NOT NULL DEFAULT '0' COMMENT '是否删除：0-否，1-是',
    `create_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_key` (`key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='全局配置表';
```

### 2.2 敏感词配置示例

```sql
-- 启用敏感词功能
INSERT INTO global_conf (`key`, `value`, `comment`) VALUES
('paicoding.sensitive.enable', 'true', '是否启用敏感词过滤');

-- 黑名单：需要过滤的敏感词
INSERT INTO global_conf (`key`, `value`, `comment`) VALUES
('paicoding.sensitive.deny', '["赌博","色情","暴力","诈骗"]', '敏感词黑名单');

-- 白名单：不应被误判的词
INSERT INTO global_conf (`key`, `value`, `comment`) VALUES
('paicoding.sensitive.allow', '["胸腔","性能","黑客技术"]', '敏感词白名单');
```

---

## 三、核心代码实现

### 3.1 第一层：动态配置容器

**文件路径**：`paicoding-core/src/main/java/com/github/paicoding/forum/core/autoconf/DynamicConfigContainer.java`

**职责**：从数据库加载配置，定时刷新，触发回调

```java
package com.github.paicoding.forum.core.autoconf;

import com.github.paicoding.forum.core.util.JsonUtil;
import com.github.paicoding.forum.core.util.SpringUtil;
import com.google.common.collect.Maps;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class DynamicConfigContainer implements EnvironmentAware, ApplicationContextAware, CommandLineRunner {

    private ConfigurableEnvironment environment;
    private ApplicationContext applicationContext;

    /**
     * 存储数据库中的全局配置，优先级最高
     */
    @Getter
    public Map<String, Object> cache;

    private DynamicConfigBinder binder;

    /**
     * 配置变更的回调任务
     * Key: 配置类的 Class
     * Value: 配置变更时执行的回调
     */
    @Getter
    private Map<Class, Runnable> refreshCallback = Maps.newHashMap();

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = (ConfigurableEnvironment) environment;
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @PostConstruct
    public void init() {
        cache = Maps.newHashMap();
        bindBeansFromLocalCache("dbConfig", cache);
    }

    /**
     * 从数据库中获取全量的配置信息
     *
     * @return true 表示有信息变更; false 表示无信息变更
     */
    private boolean loadAllConfigFromDb() {
        // 查询所有未删除的配置
        List<Map<String, Object>> list = SpringUtil.getBean(JdbcTemplate.class)
                .queryForList("select `key`, `value` from global_conf where deleted = 0");

        // 转换为 Map
        Map<String, Object> val = Maps.newHashMapWithExpectedSize(list.size());
        for (Map<String, Object> conf : list) {
            val.put(conf.get("key").toString(), conf.get("value").toString());
        }

        // 对比是否有变化
        if (val.equals(cache)) {
            return false;  // 无变化
        }

        // 有变化，更新缓存
        cache.clear();
        cache.putAll(val);
        return true;
    }

    private void bindBeansFromLocalCache(String namespace, Map<String, Object> cache) {
        // 将内存的配置信息设置为最高优先级（覆盖 application.yml）
        MapPropertySource propertySource = new MapPropertySource(namespace, cache);
        environment.getPropertySources().addFirst(propertySource);
        this.binder = new DynamicConfigBinder(this.applicationContext, environment.getPropertySources());
    }

    /**
     * 配置绑定
     */
    public void bind(Bindable bindable) {
        binder.bind(bindable);
    }

    /**
     * 监听配置的变更（核心方法）
     */
    public void reloadConfig() {
        String before = JsonUtil.toStr(cache);
        boolean toRefresh = loadAllConfigFromDb();
        if (toRefresh) {
            refreshConfig();
            log.info("配置刷新! 旧:{}, 新:{}", before, JsonUtil.toStr(cache));
        }
    }

    /**
     * 强制刷新缓存配置
     */
    public void forceRefresh() {
        loadAllConfigFromDb();
        refreshConfig();
        log.info("db配置强制刷新! {}", JsonUtil.toStr(cache));
    }

    /**
     * 刷新所有 @ConfigurationProperties 注解的 Bean
     */
    private void refreshConfig() {
        applicationContext.getBeansWithAnnotation(ConfigurationProperties.class)
                .values().forEach(bean -> {
            // 重新绑定配置值
            Bindable<?> target = Bindable.ofInstance(bean)
                    .withAnnotations(AnnotationUtils.findAnnotation(bean.getClass(), ConfigurationProperties.class));
            bind(target);

            // 触发回调（如敏感词服务的 refresh 方法）
            if (refreshCallback.containsKey(bean.getClass())) {
                refreshCallback.get(bean.getClass()).run();
            }
        });
    }

    /**
     * 注册定时刷新任务
     */
    private void registerConfRefreshTask() {
        Executors.newScheduledThreadPool(1).scheduleAtFixedRate(() -> {
            try {
                reloadConfig();
            } catch (Exception e) {
                log.warn("自动更新db配置信息异常!", e);
            }
        }, 5, 5, TimeUnit.MINUTES);  // 每 5 分钟执行一次
    }

    /**
     * 注册配置变更的回调任务
     *
     * @param bean 配置类实例
     * @param run  回调函数
     */
    public void registerRefreshCallback(Object bean, Runnable run) {
        refreshCallback.put(bean.getClass(), run);
    }

    /**
     * 应用启动之后执行
     */
    @Override
    public void run(String... args) throws Exception {
        reloadConfig();              // 首次加载配置
        registerConfRefreshTask();   // 启动定时任务
    }
}
```

---

### 3.2 第二层：敏感词配置类

**文件路径**：`paicoding-core/src/main/java/com/github/paicoding/forum/core/senstive/SensitiveProperty.java`

**职责**：承载敏感词相关配置，自动绑定数据库值

```java
package com.github.paicoding.forum.core.senstive;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 敏感词相关配置
 *
 * 配置优先级：数据库 > application.yml
 * 支持动态刷新
 */
@Data
@Component
@ConfigurationProperties(prefix = SensitiveProperty.SENSITIVE_KEY_PREFIX)
public class SensitiveProperty {

    public static final String SENSITIVE_KEY_PREFIX = "paicoding.sensitive";

    /**
     * 是否启用敏感词校验
     * 对应数据库 key: paicoding.sensitive.enable
     */
    private Boolean enable;

    /**
     * 自定义的敏感词（黑名单）
     * 对应数据库 key: paicoding.sensitive.deny
     */
    private List<String> deny;

    /**
     * 自定义的非敏感词（白名单）
     * 对应数据库 key: paicoding.sensitive.allow
     */
    private List<String> allow;
}
```

---

### 3.3 第三层：敏感词服务

**文件路径**：`paicoding-core/src/main/java/com/github/paicoding/forum/core/senstive/SensitiveService.java`

**职责**：敏感词匹配、替换、命中统计

```java
package com.github.paicoding.forum.core.senstive;

import com.github.houbb.sensitive.word.api.IWordAllow;
import com.github.houbb.sensitive.word.api.IWordDeny;
import com.github.houbb.sensitive.word.bs.SensitiveWordBs;
import com.github.houbb.sensitive.word.support.allow.WordAllowSystem;
import com.github.houbb.sensitive.word.support.deny.WordDenySystem;
import com.github.paicoding.forum.core.autoconf.DynamicConfigContainer;
import com.github.paicoding.forum.core.cache.RedisClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.BooleanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import javax.annotation.PostConstruct;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class SensitiveService {

    /**
     * 敏感词命中计数的 Redis Key
     * 数据结构：Hash
     * Key: sensitive_word
     * Field: 敏感词
     * Value: 命中次数
     */
    private static final String SENSITIVE_WORD_CNT_PREFIX = "sensitive_word";

    /**
     * 敏感词引擎
     * 使用 volatile 保证多线程可见性
     */
    private volatile SensitiveWordBs sensitiveWordBs;

    @Autowired
    private SensitiveProperty sensitiveConfig;

    @Autowired
    private DynamicConfigContainer dynamicConfigContainer;

    /**
     * 初始化敏感词引擎
     *
     * 执行时机：
     * 1. 应用启动时（@PostConstruct）
     * 2. 配置刷新时（回调触发）
     */
    @PostConstruct
    public void refresh() {
        // 注册配置刷新回调：当 SensitiveProperty 配置变更时，重新执行 refresh()
        dynamicConfigContainer.registerRefreshCallback(sensitiveConfig, this::refresh);

        // 构建黑名单：系统内置词库 + 数据库自定义词库
        IWordDeny deny = () -> {
            List<String> sub = WordDenySystem.getInstance().deny();  // 系统内置
            if (sensitiveConfig.getDeny() != null) {
                sub.addAll(sensitiveConfig.getDeny());               // 数据库配置
            }
            return sub;
        };

        // 构建白名单：系统内置词库 + 数据库自定义词库
        IWordAllow allow = () -> {
            List<String> sub = WordAllowSystem.getInstance().allow();
            if (sensitiveConfig.getAllow() != null) {
                sub.addAll(sensitiveConfig.getAllow());
            }
            return sub;
        };

        // 重建敏感词引擎（基于 DFA 算法）
        sensitiveWordBs = SensitiveWordBs.newInstance()
                .wordDeny(deny)
                .wordAllow(allow)
                .init();

        log.info("敏感词初始化完成!");
    }

    /**
     * 检查文本是否包含敏感词
     *
     * @param txt 需要校验的文本
     * @return 返回命中的敏感词列表
     */
    public List<String> contains(String txt) {
        // 未启用敏感词功能，直接返回空
        if (!BooleanUtils.isTrue(sensitiveConfig.getEnable())) {
            return Collections.emptyList();
        }

        // 使用 DFA 算法查找所有敏感词
        List<String> ans = sensitiveWordBs.findAll(txt);

        if (CollectionUtils.isEmpty(ans)) {
            return ans;
        }

        // 命中统计：使用 Redis Pipeline 批量写入
        RedisClient.PipelineAction action = RedisClient.pipelineAction();
        ans.forEach(key -> action.add(
                SENSITIVE_WORD_CNT_PREFIX,  // Hash Key
                key,                         // Hash Field（敏感词）
                (connection, k, v) -> connection.hIncrBy(k, v, 1)  // Value +1
        ));
        action.execute();

        return ans;
    }

    /**
     * 获取敏感词命中统计
     *
     * @return key: 敏感词, value: 命中次数
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

    /**
     * 敏感词替换（替换为 ***）
     *
     * @param txt 原始文本
     * @return 替换后的文本
     */
    public String replace(String txt) {
        if (BooleanUtils.isTrue(sensitiveConfig.getEnable())) {
            return sensitiveWordBs.replace(txt);
        }
        return txt;
    }

    /**
     * 查询文本中所有命中的敏感词（不做统计）
     */
    public List<String> findAll(String txt) {
        return sensitiveWordBs.findAll(txt);
    }
}
```

---

### 3.4 第四层：Redis Pipeline 实现

**文件路径**：`paicoding-core/src/main/java/com/github/paicoding/forum/core/cache/RedisClient.java`

**职责**：批量操作 Redis，减少网络往返

```java
/**
 * 创建 Pipeline 操作
 */
public static PipelineAction pipelineAction() {
    return new PipelineAction();
}

/**
 * Pipeline 操作封装类
 */
public static class PipelineAction {
    private List<Runnable> actions = new ArrayList<>();
    private RedisConnection connection;

    /**
     * 添加单 Key 操作
     */
    public PipelineAction add(String key, BiConsumer<RedisConnection, byte[]> conn) {
        actions.add(() -> conn.accept(connection, keyBytes(key)));
        return this;
    }

    /**
     * 添加 Hash 操作（Key + Field）
     */
    public PipelineAction add(String key, String field,
                              ThreeConsumer<RedisConnection, byte[], byte[]> conn) {
        actions.add(() -> conn.accept(connection, keyBytes(key), valBytes(field)));
        return this;
    }

    /**
     * 执行所有操作
     */
    public void execute() {
        template.execute((RedisCallback<Object>) connection -> {
            PipelineAction.this.connection = connection;
            connection.openPipeline();
            actions.forEach(Runnable::run);
            connection.closePipeline();
            return null;
        });
    }
}
```

---

### 3.5 第五层：业务调用示例

#### 示例1：AI 聊天敏感词检查

**文件路径**：`paicoding-service/src/main/java/com/github/paicoding/forum/service/chatai/service/AbsChatService.java`

```java
@Autowired
private SensitiveService sensitiveService;

protected AiChatStatEnum answer(Long user, ChatRecordsVo res) {
    ChatItemVo itemVo = res.getRecords().get(0);

    // 检查用户提问是否包含敏感词
    List<String> sensitiveWords = sensitiveService.contains(itemVo.getQuestion());

    if (!CollectionUtils.isEmpty(sensitiveWords)) {
        // 命中敏感词，返回错误提示
        itemVo.initAnswer(String.format("您的提问包含敏感词：%s，请修改后重试", sensitiveWords));
        return AiChatStatEnum.ERROR;
    }

    // 正常调用 AI
    return doAnswer(user, itemVo);
}
```

#### 示例2：异步聊天敏感词检查

```java
@Override
public ChatRecordsVo asyncChat(Long user, String question, Consumer<ChatRecordsVo> consumer) {
    ChatRecordsVo res = initResVo(user, question);

    // 检查敏感词
    List<String> sensitiveWord = sensitiveService.contains(res.getRecords().get(0).getQuestion());

    if (!CollectionUtils.isEmpty(sensitiveWord)) {
        // 包含敏感词，直接返回异常
        res.getRecords().get(0).initAnswer(
            String.format("您的提问包含敏感词：%s", sensitiveWord));
        consumer.accept(res);
        return res;
    }

    // 异步调用 AI
    // ...
}
```

---

## 四、执行流程

### 4.1 应用启动流程

```
1. Spring 容器启动
   │
   ▼
2. @PostConstruct: DynamicConfigContainer.init()
   │  - 初始化缓存 Map
   │  - 创建配置绑定器
   │
   ▼
3. @PostConstruct: SensitiveService.refresh()
   │  - 注册配置刷新回调
   │  - 构建敏感词引擎
   │
   ▼
4. CommandLineRunner: DynamicConfigContainer.run()
   │
   ├──▶ reloadConfig()
   │       │
   │       ├──▶ loadAllConfigFromDb()     // 从 MySQL 加载配置
   │       │       SQL: SELECT `key`, `value` FROM global_conf WHERE deleted = 0
   │       │
   │       └──▶ refreshConfig()           // 刷新配置
   │               │
   │               ├──▶ bind(SensitiveProperty)  // 重新绑定配置值
   │               │
   │               └──▶ SensitiveService.refresh()  // 触发回调，重建敏感词引擎
   │
   └──▶ registerConfRefreshTask()         // 启动定时任务
           │
           └──▶ ScheduledExecutor.scheduleAtFixedRate(reloadConfig, 5, 5, MINUTES)
```

### 4.2 定时刷新流程

```
每 5 分钟执行一次
   │
   ▼
reloadConfig()
   │
   ├──▶ loadAllConfigFromDb()
   │       │
   │       └──▶ 对比 cache 是否有变化
   │               │
   │               ├── 无变化 → 直接返回
   │               │
   │               └── 有变化 → 更新 cache
   │
   └──▶ refreshConfig() （仅在有变化时执行）
           │
           ├──▶ 遍历所有 @ConfigurationProperties Bean
           │
           ├──▶ 重新绑定配置值
           │
           └──▶ 触发回调
                   │
                   └──▶ SensitiveService.refresh()
                           │
                           └──▶ 重建敏感词引擎
```

### 4.3 敏感词检查流程

```
用户请求（如发送 AI 问题）
   │
   ▼
sensitiveService.contains(txt)
   │
   ├──▶ 检查 enable 配置
   │       │
   │       └── false → 返回空列表
   │
   ├──▶ sensitiveWordBs.findAll(txt)    // DFA 算法匹配
   │       │
   │       └── 返回命中的敏感词列表
   │
   └──▶ 命中统计（如果有命中）
           │
           └──▶ RedisClient.pipelineAction()
                   │
                   ├──▶ HINCRBY sensitive_word "敏感词1" 1
                   ├──▶ HINCRBY sensitive_word "敏感词2" 1
                   └──▶ execute()  // 批量执行
```

---

## 五、Redis 数据结构

### 5.1 命中统计

```
Key:    sensitive_word (Hash)
Field:  敏感词内容
Value:  命中次数

示例：
┌─────────────────────────────────────┐
│           sensitive_word            │
├─────────────────┬───────────────────┤
│     Field       │      Value        │
├─────────────────┼───────────────────┤
│     赌博        │        156        │
│     色情        │         89        │
│     暴力        │         45        │
│     诈骗        │         23        │
└─────────────────┴───────────────────┘
```

### 5.2 查询命中统计

```java
// 获取所有敏感词的命中统计
Map<String, Integer> stats = sensitiveService.getHitSensitiveWords();

// 结果示例
// {
//     "赌博": 156,
//     "色情": 89,
//     "暴力": 45,
//     "诈骗": 23
// }
```

---

## 六、关键技术点

### 6.1 volatile 关键字

```java
private volatile SensitiveWordBs sensitiveWordBs;
```

**作用**：保证多线程可见性

**场景**：
- 定时任务线程修改 `sensitiveWordBs`（重建引擎）
- 业务线程读取 `sensitiveWordBs`（敏感词检查）

**不用 volatile 的问题**：
- 业务线程可能读到旧的引擎实例
- 导致新配置的敏感词不生效

### 6.2 回调机制

```java
// 注册回调
dynamicConfigContainer.registerRefreshCallback(sensitiveConfig, this::refresh);

// 触发回调
if (refreshCallback.containsKey(bean.getClass())) {
    refreshCallback.get(bean.getClass()).run();
}
```

**优点**：
- 配置类和业务逻辑解耦
- 配置变更时自动触发业务刷新
- 支持多个配置类各自注册回调

### 6.3 Pipeline 批量操作

```java
RedisClient.PipelineAction action = RedisClient.pipelineAction();
ans.forEach(key -> action.add(SENSITIVE_WORD_CNT_PREFIX, key,
    (connection, k, v) -> connection.hIncrBy(k, v, 1)));
action.execute();
```

**优点**：
- 多个命令打包成一次网络请求
- 减少网络往返时间
- 适合批量写入场景

**对比**：
```
普通方式：5 个敏感词 = 5 次网络请求
Pipeline：5 个敏感词 = 1 次网络请求
```

### 6.4 词库合并策略

```java
IWordDeny deny = () -> {
    List<String> sub = WordDenySystem.getInstance().deny();  // 系统内置（约 1000+ 词）
    sub.addAll(sensitiveConfig.getDeny());                   // 数据库自定义
    return sub;
};
```

**优点**：
- 系统内置词库保证基础覆盖
- 数据库词库支持业务定制
- 两者合并，既安全又灵活

---

## 七、配置说明

### 7.1 application.yml 默认配置

```yaml
paicoding:
  sensitive:
    enable: true
    deny: []
    allow: []
```

### 7.2 数据库配置（优先级更高）

```sql
-- 这些配置会覆盖 application.yml 中的值
INSERT INTO global_conf (`key`, `value`) VALUES
('paicoding.sensitive.enable', 'true'),
('paicoding.sensitive.deny', '["自定义敏感词1","自定义敏感词2"]'),
('paicoding.sensitive.allow', '["白名单词1","白名单词2"]');
```

---

## 八、运维操作

### 8.1 新增敏感词

```sql
-- 1. 更新数据库配置
UPDATE global_conf
SET `value` = '["赌博","色情","暴力","新增敏感词"]'
WHERE `key` = 'paicoding.sensitive.deny';

-- 2. 等待 5 分钟自动生效，或调用接口强制刷新
```

### 8.2 查看命中统计

```bash
# Redis 命令
HGETALL sensitive_word

# 结果
1) "赌博"
2) "156"
3) "色情"
4) "89"
```

### 8.3 清除命中统计

```bash
# 清除单个词的统计
HDEL sensitive_word "赌博"

# 清除所有统计
DEL sensitive_word
```

---

## 九、常见问题

### Q1：配置更新后多久生效？

**答**：最长 5 分钟（定时任务间隔）

如需立即生效，可调用强制刷新接口：
```java
dynamicConfigContainer.forceRefresh();
```

### Q2：敏感词匹配性能如何？

**答**：使用 DFA 算法，时间复杂度 O(n)，n 是文本长度，与敏感词数量无关。

### Q3：多实例部署时配置如何同步？

**答**：每个实例独立从数据库拉取配置，5 分钟内所有实例会自动同步。

### Q4：敏感词引擎重建会阻塞业务吗？

**答**：有短暂阻塞（毫秒级），因为 `refresh()` 方法是同步执行的。如需优化，可改成双缓冲机制。

---

## 十、代码文件清单

| 文件路径 | 职责 |
|---------|------|
| `paicoding-core/.../autoconf/DynamicConfigContainer.java` | 动态配置加载与刷新 |
| `paicoding-core/.../senstive/SensitiveProperty.java` | 敏感词配置类 |
| `paicoding-core/.../senstive/SensitiveService.java` | 敏感词服务（核心） |
| `paicoding-core/.../cache/RedisClient.java` | Redis 操作封装 |
| `paicoding-service/.../chatai/service/AbsChatService.java` | 业务调用示例 |
