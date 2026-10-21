# 爱管理

项目与任务协作应用。技术栈为 Java 17、Spring Boot 3.3.5、Thymeleaf、Spring Data JPA 和 MySQL。

## 已实现功能

- 注册登录、个人资料、三角色访问控制和角色创建分配。
- 项目创建编辑、成员加入移除、成员状态与职责管理。
- 项目状态及变更历史、负责人项目筛选和排序；侧栏按当前项目显示名称并可收起。
- 项目负责人可以勾选进展内容，预览、复制或下载项目进展 PNG 图片；图片不提供访问权限。
- Backlog 需求与用户故事、验收标准、优先级。
- 任务创建编辑、分配、截止日期、优先级及故事关联。
- 我的任务和本人任务状态更新，项目及成员权限校验；完成说明、重开原因与完成时间。
- 我的任务支持月视图、周视图和无截止日期任务列表；负责人可在项目任务日历拖拽改期，或使用表单改期。
- 我的任务显示逾期和未来 3 天内到期的应用内提醒，可标记已读；改期后会重新提醒。
- 项目任务看板、关键词搜索、组合筛选和排序。
- 任务详情、评论、操作记录、阻塞标记及前后置任务依赖。

Sprint 管理和 Backlog 用户故事模块未作功能变更；AI 协作及分析仍为预览，不代表功能已实现。

任务看板入口为 `/tasks`，三个状态区按上下三行排列。仅项目负责人可管理依赖，禁止自依赖、跨项目依赖及循环依赖。

任务允许直接完成，但须填写完成说明、解除阻塞并完成所有前置任务。重开须填写原因；任务负责人或项目负责人可以重开，项目负责人不能代替开发人员宣告完成。若后续任务已完成，须先重开后续任务，才能重开其前置任务。

已完成任务修改工作内容、负责人或新增依赖前须先重开。暂停、移除成员前须转交未完成任务；离组成员仍可只读查看分配给自己的已完成任务，不能继续查看项目其他任务或参与讨论。任务表单携带版本号，过期页面和同时发生的冲突写入不会覆盖其他人的更新。

AI 接入预留 `GET /tasks/{taskId}/context`，返回任务、描述、依赖和讨论记录的 JSON；需要登录并有该项目访问权限，不包含模型调用。

## 构建与测试

需要 JDK 17 或以上、Maven 3.9，以及已启动的 MySQL（可以在 Docker 中运行并映射端口）。不包含依赖缓存、Maven 安装包、数据库文件和本机密码。

在项目根目录执行：

```powershell
mvn test
mvn -DskipTests package
```

自动化测试共 75 项，使用独立 H2 内存库，结果生成在 `target/surefire-reports`。MySQL 状态字段及记录表迁移脚本为 `scripts/migrate-project-status-mysql.sql`；现有 MySQL 业务库升级前需先备份并在测试库验证。当前环境没有 MySQL 客户端或实例，未执行真实 MySQL 回归。

## MySQL 启动

先创建 `aimanage` 数据库，为应用账号授予该库所需权限。已有数据库升级前应备份；`ddl-auto=update` 不保证所有旧字段约束都能自动迁移。

在 PowerShell 设置实际连接信息：

```powershell
$env:AIMANAGE_DB_URL='jdbc:mysql://localhost:3306/aimanage?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&allowPublicKeyRetrieval=true&useSSL=false'
$env:AIMANAGE_DB_USERNAME='aimanage_app'
$env:AIMANAGE_DB_PASSWORD='填写实际密码'
$env:AIMANAGE_BOOTSTRAP_ADMIN_USERNAME='填写管理员账号'
$env:AIMANAGE_BOOTSTRAP_ADMIN_PASSWORD='填写至少12位的管理员初始密码'
powershell -ExecutionPolicy Bypass -File scripts/start-mysql.ps1
```

访问 <http://localhost:8080/login>。后台日志在 `target/app.stdout.log` 和 `target/app.stderr.log`。脚本输出进程编号，用 `Stop-Process -Id 进程编号` 停止应用。

## 临时演示

```powershell
java -jar target/aimanage-prototype-0.0.1-SNAPSHOT.jar --spring.profiles.active=h2 --spring.datasource.url=jdbc:h2:mem:aimanage-demo --spring.jpa.hibernate.ddl-auto=create-drop --aimanage.demo.enabled=true
```

内存模式停止后数据清空。默认 H2 文件配置仅作备用，项目未附带旧数据库。

## 演示账号

| 身份 | 账号 | 密码 |
| --- | --- | --- |
| 项目负责人 | manager | 123456 |
| 开发人员 | developer | 123456 |
| 备用开发人员 | developer2 | 123456 |
| 待加入项目的开发人员 | developer3 | 123456 |
| 系统管理员 | admin | 123456 |

仅在显式设置 `aimanage.demo.enabled=true` 时初始化演示账号和项目。演示账号密码以加盐 PBKDF2 保存；旧数据库中的演示明文密码会在登录成功后升级。正式模式不会创建公开密码的管理员；若数据库没有管理员，首次启动须提供上述管理员环境变量。登录失败按账号和客户端地址在单实例内限制为 15 分钟内最多 5 次；多实例部署应改用共享限流存储。正式部署仍需统一安全框架与完整审计方案。

## 目录

```text
src/main/java       业务源码
src/main/resources  页面、样式、脚本及配置
src/test            测试源码及测试配置
scripts             MySQL 启动脚本
pom.xml             Maven 项目配置
```
