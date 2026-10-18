# 爱管理 AI 模块交接材料

这是独立 AI 原型的内部调用说明，供后续前后端对接时使用。现在无需队友提供数据库或服务器，不需要把本机模型密钥发给队友。

当前没有 HTTP Controller、可调用的 Web 地址、远程保存接口或平台鉴权。下面的 Java 方法是真实存在的服务入口；JSON 是这些入口的参数和返回值示例，不是已经发布的 HTTP 请求或真实模型运行记录。

## 可以交接的能力

| 能力 | 已有 Java 入口 | 输入 | 返回 |
| --- | --- | --- | --- |
| 连续对话 | `AssistantService.ask(conversationId, question)` | 会话标识、问题 | 回答字符串 |
| 清空短期记忆 | `AssistantService.clear(conversationId)` | 会话标识 | 无返回内容 |
| 群聊纪要 | `DiscussionService.analyze(messages)` | 消息对象列表 | `DiscussionAnalysis` |
| 需求拆解 | `DecompositionService.decompose(requirement)` | 需求文本 | `SavedDraft` |
| 资料辅助需求拆解 | `DecompositionService.decomposeWithKnowledge(requirement, knowledge)` | 需求文本、知识服务对象 | `SavedDraft`，含 `grounding` |
| 项目知识问答 | `KnowledgeService.ask(question)` | 问题 | `KnowledgeAnswer` |
| 公共知识问答 | `KnowledgeService.askPublic(question)` | 问题 | `KnowledgeAnswer` |
| 进度与风险建议 | `ProgressService.analyze(file)` | 模拟快照文件路径 | `Analysis`，含 Java 统计和模型回答 |
| 三角色协作 | `CollaborationService.collaborate(file, knowledge)` | 模拟协作文件路径、知识服务或 null | `Run`，含各角色结果与阶段记录 |
| 提取候选决策 | `DecisionReviewService.extract(file)` | 带 simulation 和 messages 的文件路径 | `Extraction` |
| 查看候选 | `DecisionReviewService.get(id)` / `list()` | 决策 ID 或无参数 | `Candidate` / 候选列表 |
| 审核候选 | `DecisionReviewService.review(id, action, reviewer, reason, modifiedText)` | 决策 ID、处理动作、审核人、理由、可选改稿 | `Candidate` |
| 发布已审核决策 | `DecisionReviewService.publish(id, knowledge)` | 决策 ID、知识服务 | `Publication` |

方法里的 `knowledge` 是 Java 服务对象，不是客户端能提交的 JSON。文件路径入口也不是让浏览器传服务器任意路径；后续应由后端取得经过授权的业务数据，再增加业务对象入口。当前不要猜测平台字段或让 AI 选择查询路径。

可查看 [examples.json](examples.json)。其中包含聊天、纪要、需求、空库问答、进度、协作、候选提取和审核八类示例；每一项标明实际 Java 方法、返回类型和限制。示例中的姓名、编号、时间和模型内容均为人工构造，不表示实际调用或审批。

2026-10-18 已在离线核对中将全部 9 个示例结果反序列化为实际 Java 返回类型，并对进度/协作示例调用现有 Java 统计核对数量、比例、延期、阻塞与依赖；同时核对原始输入的 SHA-256。未启动助手、调用模型或导出运行内存。这些检查证明示例结构与当前代码一致，不表示 HTTP 接口已经实现，也不证明模型语义质量。

## 前端以后如何使用结果

- 回答字符串作为聊天回复展示；会话标识由后端分配并校验访问权限，不能只相信浏览器提交的任意 ID。
- `DiscussionAnalysis` 展示讨论要点、候选决定、建议、行动项和待确认问题；点击 `sourceMessageIds` 以后可定位平台原消息，当前只是来源编号。
- `SavedDraft.result` 展示用户故事、验收标准、开发任务和待确认问题。`file=null` 是默认不在本地保存的正常返回，不是生成失败。
- `KnowledgeAnswer.answer` 展示回答，`sources` 展示证据；空 sources 可能表示没有资料或没有命中，不能补出引用。决策来源在 `sources[].decision` 中，不能把模拟标记隐藏。
- `Analysis.report` 的数字由 Java 计算，`answer` 是 AI 草稿，二者分开呈现；完成率 null 表示没有任务而无法计算，不显示成 0%。
- `Run` 展示 `facts`、`minutes`、`risks`、`coordination`、`decisions`、`trace`。草稿状态和“未执行”说明必须保留；完整草稿生成不等于已采纳或已修改平台任务。
- `Candidate.status` 表示具体候选的审核/入库状态。候选决策已审核不代表整份纪要、风险报告或协调建议都已审核。

## 审核与保存的职责

当前默认结果和索引只在 Java 进程内存中，退出即清空。AI 模型服务和向量模型服务不是项目数据库。

| 数据 | 当前行为 | 后续平台需要承担的职责 |
| --- | --- | --- |
| 群聊消息、任务和 Sprint | 独立原型读取模拟材料，没有真实数据库连接 | 鉴权后查询真实记录，提供项目范围和统计时区 |
| 普通对话记忆 | 最近 20 条消息，包含用户与助手，退出清空 | 决定会话生命周期与持久化策略，验证会话归属 |
| 纪要、需求和分析草稿 | 返回对象或终端展示，不默认落磁盘 | 按项目保存草稿、来源、生成时间及采纳情况 |
| 决策审核记录 | 本机操作者自报姓名，尚未验证平台身份 | 用已登录用户身份、角色和项目权限审核，持久化状态转换 |
| 知识正文及向量 | 当前进程内存，尚无跨项目隔离 | 选择远程存储和项目隔离方式，保证授权过滤与更新一致性 |
| 创建任务、变更负责人或日期 | 不执行 | 后端校验权限与明确确认后调用业务接口，记录实际操作结果 |

决策状态：`PENDING_REVIEW → APPROVED → PUBLISHED`，或者 `PENDING_REVIEW → REJECTED`。这里 PUBLISHED 只表示进入当前进程的项目检索索引，不表示保存到队友数据库。向量化失败时保持 APPROVED，可以在当前进程重试 publish；未审核和否决条目不能发布。

审核动作是 `ACCEPT`、`MODIFY`、`REJECT`。MODIFY 必须提供完整且与原稿不同的文字；其他动作不应带改稿。审核理由必填，平台接入时 reviewer 应来自已认证身份，不能从 AI 输出或不可信请求正文取姓名。普通需求与协作报告目前只有待审核标记，没有实现同样的审核提交能力。

examples.json 中的 APPROVED 候选仅展示返回形状；不能把客户端提交的这段 JSON 当作审核凭据。当前入库必须通过审核服务的状态转换，再调用 publish，由服务内部创建受控凭据。

## 知识使用边界

1. 项目文档问答和公共知识问答分开检索。
2. 普通需求拆解检索公共方法及非模拟的已审核决策；不会把普通项目文件、未审核候选或模拟决策自动加入该次决策检索。
3. 当前协作输入必须明确标为模拟。启用 RAG 时可检索模拟决策，但不能把它变为真实项目规则。
4. 风险与协调收到同一份冻结决策片段；公共方法引用与项目决策引用不是同一类依据。
5. 当前是单项目进程，尚无平台多项目授权。仅传入 projectId 并不自动实现隔离，后续需在查询和存储层落实。

## 失败处理

当前方法抛出 Java 异常，没有统一 HTTP 状态码或错误 JSON。后续适配层再定义映射，不要把下面的分类理解为已上线接口。

| 当前信号 | 含义 | 接入时如何处理 |
| --- | --- | --- |
| `IllegalArgumentException` | 某个输入或调用状态不符合要求，也可能来自供应商客户端 | 根据发生阶段分类，不能一律断言为用户输入错误；不要直接回显原异常正文 |
| `IOException` | 文件、解析或兼容持久化发生错误 | 显示失败，保留已有数据，不标记已保存 |
| `IllegalStateException` | 返回为空、来源无效、必要列表缺失或状态转换不允许等 | 不采纳本次结果，展示可理解的说明 |
| `ProgressService.ToolNotCalledException` | 模型没有调用要求的进度工具 | 不将普通回答当作工具分析成功 |
| `CollaborationFailedException` | 协作某阶段失败 | 使用 `stage` 与 `trace` 展示实际进度，不展示完整成功结果 |
| `CheckedOutputConverter.InvalidOutputException` 作为协作异常原因 | 风险或协调 JSON 无效，或重复字段值冲突 | 使用固定格式错误说明，不回显模型全文；不自动扩大重试次数 |
| 其他供应商/网络 `RuntimeException` | 调用失败，可能已产生请求费用 | 不标记已生成或已保存；避免把供应商正文和密钥发往页面 |

相同名且相同值的 JSON 字段可合并，冲突值不会按最后一个值覆盖；来源编号还需通过独立业务校验。字段和编号格式正确不保证内容语义正确，真实模型输出仍需人工核对。

## 对接前仍需团队确定

- 同进程引入 AI 模块还是独立服务，以及实际调用地址、部署与超时策略。
- 平台项目、群组、消息、任务、Sprint 的字段、ID、任务状态及截止日期口径。
- 登录身份如何传递、谁能查看资料和提交审核、项目如何隔离。
- 草稿、审核记录、文档与向量由哪些远程接口保存，失败和重复提交如何处理。
- 消息触发方式、回复推送方式，以及用户取消或超时时页面如何显示。

这些是后续对接事项，不要求现在联系队友，也不代表已获知他们的实现。完整内部结构补充见 [INTEGRATION-CONTRACT.md](../INTEGRATION-CONTRACT.md)。
