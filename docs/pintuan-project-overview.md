# 技术派（PaiCoding）社区系统架构与业务概览

## 项目概览
- 基于 Spring Boot、MyBatis-Plus、MySQL、Redis、ElasticSearch、MongoDB、RabbitMQ、Docker 等技术栈构建的开源技术社区系统。
- 提供文章与教程发布、搜索、评论、统计等完整内容运营闭环，支持一键部署与多端演示（前台社区、管理后台、Vue3 重写版）。

## 模块与架构
- **paicoding-api**：通用枚举、实体、DO/DTO/VO 定义，作为业务模型基础。
- **paicoding-core**：工具与通用组件层（搜索、缓存、推荐等），为上层业务提供可复用能力。
- **paicoding-service**：服务层，封装文章/用户/评论等核心业务逻辑与 MyBatis-Plus 数据访问。
- **paicoding-ui**：前端模板与静态资源（Thymeleaf、JS、CSS），支撑社区界面渲染。
- **paicoding-web**：Web 入口与控制层，包含权限校验、全局异常处理、`QuickForumApplication` 启动入口等。
- **paicoding-admin（独立仓库）**：运营管理后台，承担内容与活动的管理发布。

## 功能范围
- 文章与教程管理：发布、浏览、搜索、评论、统计等核心社区流程。
- 账户与身份：支持 Session/Cookie/JWT 登录，含微信扫码/公众号自动登录等扩展方案。
- 社区互动与榜单：阅读量、点赞、收藏、活跃排行榜等社区运营能力。
- 搜索与推荐：ElasticSearch 支撑全文检索，结合 Redis 缓存与推荐组件提升响应速度。
- 媒体与资源：图片上传、对象存储、富文本渲染等多媒体支持。

## 业务流程概览
1. 用户访问社区页面，通过 `paicoding-web` 控制层渲染 `paicoding-ui` 模板或前后端分离版本。
2. 登录/鉴权后，访问文章、教程、评论等资源；请求由 `paicoding-service` 调用数据库与缓存完成读写。
3. 搜索请求路由至 ElasticSearch，查询结果结合缓存/排行榜能力进行排序与展示。
4. 评论、点赞、浏览等行为同步或异步写入数据库/Redis，并用于统计与推荐。
5. 管理后台（独立部署）提供内容管理、配置与运营数据查看，与主站形成闭环。

## 技术亮点
- **多环境配置与一键切换**：`paicoding-web` 提供 dev/test/pre/prod 环境配置，Maven Profile 切换即生效。
- **完善的技术栈组合**：MyBatis-Plus ORM、Redis 缓存、ElasticSearch 搜索、RabbitMQ 消息、MongoDB 文档存储，覆盖高并发常见场景。
- **模板与前后端分离并行**：既提供 Thymeleaf SSR 版本，也有 Vue3 + Spring Boot3 改造分支，方便二次开发。
- **数据库版本管理**：Liquibase 维护表结构演进，便于多人协作与持续交付。
- **开源生态与教程支撑**：配套 120+ 教程与示例，便于学习、简历输出和业务落地。

## 配置与部署要点
- 资源配置集中于 `paicoding-web/src/main/resources` 及 `resources-env/<env>`，包含 `application.yml`、站点配置、日志、数据库与图片上传等。
- 通过 `mvn clean install -DskipTests=true -P<env>` 选择环境并打包；生产可用 `launch.sh` 一键部署或 Docker 容器化。
- 运行依赖 MySQL、Redis（搜索依赖 ElasticSearch，可选 RabbitMQ/MongoDB），需提前准备对应服务与连接配置。
