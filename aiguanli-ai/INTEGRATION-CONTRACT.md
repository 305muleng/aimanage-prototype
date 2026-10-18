# AI 模块调用边界与结果结构

这是当前独立原型的内部服务约定，供以后对接使用；不是已与队友确认的 HTTP 协议。目前没有启动 Web 服务、远程保存、数据库访问或基于平台身份的正式审核接口。群聊决策已有终端审核操作，身份仍是本机自报姓名。

全部内部入口、前端展示字段、存储职责和失败处理汇总见 [handoff/README.md](handoff/README.md)，人工构造的输入与返回示例见 [handoff/examples.json](handoff/examples.json)。9 个示例已核对实际 Java 返回类型和快照统计，不是用户运行记录或已发布 HTTP 协议。

## 三角色协作入口

当前控制台调用 `CollaborationService.collaborate(Path, KnowledgeService)`；RAG 未启用时第二参数为 null，原 `collaborate(Path)` 入口保留。输入为一份 UTF-8 JSON，只读取一次并冻结。启用 RAG 时先检索已审核决策，后续风险、协调角色共享同一份片段。模型无法选择文件路径或再次读取其他文件。完整示例见 `examples/collaboration.json`，决策检索演示见 `examples/collaboration-decisions.json`。

| 字段 | 类型 | 当前要求 |
| --- | --- | --- |
| simulation | boolean | 必须为 true |
| messages | array | 至少一条，每条有唯一 id、非空 speaker、content |
| progress.simulation | boolean | 必须为 true |
| progress.snapshotDate | string | yyyy-MM-dd，统计以该日期为准 |
| progress.sprintName | string | 非空 |
| progress.tasks | array | 可以为空；空任务完成率为 null |
| tasks[].id / title / status | string | ID 唯一；状态为 TODO、IN_PROGRESS、BLOCKED、DONE |
| tasks[].dueDate | string / null | yyyy-MM-dd，或 null |
| tasks[].dependencyIds | array | 引用本快照存在的其他任务，不能重复或自引用 |

当前不接受未标记的真实数据。正式接平台前，需要明确实际数据来源、状态映射、项目隔离与权限验证，届时再实现接收业务对象的适配入口，不预先猜测队友接口。

## 成功结果

返回 `CollaborationService.Run`，不触发存储。主要字段如下：

| 字段 | 内容 |
| --- | --- |
| id / createdAt | 程序生成的运行 UUID、UTC 时间 |
| status | PENDING_REVIEW，表示结果待审核 |
| simulation | true，不能标成真实项目状态 |
| configuredModel | 本次配置的模型标识 |
| source / inputSha256 / originalInput | 输入文件名、原始字节 SHA-256、冻结的原始 JSON |
| input | 解析后的模拟输入 |
| facts | Java 计算的 ProgressReport；不靠模型重算完成率 |
| minutes | 纪要角色输出，保留 sourceMessageIds |
| risks | 风险角色输出，保留风险 ID 和消息、任务、整体快照依据 |
| coordination | 协调角色输出，建议可引用风险 ID 或原始依据 |
| trace | 实际执行阶段、输入阶段 ID、COMPLETED/FAILED、起止 UTC 时间 |
| review | 初始 PENDING，审核人、审核时间、结论和理由均为 null |
| preview | 控制台可读 Markdown 草稿，可由未来页面展示 |
| decisions | 本次决策检索 status、query 和冻结的 sources；DISABLED / NO_MATCH / RETRIEVED |

`risks.risks[]` 包含 id、observation、potentialImpact、sourceMessageIds、sourceTaskIds、referencesSnapshot。风险仍是模型候选判断。

`coordination.recommendations[]` 包含 title、rationale、riskIds、sourceMessageIds、sourceTaskIds、referencesSnapshot。所有建议均为待团队确认、未执行。`referencesSnapshot=true` 表示依据整体快照，例如快照未提供工时；不代表查询过实时系统。

编号校验只能确认引用对象存在，不能证明自然语言结论正确。审核时需要同时查看原始输入和各阶段输出，不能把上游模型文本当成新事实。

`risks.projectDecisions` 与 `coordination.projectDecisions` 包含 references 和 note；references 每项有 explanation 与 decisionIds，只能引用本次 `decisions.sources[].decision.decisionId`。无检索材料且模型未返回此对象时可以为 null；命中后缺少对象、说明或引用了不存在的编号会终止该阶段。模型可以声明未采用并说明理由，不把“传给模型”当成“已被采用”。检索阶段名为 decisions，失败时 stage=decisions，尚未调用三个角色。

`KnowledgeService.retrieveReviewedDecisions(query, allowSimulation)` 只检索已审核入库来源，过滤在 topK 之前执行。`DecompositionService.decomposeWithKnowledge` 对该参数固定 false；模拟协作固定 true，仍保留来源的 simulation 标记。需求返回的 `grounding.decisions` 与 `grounding.decisionUsage` 分别记录冻结的检索结果与模型采用说明。所有内容仍在当前单项目进程中，尚无多项目权限隔离。

## 失败结果

- 输入文件、JSON 或模拟数据格式错误：在角色调用前抛出 IOException 或 IllegalArgumentException；不进入模型流程。
- 纪要、风险或协调阶段失败：抛出 CollaborationFailedException，stage 标明失败阶段，trace 保留已执行阶段和 FAILED 状态；后续阶段不执行，不返回成功 Run。
- 控制台不打印供应商原始错误响应。没有生成成功结果时，不把部分输出标为已完成或已保存。

风险、协调输出采用 CheckedOutputConverter：相同名称与值的重复字段逐层合并；冲突重复值、截断 JSON、多对象或额外文本抛出 InvalidOutputException，外层仍包装为 CollaborationFailedException 并保留失败阶段。格式错误不挂载含模型正文的原始解析异常，也不由转换器打印正文。该处理不补写缺失业务字段、不代替来源校验，不自动增加模型重试。

## 以后接入远程平台

未来由后端提供经过鉴权的输入与项目上下文，并负责统一存储和审核；本原型没有这些能力。数据库无需安装在 AI 开发者本机。

协作报告的审核数据结构仍为占位；群聊决策已有审核状态转换。正式接入时，审核者身份应由后端验证，审核时间由程序生成，不能相信模型自行填入的身份。当前不把审核结论写回数据库，也不执行任务创建、分配、排期修改或消息发送。

## 群聊决策审核入库入口

`DecisionReviewService.extract(Path)` 接收 `{ "simulation": true, "messages": [...] }`。提取只使用 DiscussionAnalysis.decisions 创建待审核条目；输出 `Extraction`，包含 Candidate 列表和 reused 标记。原数组聊天解析入口保持兼容，但不会自动进入审核入库流程。

Candidate 包含程序生成的 id、originalText、approvedText、status、simulation、sourceFile、inputSha256、originalInput、createdAt、sourceMessages、review、publishedAt。review 包含 action（ACCEPT/MODIFY/REJECT）、reviewer、reason、reviewedAt。状态只由程序转换：

```text
PENDING_REVIEW → APPROVED → PUBLISHED
             ↘ REJECTED
```

`review(id, action, reviewer, reason, modifiedText)` 只允许处理待审核条目；不允许覆盖已有审核记录。`publish(id, knowledge)` 只允许 APPROVED/PUBLISHED 状态，构造外部不能直接创建的审核凭据后交给知识服务。失败保持 APPROVED，可重试；重复发布幂等。服务方法本身不验证 reviewer 的平台身份，真实接口必须绑定经过认证的用户并校验其权限，不能直接相信请求体里的姓名。

只有采纳文本进入检索正文。原始消息和 AI 原稿保留为审计材料，修改后采纳不会把被修改的旧文再次传给问答模型。已审核来源概要会附在 `SourceFile.decision` 和 `SourceSnippet.decision`，包括决策编号、模拟标记、审核信息、消息编号和原始输入指纹。simulation=true 的内容仍为模拟材料，审核不能将其升级为真实项目事实。

默认候选、审核与向量索引全部在内存，尚无远程保存。旧文件兼容模式中的索引证据不等同于正式后端审核记录；跨重启、撤回、决策替代和多项目隔离需要后续后端适配。

其他已完成的服务入口保留：DiscussionService.analyze、DecompositionService.decompose / decomposeWithKnowledge、KnowledgeService.ask / askPublic、ProgressService.analyze。它们的接口适配可以在队友后端准备好后实现，当前不需要为猜测的接口创建数据库连接。
