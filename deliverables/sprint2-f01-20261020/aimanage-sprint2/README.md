# 爱管理

## 2026年10月20日 F01修复版

本版本修复“任务关联用户故事后，负责人工作台无法完整显示”的问题。在任务列表读取时显式加载关联故事；新增两项无外围测试事务的回归，保留`spring.jpa.open-in-view=false`。

本地复核结果：68项原有及新增自动化测试全部通过；打包成功；从随包`dist`中的可执行jar启动后，11项HTTP检查全部通过。故事关联在后续操作中一直保留，没有通过清除关联跳过F01。

### 从源码构建并运行（GitHub源码快照）

此GitHub目录保存源码和复核证据，未提交可执行jar。历史HTTP记录来自独立修复交付包中的dist jar。

在本目录打开终端，需有JDK 17和Maven 3.9。先运行测试、构建，再启动：

```powershell
mvn test
mvn -DskipTests package
java -jar target/aimanage-prototype-0.0.1-SNAPSHOT.jar --spring.profiles.active=h2 "--spring.datasource.url=jdbc:h2:mem:aimanage-demo;MODE=MySQL;DB_CLOSE_DELAY=-1" --spring.jpa.hibernate.ddl-auto=create-drop
```

访问`http://localhost:8080/login`；新库演示账号为manager、developer、developer2和admin，初始密码均为123456。按`Ctrl+C`停止，内存库数据随进程结束清空。默认配置为MySQL，快速演示须显式指定h2。源码构建和测试仍需JDK 17、Maven 3.9。

本次检查使用Linux、Java 17.0.20及Maven 3.9.9。当前环境不能自动挂载Mockito代理，复核时通过Surefire的argLine预加载项目已有的byte-buddy-agent-1.14.19.jar；真实命令保留在上级evidence目录。未修改测试断言或应用事务配置。

修复范围是现有Sprint 2快照。MySQL、旧业务库、浏览器点击与视觉、真实模型、UML及最终Sprint 3累计版本仍需验证。Sprint、AI和分析的预览范围沿用本版实际状态。详情见`RELEASE_NOTES_F01.md`；代码补丁与复核证据见上级changes、evidence目录。

来自最新版 Sprint 1 源码，已补充 Sprint 2 任务协作功能。技术栈为 Java 17、Spring Boot 3.3.5、Thymeleaf、Spring Data JPA 和 MySQL。

## 已实现功能

- 注册登录、个人资料、三角色访问控制和角色创建分配。
- 项目创建编辑、成员加入移除、成员状态与职责管理。
- Backlog 需求与用户故事、验收标准、优先级。
- 任务创建编辑、分配、截止日期、优先级及故事关联。
- 我的任务和本人任务状态更新，项目及成员权限校验；完成说明、重开原因与完成时间。
- 项目任务看板、关键词搜索、组合筛选和排序。
- 任务详情、评论、操作记录、阻塞标记及前后置任务依赖。

Sprint 管理和 Backlog 用户故事模块未作功能变更；AI 协作及分析仍为预览，不代表功能已实现。

任务看板入口为 `/tasks`，三个状态区按上下三行排列。仅项目负责人可管理依赖，禁止自依赖、跨项目依赖及循环依赖。

任务允许直接完成，但须填写完成说明、解除阻塞并完成所有前置任务。重开须填写原因；任务负责人或项目负责人可以重开，项目负责人不能代替开发人员宣告完成。前置任务重开不自动回退后续已完成任务。

已完成任务修改工作内容、负责人或新增依赖前须先重开。暂停、移除成员前须转交未完成任务，已完成任务保留历史负责人。任务表单携带版本号，过期页面和同时发生的冲突写入不会覆盖其他人的更新。

AI 接入预留 `GET /tasks/{taskId}/context`，返回任务、描述、依赖和讨论记录的 JSON；需要登录并有该项目访问权限，不包含模型调用。

## 构建与测试

需要 JDK 17 或以上和 Maven 3.9。自动化测试使用独立H2库，无需先启动MySQL；使用MySQL运行应用时，才需准备该数据库。不包含依赖缓存、Maven 安装包、数据库文件和本机密码。

在项目根目录执行：

```powershell
mvn test
mvn -DskipTests package
```

自动化测试共 68 项（原有66项加2项F01回归），使用独立 H2 内存库，结果生成在 `target/surefire-reports`。本修复版的68项测试及11项HTTP流程复测均通过。原版README曾记录Docker MySQL独立测试库验证；本次F01修复没有重跑MySQL或旧数据升级，仍需在目标数据库另行验证。

## MySQL 启动

先创建 `aimanage` 数据库，为应用账号授予该库所需权限。已有数据库升级前应备份；`ddl-auto=update` 不保证所有旧字段约束都能自动迁移。

在 PowerShell 设置实际连接信息：

```powershell
$env:AIMANAGE_DB_URL='jdbc:mysql://localhost:3306/aimanage?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
$env:AIMANAGE_DB_USERNAME='aimanage_app'
$env:AIMANAGE_DB_PASSWORD='填写实际密码'
powershell -ExecutionPolicy Bypass -File scripts/start-mysql.ps1
```

访问 <http://localhost:8080/login>。后台日志在 `target/app.stdout.log` 和 `target/app.stderr.log`。脚本输出进程编号，用 `Stop-Process -Id 进程编号` 停止应用。

## 临时演示

```powershell
java -jar target/aimanage-prototype-0.0.1-SNAPSHOT.jar --spring.profiles.active=h2 --spring.datasource.url=jdbc:h2:mem:aimanage-demo --spring.jpa.hibernate.ddl-auto=create-drop
```

内存模式停止后数据清空。默认 H2 文件配置仅作备用，项目未附带旧数据库。

## 演示账号

| 身份 | 账号 | 密码 |
| --- | --- | --- |
| 项目负责人 | manager | 123456 |
| 开发人员 | developer | 123456 |
| 备用开发人员 | developer2 | 123456 |
| 系统管理员 | admin | 123456 |

新数据库启动时初始化演示数据。密码使用加盐 PBKDF2，旧演示明文密码成功登录后升级。项目用于课堂演示；统一 CSRF 防护、登录限流和完整操作日志仍需完善。

## 目录

```text
src/main/java       业务源码
src/main/resources  页面、样式、脚本及配置
src/test            测试源码及测试配置
scripts             MySQL 启动脚本
pom.xml             Maven 项目配置
```
