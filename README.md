# 爱管理｜Sprint 1 可运行原型

这是一个适合作业演示的项目协作系统原型，使用 Java 17、Spring Boot、Thymeleaf 和 Spring Data JPA 开发。默认使用免安装的 H2 文件数据库，也可以切换到 MySQL。

前端采用克制的 Notion 风格：米色侧栏、白色文档区、轻边框、小圆角、线性图标和低干扰悬停反馈。桌面、平板和手机均可使用。

## 已实现的用户故事

| 用户故事 | 原型中的实现 |
| --- | --- |
| Epic1-US02 用户登录 | 账号密码登录，错误信息提示，按角色进入工作台 |
| Epic1-US03 项目负责人登录 | 自动识别项目负责人角色并进入项目工作台 |
| Epic1-US07 项目创建 | 填写名称和简介创建项目 |
| Epic1-US09 项目成员加入 | 从开发人员中添加成员，阻止重复添加 |
| Epic2-US06 任务创建与编辑 | 创建任务并编辑标题、描述、截止日期、负责人 |
| Epic2-US07 任务负责人分配 | 只能把任务分配给当前项目成员 |
| Epic2-US09 任务状态更新 | 开发人员可将自己的任务更新为待处理、进行中、已完成 |
| Epic2-US11 我的任务 | 开发人员登录后只看到分配给自己的任务 |

## 完整产品界面

除以上 8 条真实功能外，原型还为当前需求清单中的全部 49 条用户故事提供了可见界面、导航入口和操作按钮。它们按 8 个模块组织：

- 账户与个人资料；
- 项目与成员；
- 角色与权限；
- Product Backlog；
- Sprint 管理；
- 任务与协作；
- AI 协作；
- 数据与分析。

标记为“界面预览”的按钮不写入数据，点击时会提示“将在后续 Sprint 中实现”；标记为“已实现”的功能会进入当前可运行的 Sprint 1 工作流。这样既能完整展示产品规划，也不会让演示者误把占位交互当成已完成业务。

## 最快运行方式

环境要求：JDK 17、Maven 3.9 或兼容版本。

在项目根目录双击 `start.cmd`，或直接在终端运行已经打包好的程序：

```powershell
java -jar target/aimanage-prototype-0.0.1-SNAPSHOT.jar
```

看到 `Started AiManageApplication` 后，浏览器访问：

<http://localhost:8080>

首次启动会自动创建数据库和演示数据，无需安装或配置 MySQL。数据保存在项目的 `data` 目录中，重启不会丢失。

## 演示账号

| 角色 | 账号 | 密码 |
| --- | --- | --- |
| 项目负责人 | `manager` | `123456` |
| 开发人员 | `developer` | `123456` |
| 备用开发人员 | `developer2` | `123456` |

登录页点击演示账号卡片，可以自动填入账号和密码。

## 推荐演示流程

1. 使用 `manager / 123456` 登录。
2. 点击“创建项目”，填写项目名称和简介。
3. 在新项目中点击“添加成员”，选择开发人员。
4. 点击“新建任务”，填写任务内容并选择负责人。
5. 退出项目负责人账号。
6. 使用刚才被分配任务的开发人员账号登录。
7. 在“我的任务”中查看任务，并将状态改为“进行中”或“已完成”。
8. 再次使用项目负责人账号登录，可看到任务最新状态与整体进度。
9. 使用左侧导航依次打开其他模块，展示 49 条用户故事的完整界面规划。

项目中已预置一个示例项目和一条任务，方便快速展示页面；也可以按上述步骤新建完整数据。

## 运行测试

```powershell
mvn "-Dmaven.repo.local=.m2-cache" test
```

修改源码后，可重新生成可执行程序：

```powershell
mvn "-Dmaven.repo.local=.m2-cache" -DskipTests clean package
```

基础测试覆盖：

- 正确登录与错误密码；
- 项目创建、成员加入及重复加入校验；
- 任务只能分配给项目成员；
- 只有任务负责人能更新任务状态。
- 项目负责人和开发人员页面可以正常渲染；
- 登录后可以打开全部 8 个界面预览模块。

## 切换到 MySQL

1. 打开 `src/main/resources/application-mysql.properties`。
2. 修改 `spring.datasource.username` 和 `spring.datasource.password`。
3. 确保本机 MySQL 服务正在运行。
4. 使用 MySQL 配置启动：

```powershell
mvn "-Dmaven.repo.local=.m2-cache" -DskipTests package
java -jar target/aimanage-prototype-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql
```

配置中启用了 `createDatabaseIfNotExist=true`，账号有建库权限时会自动创建 `aimanage` 数据库；否则请先手动执行：

```sql
CREATE DATABASE aimanage CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

## 项目结构

```text
src/main/java/com/aimanage/prototype
├─ config       演示数据初始化
├─ controller   登录、负责人工作台、我的任务
├─ domain       用户、项目、成员、任务实体
├─ repository   数据访问接口
└─ service      登录与核心业务规则

src/main/resources
├─ templates    登录、工作台、我的任务、通用预览页面与侧栏
├─ static       页面样式和交互脚本
└─ application*.properties
```

## 原型说明

本项目以课堂演示为目标。Sprint 1 的 8 条用户故事具备真实业务闭环，其他功能目前只实现前端展示。账号密码使用明文演示数据，未引入 Spring Security。正式系统应增加密码哈希、CSRF 防护、完整权限拦截、操作日志和更严格的参数校验。

## AI 助手模块（独立原型，待平台接入）

仓库另含 `aiguanli-ai/`：基于 Spring AI、DeepSeek 和向量检索的独立 AI 模块，支持群聊纪要、需求拆解、RAG 问答、模拟进度工具分析、固定三角色协作及人工审核决策入库。

请先看 [AI 模块交接入口](AI-HANDOFF.md)，再按 [AI 模块运行说明](aiguanli-ai/README.md) 在该子目录单独启动。当前尚未连接本站页面、登录身份和数据库；网站中的 AI 预览入口不会自动调用该模块。交接字段与演示 JSON 见 [handoff](aiguanli-ai/handoff/README.md)。
