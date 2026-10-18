# 爱管理独立 AI 助手：对话记忆、聊天解析与 RAG

本项目使用 Spring Boot 3.5.9、Spring AI 1.1.2 和 Java 17，通过控制台调用 DeepSeek 官方 API。依赖名中的 openai 表示协议适配器，实际调用的是 DeepSeek。

当前实现控制台问答、近期对话记忆、独立聊天记录解析、结构化需求拆解、项目文档 RAG、联网导入公共项目管理资料、基于模拟任务快照的只读工具调用、三角色固定交接，以及群聊决策提取、人工审核后入库。不启动 Web 服务，不接业务数据库。

后续交接可先看 [AI 模块交接说明](handoff/README.md) 和 [9 个静态 JSON 示例](handoff/examples.json)。材料已核对现有 Java 返回类型，尚未定义或发布 HTTP 接口，不包含用户实际运行记录或模型密钥。

当前默认关闭本地业务数据持久化（`app.storage.local-persistence-enabled=false`）。需求拆解和进度分析只显示结果，不生成本地报告；RAG 仅使用本次运行的内存索引，不读取或更新旧索引文件，也不通过临时文件周转向量。退出后内存知识库清空，重启使用 RAG 需重新导入。已有本地文件保留不动。终端会明确提示“尚未持久化”，远程存储尚未接通。

正式对接时，AI 结果可交给队友后端，由后端统一鉴权并写入他们的数据库，数据库无需安装在本机。该远程链路目前未实现。下面涉及文件保存及重启恢复的说明，仅适用于明确启用本地持久化的兼容模式；当前默认模式不执行这些写入。

## 公共软件项目管理知识

公共资料从以下原始发布方网站获取，目录仅保存地址和主题，不把网页全文或向量随代码打包。输入 `/sources` 查看目录，列出来源不代表已经完成导入。

| 编号 | 资料 | 用途 |
| --- | --- | --- |
| agile | [敏捷宣言十二原则](https://agilemanifesto.org/iso/zhchs/principles.html) | 交付、协作、持续改进 |
| scrum | [Scrum 指南简体中文，2020-11](https://scrumguides.org/docs/scrumguide/v2020/2020-Scrum-Guide-Chinese-Simplified.pdf) | Sprint、职责、Backlog、完成的定义 |
| stories | [Agile Alliance：User Stories](https://agilealliance.org/glossary/user-stories/) | 用户故事与需求沟通 |
| kanban | [The Kanban Guide，2025.5](https://kanbanguides.org/the-kanban-guide/2025.5/) | 工作流与流动指标 |
| acceptance | [Cucumber：Gherkin Reference](https://cucumber.io/docs/gherkin/reference/) | Given / When / Then 验收场景 |
| risk | [NASA 手册，第 6.4.1 节](https://www.nasa.gov/reference/6-0-crosscutting-technical-management/) | 技术风险管理流程 |
| change | [NASA 手册，第 6.5.1 节](https://www.nasa.gov/reference/6-0-crosscutting-technical-management/) | 配置管理与变更控制流程 |

先退出旧助手，再在 PowerShell 执行：

```powershell
.\run.ps1 -Rag
```

启动后逐条输入：

```text
/sources
/import-public agile
/import-public scrum
/docs
/ask-public Sprint Review 和 Sprint Retrospective 有什么区别？请给出依据。
```

`/import-public all` 可依次导入目录中的全部来源，会下载资料并调用向量 API；单个来源失败不算成功，已成功导入的来源保留。网页导航、脚本等被移除，NASA 只提取指定流程章节；网站改版导致章节无法识别时直接报导入失败。每份资料下载上限 4 MiB，公共资料切分超过 120 块时拒绝向量化。下载、解析、正文、向量快照全部在内存处理，默认不写本地业务文件。HTTP 请求有超时，网页请求不携带模型密钥。

重复导入会重新联网核对正文，正文与版本元数据未变则跳过向量化；资料更新时用新片段替换原片段，失败保留旧索引。实际导入时记录来源标题、发布方、版本、抓取时间及署名/许可提示。抓取时间指当前索引对应正文的获取时间，不代表资料发布日期；跳过重复向量化时保留原抓取时间。引用列表显示网页地址与上述元数据，片段编号是系统切分编号，不冒充原文页码。

`/ask-public` **只检索公共知识**；`/ask` **只检索项目文档和经人工审核入库的群聊决策**。两者都使用独立 RAG 调用，不继承普通聊天记忆。一般方法、NASA 专属流程与项目实际情况需区分；不从公开指南推断你们的完成率、负责人或团队决定。`/decompose` 在 RAG 启用时会检索公共方法资料；`/analyze` 和 `/progress` 尚未接入公共知识。

退出后内存索引清空，需重新导入。公共网站可访问性与实际向量模型语义效果需要分别检查；普通自动测试不访问公网、不调用模型。开发者可通过 Maven 参数 `-Dtest=PublicKnowledgeLiveTest -DpublicKnowledge.liveCheck=true` 单独开启公开网页下载解析检查（仍不调用任何模型）。英文资料保留原文，回答提示词要求中文解释；中文问题检索英文资料的效果需用实际多语言向量模型验证。

## 群聊决策提取、人工审核与项目知识库

当前知识库位于运行 Java 应用的机器内存中，具体由 KnowledgeService 中的向量索引和来源表维护。当前是在本机运行，因此是本机 RAM，不是磁盘业务文件；硅基流动提供向量计算，未被当作项目知识数据库。默认不会自动读写 `.local/knowledge.json`，也没有远程数据库连接。程序退出后正文、向量、候选及审核记录清空。

重启旧助手：`/exit`，然后在 PowerShell 运行 `.\run.ps1 -Rag`。无需先导入公共资料，直接在助手中执行：

```text
/extract-decisions examples/decision-discussion.json
/decisions
```

提取输入是带明确 `simulation` 布尔标记和 `messages` 列表的 UTF-8 JSON。`simulation=true` 表示模拟材料，审核后仍保留模拟标记；false 只表示操作者标为用户提供的非模拟材料，不代表平台权限或数据真实性已验证。消息仍使用 id、speaker、content。已有 `/analyze examples/discussion.json` 的数组格式与原功能保留，不会自动创建审核记录或入库。

程序只从结构化 `decisions` 创建候选；建议、待办、未解决问题不会自动提升为决策。每条候选有程序生成的 `D-xxxxxxxx` 编号、AI 原稿、原始消息、输入 SHA-256 和待审核状态。相同路径和字节内容重复提取时返回已有条目及当前状态，不重复调用模型或重新打开已审核条目。没有提取到明确决策时不创建条目。

用实际显示的编号替换下面的占位文字：

```text
/decision D-实际编号
/review-decision D-实际编号
```

审核会展示候选和原文，依次询问：1=采纳原文、2=修改后采纳、3=否决、0=取消。若不修改草稿，应选 1；选 2 后必须输入完整的新决策文字，后续输入不再是选项编号。程序拦截纯数字、纯标点等明显占位内容；该校验不能证明决策语义正确。审核人和理由必填，提交前并列展示原稿和最终入库文本，最后输入“确认”才提交；取消或输入不完整时保持待审核。审核人目前是终端操作者自报姓名，尚未绑定平台账号或角色。

采纳或修改后采纳后自动加入当前项目知识库；未审核和已否决条目不能入库。拒绝审核不调用向量模型。若向量化或索引保存失败，审核记录保持“已采纳、未入库”，原索引不变，可在当前进程中重试，不需要再次审核：

```text
/publish-decision D-实际编号
```

入库成功后：

```text
/docs
/ask 模拟群聊中，任务创建和任务分配的顺序是什么？请注明依据。
```

检索正文只包含采纳后的决策，不混入未采纳建议、原始 AI 草稿或全部聊天；这些原始证据保留在审核记录及索引审计元数据中。引用会显示决策编号、消息编号、审核人、处理结论、理由、时间和输入文件信息。修改后采纳会保留原稿与改稿供核对；模型问答只读取改稿和审核来源概要。已入库条目重复发布不重复向量化。

这仍是单项目独立原型，未接真实群聊消息入口、登录身份、项目权限、远程审核或数据库。知识库由当前运行的项目资料共用，不宣称实现了多项目隔离。审核候选不可覆盖；新版本聊天可形成新候选，但尚未实现撤回或自动替代旧决策。已审核不代表平台任务已执行，也不会将知识自动加入公共资料库或普通聊天记忆。RAG 启用时，需求拆解和协作分析现在可以检索已入库决策：普通需求拆解排除模拟决策，模拟协作允许引用带模拟标记的决策。

默认所有新业务数据仍只在内存，退出丢失。若明确开启旧文件兼容模式，KnowledgeService 会将已入库决策及证据元数据按该模式写入索引文件；审核候选列表本身仍不持久化。当前未开启此模式。需要跨重启保存、统一审核和项目权限时，应对接队友后端的远程存储，而不是把“内存中已入库”误写成“已保存到数据库”。

## 先检查环境

在此目录打开 PowerShell：

```powershell
.\run.ps1 -Check
```

脚本优先选择 AI_JAVA_HOME、JAVA_HOME 中有效的 JDK 17 及以上版本，或本机已有的 D:\Java_JDK\jdk-17；优先复用用户 Maven Wrapper 缓存里的 Maven 3.9.x。只影响当前终端，不修改系统设置。

其他机器可自行设置：

```powershell
$env:AI_JAVA_HOME = '你的JDK17安装目录'
$env:AI_MAVEN_HOME = '你的Maven3.9安装目录'
```

## 不使用真实密钥的构建与测试

```powershell
.\run.ps1 -Build
```

首次构建需要联网下载依赖，缓存保存在本项目 .build/repository。自动测试使用本机模拟 HTTP 服务，检查 Spring AI 请求、中文提示词和响应解析，不调用任何真实模型，也不产生模型调用费用。测试成功只说明程序调用链可工作，不说明真实服务连接成功。

如果助手还在另一个终端运行，可以用 `.\run.ps1 -Test` 只执行测试，避免覆盖运行中的 jar。测试还检查记忆传递、会话隔离、清空与窗口淘汰、失败回滚、结构化纪要及无效来源引用拦截。RAG 测试使用确定性的模拟向量，验证文档解析、检索流程、索引保存恢复与更新、失败回滚、来源编号及两套密钥隔离，不证明真实向量模型的语义检索质量。

## 接通 DeepSeek 并真实运行

先登录 [DeepSeek 开放平台](https://platform.deepseek.com/)，在 API Keys 页面创建密钥，并确认账户具备 API 调用额度。使用开放平台生成的 API Key，不使用网页登录密码。不要把密钥放入代码、截图、实验报告或聊天。

项目已经设置以下默认值，不需要逐项输入：

| 配置 | 默认值 |
| --- | --- |
| 基础地址 | https://api.deepseek.com |
| 请求路径 | /chat/completions |
| 模型 | deepseek-flash |
| thinking.type | disabled，第一版使用非思考模式 |

这些参数依据当前 [DeepSeek 官方接入说明](https://api-docs.deepseek.com/) 与 [Chat Completions API](https://api-docs.deepseek.com/api/create-chat-completion/)。如开放平台后续调整模型标识，以官方说明和账号可用模型为准。

在本目录运行：

```powershell
.\run.ps1
```

脚本先构建与执行本地测试，然后在缺少密钥时提示隐藏输入 DeepSeek API Key。输入完成后可以开始提问，输入 /exit 退出。

如果 PowerShell 提示禁止运行脚本，先在该终端执行 `Set-ExecutionPolicy -Scope Process -ExecutionPolicy RemoteSigned`，然后重新运行脚本。

密钥仅设置在当前终端环境变量中，不自动写入文件；关闭终端后需重新输入。已有 DEEPSEEK_API_KEY 或 AI_API_KEY 时不再询问。

也可以先隐藏输入密钥再运行一次提问：

```powershell
$modelSecret = Read-Host '输入 DeepSeek API Key' -AsSecureString
$env:DEEPSEEK_API_KEY = [System.Net.NetworkCredential]::new('', $modelSecret).Password
$modelSecret.Dispose()
Remove-Variable modelSecret
.\run.ps1 -Question '请将用户登录功能拆成开发任务草稿，并列出待确认的问题。'
```

需要覆盖默认地址、模型或思考模式时，在运行前设置环境变量：

```powershell
$env:AI_BASE_URL = 'https://api.deepseek.com'
$env:AI_CHAT_PATH = '/chat/completions'
$env:AI_MODEL = 'deepseek-flash'
$env:AI_THINKING_MODE = 'disabled'
```

最终请求地址为 https://api.deepseek.com/chat/completions。基础地址和路径分开配置，避免重复拼接。

## 验证对话记忆

更新代码后，先在正在运行的旧助手中输入 `/exit`，回到 PowerShell 再执行 `.\run.ps1`。在同一 PowerShell 窗口中通常无需重新输入密钥。

连续输入：

```text
我们项目代号叫青竹，当前正在讨论任务分配。
我刚才说的项目代号是什么？
/new
我之前告诉你的项目代号是什么？
```

前两句验证模型能使用上一轮内容。`/new` 后不应再知道旧代号。该能力使用 Spring AI 的 MessageWindowChatMemory 和 MessageChatMemoryAdvisor，按会话隔离，在内存中保留最近 20 条用户与助手消息。历史消息会随新问题一起发送给 DeepSeek，并消耗输入 token。它不是长期记忆，退出后清空，也不等于项目知识库。一次性 `-Question` 模式不复用其他对话。

## 验证需求拆解与草稿保存

更新后先在旧助手中 `/exit`，回到 PowerShell 执行 `.\run.ps1 -Rag`（保留知识库功能），或 `.\run.ps1`（普通模式）。

在助手的“你：”后输入完整的一行：

```text
/decompose 项目负责人可以创建任务并指定负责人和截止日期；开发成员可以更新自己负责的任务状态。
```

专用 ChatClient 通过 Spring AI 的结构化输出生成用户故事、验收标准、关联故事的开发任务、待确认问题和可选建议。只处理本次需求，不附带普通聊天历史，也不读取项目文档。需要项目约束时，将已确认的约束写入本次需求。

在 `-Rag` 模式中，控制台自动调用 `decomposeWithKnowledge`：检索公共方法资料和非模拟的已审核项目决策，再将需求和命中片段提供给 DeepSeek。两类资料分别检索，空的资料范围跳过向量调用；通常最多两次查询向量化加一次结构化生成。需求及检索词会发送给配置的向量服务，需求与相关片段会发送给 DeepSeek。公共资料只提供方法依据，项目决策只作相关背景；与新需求矛盾时应列为待确认，不自动覆盖需求或旧决策。

重启后可只导入本轮所需的两份资料，再验证需求拆解：

```text
/import-public stories
/import-public acceptance
/decompose 项目负责人可以创建任务，填写标题并指定负责人和截止日期；开发成员只能更新自己负责的任务状态。
```

结果中的“公共知识参考”显示程序实际提供的检索片段来源，以及模型声明采用的方法和 `[K1]` 等编号。来源包含原文链接、版本、抓取时间和署名/许可，编号由程序校验。列出检索片段不等于模型采用了全部片段；若模型认为资料不适用，可以返回空的方法引用，并说明未采用理由。结构检查不能证明方法解释确实得到原文支持，仍需人工核对。

未启用 RAG、资料范围为空或没有相关命中时，程序明确标记对应范围没有依据；两类资料都未命中时只按输入需求生成草稿。项目决策显示独立编号、最终审核正文、模拟标记、审核信息和原始消息编号；模型声明是否采用并解释引用，程序校验决策编号。普通项目文件、未入库候选和已否决决策不参与这次决策检索。检索请求失败时停止本次生成，不静默转成普通拆解。默认不保存结果；`SavedDraft.grounding` 保留公共检索及方法引用，`grounding.decisions` 和 `grounding.decisionUsage` 保留本次决策检索和使用声明，供未来远程接口使用。

检查生成草稿是否：

- 覆盖任务创建、分配负责人及截止日期、成员更新本人任务状态。
- 验收标准可检查，开发任务关联存在的故事 id。
- 将必填字段、状态枚举、可选负责人范围等未明确的规则列为待确认。
- 将原需求未提出的通知、搜索、审批等放入可选建议，或不提出这些扩展。
- 不编造实际负责人、时间、工期或已完成状态。

当前默认仅显示需求草稿，提示尚未持久化。兼容模式启用本地持久化时，校验通过后保存为 `.local/drafts/decomposition-<唯一编号>.md`，终端打印完整路径。文件包含原始需求、实际生成时间、配置的模型、可读草稿、结构化结果、公共资料检索与方法引用记录和留空的人工审核记录。结果始终标为“待人工审核”，不会创建平台任务、自动加入 Backlog 或写入知识库。多次保存生成不同文件。

人工审核时填写审核人、采纳/修改后采纳/否决和理由；未审核时保留“待填写”。结构检查不能证明模型的语义判断正确，仍需核对原需求。本地模拟测试不是真实的模型质量评价。目录已被 `.gitignore` 排除，需要作为实验材料提交时，由人工挑选和审核后另行整理。可通过 `AI_DRAFT_DIRECTORY` 配置其他输出目录。

## 验证三个角色的固定协作

`/collaborate` 依次执行：Java 校验与快照统计 → 纪要整理 → 风险评估 → 协调汇总。三个模型角色使用各自的提示词和调用上下文，当前共用配置的同一个 DeepSeek 模型。它是固定顺序的协作原型，不包含自主选择角色、并行调度或自动写入平台的能力。

在旧助手中 `/exit`，回到 PowerShell 运行 `.\run.ps1` 即可；使用 `.\run.ps1 -Rag` 时还会检索已审核项目决策，不需要导入公共资料。重启会清空内存，需重新提取、审核和入库才能引用决策。

```text
/collaborate examples/collaboration.json
```

输入包含模拟聊天与任务快照。程序先检查外层和任务快照的 `simulation=true`、消息编号、任务字段、日期和依赖引用；无效输入在模型调用前被拦截。任务统计由 Java 计算，不经过模型工具请求；已有 `/progress` 命令仍单独验证 Function Calling 协议。示例固定以 2026-10-11 为快照日期，预期完成率 50.00%、延期 T-4、阻塞 T-6，不能改用机器当天日期。

角色之间实际传递结构化结果：风险角色接收原始消息、纪要草稿与统计事实；协调角色接收这些内容和风险草稿。RAG 启用时，在纪要调用之前另执行一次已审核决策检索，冻结命中正文及来源概要，风险和协调角色接收同一份材料，并分别返回决策引用与采用说明。纪要角色仍只整理本轮原始聊天，历史决策的消息编号不混为本轮消息编号。上游模型结果始终是待核对草稿，不混用普通聊天记忆、其他运行快照、普通项目文档或公共知识。模拟决策只能用于模拟分析，来源矛盾应列明，不覆盖 Java 统计。

验证新链路：先用 `examples/decision-discussion.json` 提取决策、人工审核采纳并入库，然后执行 `/collaborate examples/collaboration-decisions.json`。这份模拟材料讨论相同的任务创建与分配，预期 Java 完成率为 0.00%、延期 T-CREATE、阻塞 T-ASSIGN；决策是否命中取决于实际向量检索，成功命中时应展示决策来源及两角色的引用或未采用说明。2026-10-18 用户真实调用已生成完整草稿，两角色引用已审核决策并保留模拟标记。该单例仍出现将同义概括误判为原文无依据的条目，已调整提示词，效果待观察；不能据此认为所有自然语言判断均正确。

输出区分 Java 统计、纪要草稿、候选风险、未执行的协调建议和待确认问题。每个模型条目携带本轮消息、任务、风险编号或整体快照依据。程序检查编号是否存在、是否重复及必要字段；它不能验证每条自然语言判断是否确实由原文支持，这部分仍需人工审核。

任一模型阶段调用、解析或校验失败时，后续阶段停止，控制台显示失败阶段和已执行阶段，不返回完整成功草稿。成功结果提供运行 ID、输入 SHA-256、冻结的原始输入、三个角色的结构化输出、阶段起止时间和待审核字段，保存在本次方法返回的内存对象中。**本命令不写本地报告或远程数据库，即使文件兼容模式开启也不写；不修改任务。**审核记录目前只是数据结构和待填写占位，未实现审核提交或持久化接口。

风险和协调的 JSON 转换会逐层检查重复字段：仅当字段名和值相同才合并；值冲突、JSON 截断或对象外额外文字均拒绝，不自动选最后一个值或调用模型修补。来源编号和必需列表继续由业务校验检查。解析错误只报告固定原因，不通过转换器日志回显模型业务全文。修复不会自动增加模型调用次数；当前助手需重启加载新代码，内存知识库仍需重新导入和审核。

一般会发生三次结构化模型生成，消耗 DeepSeek API 额度；RAG 启用且存在已审核决策时还会调用一次向量查询。检索失败在任何角色调用前停止；没有命中时明确标记。自动测试使用模拟服务验证交接、来源错误中止、失败阶段、快照冻结、空任务和数据变化，以及不产生业务文件；测试不能替代真实模型运行评价。后续调用边界见 `INTEGRATION-CONTRACT.md`，无需现在向队友确认接口。

## 验证进度督导工具调用

先在旧助手中 `/exit`，回到 PowerShell 执行 `.\run.ps1 -Rag`（保留知识库功能），或 `.\run.ps1`。本命令本身只需 DeepSeek，不使用向量模型。

```text
/progress examples/tasks.json
```

第一版用本地模拟文件验证工具链：DeepSeek 请求 `getSprintProgress` → Spring AI 执行 Java 方法 → Java 返回统计数据 → DeepSeek 根据结果分析风险及建议。初始模型请求不直接附带任务快照。工具使用 `@Tool` 注册，在本次请求通过 `.tools(...)` 提供，参考 [Spring AI 1.1 工具调用文档](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)。

应看到“工具调用成功”，Java 统计已完成 3/6、完成率 50.00%、延期 `[T-4]`、阻塞 `[T-6]`，以及 AI 分析。T-6 依赖尚未完成的 T-4，存在潜在影响；不能据此断言 T-6 必然延期。终端统计由 Java 计算，AI 的解释仍需核对。若模型未实际调用工具，程序明确报错，不把普通回答当作工具调用成功。

文件格式见 `examples/tasks.json`：`simulation` 必须为 `true`；快照日期和非空截止日期为 `yyyy-MM-dd`；状态仅支持 `TODO`、`IN_PROGRESS`、`BLOCKED`、`DONE`；无截止日期用 `null`；无依赖用 `[]`。ID 必须唯一，依赖引用已有任务，不能自引用或重复。

计算口径是已完成任务数量除以任务总数，保留两位小数；不是工时完成率。未完成且截止日期早于**快照日期**才算延期，当日到期不算延期；空任务集完成率未知。工具列出未完成任务的未完成前置依赖，不进行关键路径或工期预测。不使用机器当前日期替代历史快照日期。

只读取用户指定的一个文件，不给模型选择路径或修改任务的能力，不混入聊天记忆、知识库或其他快照。任务内容及工具计算结果会发送给 DeepSeek。尚未接入真实项目数据，不要把模拟结果记作团队实际进度。程序限制同一次分析的工具调用次数，避免反复请求同一份数据。本地测试验证真实 Spring AI 两轮工具协议及日期、空集、依赖等计算边界，不证明真实模型的风险判断质量。

当前默认分析成功后只显示结果，明确提示尚未持久化。兼容模式启用本地持久化时，保存 `.local/drafts/progress-<唯一编号>.md`，终端显示完整路径。文件包含实际生成时间（UTC）、配置的模型、输入来源和 SHA-256、快照日期、工具调用次数、Java 统计、AI 回答原文、本次完整输入快照及工具结果。多次运行生成不同文件；输入只读取一次，后续更改源文件不会改变已保存的快照。

审核人、审核时间、采纳/修改后采纳/否决和修改理由保留“待填写”，由真实审核者填写。填写审核意见时保留原始回答、快照和工具结果，以便追溯。程序不自动判定采纳，不把报告写入平台任务、聊天记忆或知识库。默认目录与需求拆解共用，可通过 `AI_DRAFT_DIRECTORY` 配置，或用 `app.progress.draft-directory` 单独配置进度报告目录。模型跳过工具、输入无效或模型返回为空时不保存成功报告；文件保存失败会提示错误，已经发生的模型调用可能计费。

不必重做之前的全部验证。更新后重启，再运行 `/progress examples/tasks-updated.json`，统计应仍为 4/6、66.67%、无延期、阻塞 T-6；当前模式应提示“尚未持久化”，不会显示已保存路径，也不会生成报告。之前的终端回答不会自动补存或上传远程数据库。

## 验证聊天解析助手

运行助手后输入：

```text
/analyze examples/discussion.json
```

示例文件明确是模拟聊天，不能记作真实团队沟通。程序读取本地 UTF-8 JSON，通过独立 ChatClient 调用同一个 DeepSeek 模型，以 `.entity(DiscussionAnalysis.class)` 转成结构化结果。解析过程不附带普通聊天的记忆，不修改项目数据。

| 输出字段 | 含义 |
| --- | --- |
| summary | 讨论要点 |
| decisions | 原文明示并确认的决定 |
| suggestions | 尚未确认的提议 |
| actionItems | 待办，含 task、owner、deadline |
| openQuestions | 未解决的问题 |
| sourceMessageIds | 每条内容依据的原始消息 id |

没有明确负责人或期限时应为 null；相对时间保留原文。程序检查输出结构和消息来源 id 是否存在，但这些检查不能证明模型正确理解了原文，仍需人工核对。特别检查示例里的“任务分配通知”仅为建议，以及“多人分配”仍为待确认问题。

真实聊天记录文件采用以下格式，路径含空格时可以加双引号：

```json
[
  {"id":"m1","speaker":"成员甲","content":"聊天内容"},
  {"id":"m2","speaker":"成员乙","content":"另一条聊天内容"}
]
```

`id` 必须唯一，三个字段均不能为空。聊天文件内容会发送给模型进行解析。结果仅显示在终端，尚未实现自动保存、人工确认入库或长期摘要记忆。

## 使用项目 RAG

DeepSeek 继续负责回答。向量模型负责把文档片段和问题转换为向量，Spring AI 的 SimpleVectorStore 默认在运行内存中检索这些向量，检索到的原文再提供给 DeepSeek。第一版面向独立实验，不需要安装 Milvus、Redis 或业务数据库；不是多用户平台的知识库服务，尚未实现项目权限隔离或远程索引。

默认向量模型为硅基流动平台的 `BAAI/bge-m3`，独立使用 `EMBEDDING_API_KEY`。模型名称及请求路径依据 [硅基流动官方 Embeddings 文档](https://docs.siliconflow.cn/docs/api/embeddings-post)。不能用 DeepSeek 的 API Key 代替硅基流动密钥，也没有将聊天模型当作向量模型。

### 第一次启用

1. 按照 [硅基流动官方快速入门](https://docs.siliconflow.cn/docs/userguide/quickstart)，在 [API 密钥页面](https://cloud.siliconflow.cn/account/ak) 创建密钥，并在模型列表确认账号可调用 `BAAI/bge-m3`，额度和计费以平台为准。
2. 在旧助手里输入 `/exit`，回到本项目目录的 PowerShell。
3. 运行以下命令。脚本会在缺少密钥时隐藏询问，密钥仅保存在当前终端的环境变量中。

```powershell
.\run.ps1 -Rag
```

4. 启动后，在助手的“你：”后逐条输入：

```text
/import examples/knowledge.md
/docs
/ask AI 用户故事与任务拆解计划安排在哪个 Sprint？
/ask 当前项目真实完成率是多少？
```

第一问应根据计划回答 Sprint 2 并引用来源；第二问应说明缺少真实进度资料。检索参考片段会完整显示，需人工判断回答是否得到原文支持。此示例是项目计划摘录，不表示实际开发状态。

### 导入你自己的资料

支持 UTF-8 `.md`、`.txt`、有文字层的 `.pdf` 和 `.docx`。扫描 PDF 尚未接 OCR，旧版 `.doc` 不支持。含空格的路径加双引号。以下路径相对于项目目录：

```text
/import "../实验1/13_项目规划方案(1).docx"
/import "../实验指导书_实验3_Sprint2_4学时.docx"
/ask 实验三对 AI 智能体协作有什么要求？
```

导入时，文档原文发送给向量模型；文档问答时，问题发送给向量模型，检索到的片段和问题发送给 DeepSeek。原文件保留在原位置。当前索引只在内存中，退出后清空；重启需重新导入并重新调用向量模型。仅在兼容模式启用本地持久化时，才保存或加载 `.local/knowledge.json`。该索引包含文本及向量，不保存 API Key，已排除 Git 提交。

同一路径、相同内容会跳过重复导入；文件修改后重新 `/import` 会替换旧片段。导入过程中模型调用或保存失败时不替换旧索引，但失败前已发出的向量请求仍可能计费。第一版每个文档以约 400 tokens 切分，无重叠，来源以文件名和片段编号表示，不伪造 PDF 页码或 Word 章节。不同文档结构需要后续评估切分与检索效果。

使用 `/ask` 才执行 RAG；直接输入问题仍走普通对话记忆。`/analyze` 仅解析指定聊天文件。三个入口不混用历史；`/new` 只清空普通聊天记忆，保留文档索引。文档问答暂为单轮，追问时写完整的问题。

### 更换向量接口

兼容 Embeddings 接口可用以下环境变量覆盖。基础地址和请求路径分开配置，最终合并为完整 URL：

```powershell
$env:EMBEDDING_BASE_URL = 'https://api.siliconflow.cn'
$env:EMBEDDING_PATH = '/v1/embeddings'
$env:EMBEDDING_MODEL = 'BAAI/bge-m3'
.\run.ps1 -Rag
```

如需替换已输入的密钥，先执行 `Remove-Item Env:EMBEDDING_API_KEY -ErrorAction SilentlyContinue`，下次启动会重新隐藏询问。更换模型或服务后向量不能混用，程序会检查并阻止加载旧索引。请为新模型设置新文件路径，例如 `$env:RAG_INDEX_FILE = '.local/knowledge-other.json'`，然后重新导入。相似度阈值初始为 0.5、最多检索 4 个片段，可在 application.yml 中调整；阈值不是答案可信度。

首次真实演示建议依次验证一般问答、需求拆解、未知项目进度问题。助手应说明没有实时数据。实际输入、输出和人工评价需要在真实运行时保存到实验记录；不要把模拟测试响应当作模型实际输出。

## 在 IDEA 中打开

打开本目录的 pom.xml，设置 Project SDK 和 Maven Runner JRE 为 JDK 17，Maven 使用 3.6.3 或以上版本。运行 AiAssistantApplication 前在运行配置中填写 DEEPSEEK_API_KEY，其他参数使用默认值即可。不要提交带真实密钥的共享运行配置。

## 代码位置

- AiAssistantApplication.java：启动与必要环境变量检查。
- AssistantService.java：带近期记忆的模型调用、清空与失败回滚。
- DiscussionService.java：独立聊天解析与来源检查。
- DiscussionMessage.java / DiscussionAnalysis.java：聊天输入与结构化输出。
- DecisionReviewService.java：群聊决策候选、人工审核状态与受控入库凭据；审核记录只在内存。
- DecisionKnowledge.java：冻结本轮已审核决策片段、校验采用声明及展示来源。
- DecompositionService.java / RequirementDecomposition.java：需求拆解、关联检查与草稿保存。
- ProgressService.java / ProjectProgressTools.java / ProgressSnapshot.java：模拟快照读取、Java 统计与 Spring AI 工具调用。
- CollaborationService.java / CollaborationInput.java：三角色固定交接、来源校验、运行记录与内存结果。
- CheckedOutputConverter.java：风险、协调的结构化输出解析，相同重复字段合并、冲突拒绝，解析异常不回显全文。
- RagConfiguration.java / RagProperties.java：独立向量模型连接及 RAG 配置。
- KnowledgeService.java：文档解析、分块、向量检索、索引保存恢复和文档问答。
- ConsoleChat.java：控制台交互、/new、/decompose、/progress、/collaborate、/analyze、/import、/ask、/sources、/import-public、/ask-public、/docs、/help、/exit。
- src/main/resources/application.yml：模型参数配置。
- src/main/resources/prompts/system.md：本阶段实际使用的助手规则。
- src/main/resources/prompts/discussion.md：纪要解析规则。
- src/main/resources/prompts/decomposition.md：需求范围、结构化拆解及待确认规则。
- src/main/resources/prompts/knowledge.md：文档问答及引用规则。
- src/main/resources/prompts/progress.md：进度事实、风险推断及建议边界。
- src/main/resources/prompts/collaboration-risk.md / collaboration-coordinator.md：风险与协调角色的交接规则。
- examples/discussion.json：明确标注的模拟聊天。
- examples/knowledge.md：项目计划摘录，供 RAG 验证使用。
- examples/tasks.json：明确标记为模拟的任务快照，供工具调用验证使用。
- examples/collaboration.json：三角色协作用模拟聊天和任务快照。
- examples/decision-discussion.json：群聊决策审核与入库演示输入。
- examples/collaboration-decisions.json：引用已审核决策的模拟协作材料。

## 参考资料

- [Spring AI 1.1 入门与兼容版本](https://docs.spring.io/spring-ai/reference/1.1/getting-started.html)
- [Spring AI ChatClient](https://docs.spring.io/spring-ai/reference/1.1/api/chatclient.html)
- [模型连接与 Chat Completions 路径配置](https://docs.spring.io/spring-ai/reference/1.0/api/chat/openai-chat.html)
- [DeepSeek 官方接入说明](https://api-docs.deepseek.com/)
- [硅基流动向量接口](https://docs.siliconflow.cn/docs/api/embeddings-post)
- [Spring AI 1.1.2 SimpleVectorStore 源码](https://github.com/spring-projects/spring-ai/blob/v1.1.2/spring-ai-vector-store/src/main/java/org/springframework/ai/vectorstore/SimpleVectorStore.java)
