# 实验四补充：Sprint 2 F01修复源码快照与复测证据

本目录保存对团队提供的 `aimanage-sprint2(2).zip` 进行代码检查后形成的独立修复快照，供实验四补充缺陷修复记录、回归证据和团队合入审查。修复日期为2026年10月20日，按程美嘉的要求使用GitHub账号CHMgGa上传。

## 一、修复了什么

在提供的Sprint 2版本中，负责人将任务关联到用户故事后，再次进入工作台时，页面访问 `task.story.title` 会因请求事务已结束而触发 `LazyInitializationException`，导致页面返回不完整。

业务修改集中在 `TaskRepository.java`：项目任务列表、个人任务列表的两处 `@EntityGraph` 增加 `story`。继续保持 `spring.jpa.open-in-view=false`。新增 `TaskStoryRenderingRegressionTest`，分别验证关联保存后的独立GET请求和查询事务结束后的故事标题读取，测试不使用外围 `@Transactional`。

## 二、这个目录与仓库其他代码的关系

修复基线是提供的Sprint 2压缩包，其SHA-256为：

`8caff38590df4a1e5df24fdfa3c4cd859e366ac610d3c84d2d4337366e11e827`

上传分支从 `main@db78532b51960c9ee4236733686fafa8081a2151` 建立，增加本目录作为独立Maven项目。仓库根目录中的原项目与本目录是分别构建的两个代码快照，请进入 `aimanage-sprint2` 后执行Maven命令。

上传时核对的 `weiyi@f014420efe1dcf8ff91a534a3cf1aa3f87e5ee01` 包含不同的Sprint/UML等代码，其任务查询已加载 `story` 及更多关联。团队合入时应对照目标版本保留已有功能，不能用本快照整体替换该分支，也不能据此认定该分支存在同一缺陷。

## 三、已完成的本地验证

| 检查 | 实际结果 | 证据 |
| --- | --- | --- |
| 新增回归对原业务代码复现 | 2项均报原关联加载错误 | `evidence/regression-before.log`、对应txt报告 |
| 修复后完整自动化测试 | 原66项+新增2项，共68项通过；失败、错误、跳过均为0 | `evidence/test-summary.json`、`test-after.log`、txt报告 |
| Maven打包 | 成功，退出码0 | `evidence/package.log`、`package-result.json` |
| 修复jar的真实HTTP流程 | 11项全部通过，故事关联全程保留 | `evidence/http-verification.json`、同名log |
| 补丁适用性 | 在提供的原始Sprint 2快照上 `git apply --check` 通过 | `evidence/patch-apply-check.json` |

上述记录于本地Java 17.0.20、Maven 3.9.9、H2内存库环境产生。日志与结果JSON原样保留，部分日志包含原运行目录和容器时区；JSON中的时间带Asia/Shanghai时区。当前环境通过Surefire的 `argLine` 预加载项目依赖中的Mockito/Byte Buddy代理，未改变应用事务配置或测试断言。

HTTP记录对应独立修复交付包的dist jar，其SHA-256为 `960fa76dda07235d8826f8161decc2467aed69e8a6f2d989735c7e899f349f1b`。GitHub上传包含相同的业务与测试源码，并调整README以适配源码构建方式；jar、Maven依赖缓存、临时数据库及代理配置未纳入源码快照。GitHub上的源码可以按下述命令重新构建，构建产物的哈希可能因构建时间或环境不同而变化。

当前验证不覆盖MySQL、旧数据库升级、浏览器点击与视觉、真实模型调用及最终Sprint 3累计版本。HTTP第11项只确认预览页边界，不能视为Sprint、AI或分析功能已完成验收。本次上传供团队审查和后续集成使用，未创建正式发布Tag，也未作发布就绪结论。

## 四、从源码运行

需要JDK 17和Maven 3.9。在仓库根目录执行：

```powershell
cd deliverables/sprint2-f01-20261020/aimanage-sprint2
mvn test
mvn -DskipTests package
java -jar target/aimanage-prototype-0.0.1-SNAPSHOT.jar --spring.profiles.active=h2 "--spring.datasource.url=jdbc:h2:mem:aimanage-demo;MODE=MySQL;DB_CLOSE_DELAY=-1" --spring.jpa.hibernate.ddl-auto=create-drop
```

访问 `http://localhost:8080/login`。新H2库初始化演示账号：manager、developer、developer2、admin，初始密码均为123456。按Ctrl+C停止后内存数据清空。MySQL启动说明见项目README。

如测试环境禁止Mockito自动挂载，可在依赖已下载后，额外传入 `-DargLine=-javaagent:本机byte-buddy-agent-1.14.19.jar的绝对路径`；原运行参数见 `evidence/test-after-result.json`，路径应替换为本机实际位置。

如需重新执行11项HTTP检查，先安装Python 3和lxml，构建jar后回到本目录，执行 `python verify_http_flow.py`。脚本会在独立H2内存库启动本地应用，并在结束时停止；它会覆盖本目录的HTTP证据文件，因此请在另存的工作副本中复测，保留提交中的历史证据。

## 五、目录与合入方式

| 路径 | 用途 |
| --- | --- |
| `aimanage-sprint2/` | 可独立构建的完整Sprint 2修复源码、测试、README与Release Notes |
| `changes/F01.patch` | 仅包含核心查询修复和新增回归的补丁 |
| `evidence/` | 修复前失败、修复后通过、打包与HTTP验证的原始文本记录 |
| `verify_http_flow.py` | 可重复运行的本地HTTP检查脚本 |
| `SOURCE_MANIFEST.json` | 上传源码与补丁的SHA-256和Git blob校验信息 |

建议团队先审查补丁，再将适用的查询加载方式和回归覆盖整合到最终累计版本；保留目标分支已有的Sprint/UML/其他关联加载。在最终提交上重新运行完整测试和主、异常旅程，再记录实际发布范围与发布检查结论。
