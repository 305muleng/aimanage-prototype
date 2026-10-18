# 爱管理 AI 前端后端交接说明

日期：2026 年 10 月 18 日

交接对象：负责爱管理 Web 后端及 AI 服务对接的开发同学。

## 1 交接结论

AI 前端已完成页面、交互和 HTTP 调用层。后端需要提供本文约定的四类接口，将登录身份、项目数据和现有 AI 内部服务接起来。页面没有演示数据，不会在浏览器中伪造回答、审核或入库成功。

本文路径和 JSON 是**当前前端使用的对接约定，不代表这些后端接口已经实现或完成联调**。JSON 示例仅说明数据结构，不是真实项目记录。

当前代码位于 `aimanage-ai` 项目，前端交付分支为 `feat/ai-collaboration-ui`，基于 `ai/assistant-module-20261018` 开发，PR 目标也是该 AI 分支，而不是 `main`。PR 合并前，后端对接时应检出前端交付分支或 PR 对应代码；只下载原 AI 分支，不会自动包含本次前端。

### 1.1 本轮范围

- 沿用 Spring Boot、Thymeleaf、原生 JavaScript 和现有网站样式。
- 页面入口为左侧“AI 协作”，现有地址为 `/preview/ai`。沿用原页面路由名称，但页面已按接口驱动的业务页面实现。
- 不修改 Sprint、Product Backlog、任务创建与分配等其他业务模块。
- 不新增多人实时聊天、群组管理、文件上传、模型配置、报告审批或自动执行任务功能。

## 2 前端各模块的功能

| 模块 | 用户可以做什么 | 后端提供什么 |
| --- | --- | --- |
| 项目讨论 | 查看当前项目的讨论消息、发送者和已有时间信息 | 当前用户有权查看的消息列表、项目名称、会话编号 |
| 向 AI 提问 | 输入问题，点击“询问 AI”，等待回复；失败保留输入并重试 | 根据会话和问题生成回答，按需要返回结构化结果 |
| 回答 | 查看回答正文及其引用 | `answer` 和 `sources` |
| 文档引用 | 查看来源名称、摘录；有有效链接时打开来源 | `SourceSnippet` 列表；没有引用时返回空数组 |
| 纪要 | 查看讨论要点、候选决定、建议、行动项、待确认问题和来源编号 | `DiscussionAnalysis` 对应结构 |
| 需求草稿 | 查看用户故事、验收标准、开发任务、待确认问题及草稿引用 | `SavedDraft.result` 和 `grounding.sources` |
| 风险建议 | 查看风险、影响、信息缺口、协调建议和依据编号 | `risks`、`coordination` |
| 候选决策 | 查看原稿、原消息依据、当前状态、审核人和理由 | 有稳定编号的 `Candidate` 列表 |
| 决策审核 | 项目负责人采纳、修改后采纳或否决；三种操作都填写理由 | 校验权限与状态，保存审核记录，返回更新后的决策 |
| 决策入库 | 对已采纳的决策单独确认入库；失败后可重试 | 发布已采纳文本，返回真实入库结果 |

“回答、文档引用、纪要、需求草稿、风险建议”是五个展示标签，**点击标签不发送新请求**，不需要为每个标签另建接口。

只有一个“询问 AI”入口，没有 `@` 识别、功能选择器和“生成纪要”等独立按钮。提问请求也不发送功能类型；后端需要确定如何根据问题调用对应服务，或者返回已保存的分析结果。不能期待前端提交一个不存在的 `type` 参数。

讨论区目前是消息展示，不是完整的多人聊天系统；输入框提交的是 AI 提问，不是向全体成员广播普通消息。来源消息编号目前只展示，不支持点击定位原消息。

## 3 统一接口约定

### 3.1 接口清单

| 方法 | 当前默认路径 | 用途 | 请求体 | 成功返回 |
| --- | --- | --- | --- | --- |
| GET | `/api/ai/workspace` | 加载当前项目会话、历史消息、结果和权限；异常后刷新状态 | 无 | 工作区对象 |
| POST | `/api/ai/questions` | 提问并获取回答，可附带结构化结果 | `conversationId`、`question` | 回答结果对象 |
| POST | `/api/ai/decisions/{id}/review` | 审核候选决策 | `action`、`reason`、`modifiedText` | 更新后的 `Candidate` |
| POST | `/api/ai/decisions/{id}/publish` | 发布已采纳决策 | `{}` | `Publication`，其中包含 `decision` |

决策编号放在 URL 中，前端会进行 URL 编码。请求体不重复提交决策编号、审核人、审核时间或目标状态。

### 3.2 传输与响应

- 仅允许同源请求；AI 若部署在独立服务，由 Web 后端或同源网关转发，不在浏览器直连模型服务。
- 请求头包含 `Accept: application/json`；POST 另有 `Content-Type: application/json`。
- 使用现有登录会话 Cookie，前端请求为 `credentials: same-origin`，不是 Bearer Token 方案。
- 请求禁用浏览器缓存，每次请求超时为 **30 秒**。
- 当前按普通 JSON 响应处理，不支持流式文本、SSE、WebSocket 或异步任务轮询。
- 建议成功返回 HTTP 200，并直接返回本文 JSON 对象。不能返回 204、登录 HTML、纯字符串或只包含“操作成功”的消息。
- 前端当前**不自动解包** `{ "code": 0, "data": ... }`。如果项目坚持使用统一响应包装，需要在前端调用层统一解包后再联调。
- 生成内容按纯文本显示，不将模型返回的 HTML 或 Markdown 转换为富文本。

### 3.3 登录与项目上下文

`workspace` 请求目前没有 `projectId`、群组编号或分页参数。后端必须明确“当前项目”的来源：可以复用平台已有的选中项目上下文；如果平台没有这个机制，应在联调前确定项目选择方式，再同步增加必要参数。**不能随意选择数据库第一条项目，也不能仅凭前端提交的 ID 判定有权访问。**

`conversationId` 由后端提供，绑定真实用户访问权限及项目范围。页面不会自行创建正式会话编号。首次访问需要的会话初始化由后端安排；工作区正常加载时不能返回空会话编号。

后端每次都要校验会话、决策、消息和引用来源的项目归属，防止改编号访问其他项目。登录失效请对 API 返回 401，不要重定向为 200 的登录页 HTML。

## 4 加载工作区

### 4.1 GET /api/ai/workspace

最小可用响应如下。即使没有消息和结果，也要返回可访问的真实会话编号及明确权限。

```json
{
  "projectName": "项目名称",
  "conversationId": "conversation-101",
  "permissions": {
    "reviewDecisions": true
  },
  "messages": [],
  "answer": null,
  "minutes": null,
  "requirements": null,
  "risks": null,
  "coordination": null,
  "candidates": []
}
```

| 字段 | 类型 | 约束与用途 |
| --- | --- | --- |
| `conversationId` | string | 必须是非空字符串，不能用数字类型代替 |
| `permissions.reviewDecisions` | boolean | 必填；`true`/`false` 不能写成字符串 |
| `messages` | array | 必填，无消息用 `[]` |
| `candidates` | array | 必填，无候选用 `[]`；只返回当前授权范围内的决策 |
| `projectName` | string | 建议提供，用于显示项目名称；省略时显示“当前项目” |
| `answer` | object 或 null | 已有回答，结构为 `{answer, sources}`；无结果用 null |
| `minutes` | object 或 null | 纪要结构，见第 8 节 |
| `requirements` | object 或 null | 需求草稿结构，见第 8 节 |
| `risks` | object 或 null | 风险评估结构，见第 8 节 |
| `coordination` | object 或 null | 协调建议结构，见第 8 节 |

前四项不满足时，页面会判定工作区加载失败，不能只返回 HTTP 200 而缺少这些字段。

### 4.2 消息结构

```json
{
  "id": "message-101",
  "speaker": "成员姓名",
  "content": "请先确认任务字段，再继续实现。",
  "createdAt": "2026-10-18T03:00:00Z",
  "assistant": false
}
```

`id` 用于稳定标识消息，`speaker`、`content` 用于展示。已有时间建议返回带时区的 ISO 8601 字符串，浏览器按本地时区显示；没有时间可以省略，不要编造时间。AI 历史回复将 `assistant` 设为 true，以使用 AI 消息样式。

页面只加载后端返回的列表，没有分页、“加载更多”或自动接收新消息。后端需要按时间先后返回适量、连续的上下文；如果需要分页或实时更新，属于后续另行确认的功能。

### 4.3 实际调用行为

进入页面先请求工作区。输入框在工作区加载失败后仍可编辑，输入非空问题后按钮可点击。此时点击会重新加载工作区：加载成功才继续 POST 提问；仍失败则保留原输入、显示错误，不构造虚假会话或回复。

刷新状态也复用这个 GET。审核或入库出现结果不确定的情况时，它必须能够返回最新服务端状态。

## 5 提问与结果更新

### 5.1 POST /api/ai/questions

请求体：

```json
{
  "conversationId": "conversation-101",
  "question": "请整理当前项目的讨论要点。"
}
```

`question` 去除首尾空白后不能为空；前端输入上限为 4000 字符，后端也应校验长度并统一口径。客户端不会传用户姓名、项目原始消息全文、任务快照、Java 对象或服务器文件路径。后端根据已授权会话查询所需资料。

普通回答响应：

```json
{
  "answer": "请先确认任务字段，再继续实现。",
  "sources": []
}
```

`answer` 必须为字符串，建议非空；`sources` 必须是数组，没有引用仍要返回 `[]`。不能直接返回 `AssistantService.ask` 的字符串，需要包装成上述对象。

如本次确实生成了其他结果，可以在同一个响应中增加以下字段：

| 可选字段 | 对页面的影响 |
| --- | --- |
| `minutes` | 更新纪要标签 |
| `requirements` | 更新需求草稿标签 |
| `risks`、`coordination` | 更新风险建议标签 |
| `candidates` | 替换候选决策列表 |

字段省略时保留旧结果；上述分析对象显式返回 null 时清除该类旧结果。`candidates` 返回的是当前会话授权范围内的**完整候选列表**，不是新增的一条；返回 `[]` 会清空列表。不要将内部 `Extraction.decisions` 直接当作顶层字段，需映射为 `candidates`。

### 5.2 保存与重试

前端会立即将本次问题、成功回复加入当前页面；这些浏览器中的临时消息编号不是数据库主键，也不会提交给后端。要在刷新后看到历史，后端必须保存提问和回复，并在工作区接口返回它们。

生成失败时前端保留输入，用户可以手动重试；没有自动重试模型调用的循环。当前请求没有幂等键，相同文本也可能是用户再次主动提问，不能简单按文本去重并声称实现了严格幂等。如需严格防止重复计费，应先补充双方认可的请求标识约定。

如果模型请求无法在 30 秒内完成，应先协商超时或异步方案；直接改成 202 加任务编号不能与当前页面无修改对接。浏览器超时也不等于后端已经取消模型调用。

## 6 候选决策与审核

### 6.1 Candidate 结构

以下是修改后采纳的结构示例。工作区、提问结果的 `candidates`、审核结果和发布结果里的 `decision` 使用同一种对象。

```json
{
  "id": "decision-101",
  "originalText": "先实现任务创建。",
  "approvedText": "先确认任务字段，再实现任务创建。",
  "status": "APPROVED",
  "simulation": false,
  "sourceMessages": [
    {
      "id": "message-101",
      "speaker": "成员姓名",
      "content": "请先确认任务字段，再继续实现。"
    }
  ],
  "createdAt": "2026-10-18T03:01:00Z",
  "review": {
    "action": "MODIFY",
    "reviewer": "负责人姓名",
    "reason": "根据原讨论补充字段确认要求。",
    "reviewedAt": "2026-10-18T03:05:00Z"
  },
  "publishedAt": null
}
```

| 字段 | 要求 |
| --- | --- |
| `id` | 稳定的字符串编号，刷新后不能为同一条记录重新生成编号 |
| `originalText` | AI 原稿，审核后保留，不被修改稿覆盖 |
| `approvedText` | 待审核、已否决为 null；采纳为原稿；修改后采纳为完整改稿 |
| `status` | 只能使用下表四个英文枚举值，不返回中文或其他拼写 |
| `simulation` | 按真实来源返回；为 true 时前端显示“模拟材料”，审核不能将其升级为真实事实 |
| `sourceMessages` | 原消息依据数组；无依据用 `[]`，不要填充虚构来源 |
| `review` | 未审核为 null；审核后包含动作、真实审核人、理由和服务端时间 |
| `createdAt`、`publishedAt` | 建议保留，用于服务端审计和状态恢复；未发布时 `publishedAt` 为 null |

内部 Candidate 的 `sourceFile`、`inputSha256`、`originalInput` 等额外字段不是页面必需字段。完整原始输入可留在服务端审计，不必向页面暴露服务器路径或整份未经范围过滤的原始 JSON。

### 6.2 状态与按钮

| status | 页面文字 | 有审核权限时的操作 |
| --- | --- | --- |
| `PENDING_REVIEW` | 待审核 | 采纳、修改后采纳、否决 |
| `APPROVED` | 已采纳 | 入库；失败后重试入库 |
| `REJECTED` | 已否决 | 不再提供审核、入库按钮 |
| `PUBLISHED` | 已入库 | 不再提供审核、入库按钮 |

状态流转：

```text
PENDING_REVIEW -- ACCEPT 或 MODIFY --> APPROVED -- publish --> PUBLISHED
PENDING_REVIEW -- REJECT -----------> REJECTED
```

没有候选记录时不显示审核按钮。开发人员可查看授权内容、向 AI 提问，但不能审核或入库。当前按钮显示同时受页面登录角色“项目负责人”和 `permissions.reviewDecisions === true` 约束；前端隐藏按钮不代替后端鉴权。

### 6.3 POST /api/ai/decisions/{id}/review

采纳请求：

```json
{
  "action": "ACCEPT",
  "reason": "已核对原讨论，决定采纳。",
  "modifiedText": null
}
```

修改后采纳请求：

```json
{
  "action": "MODIFY",
  "reason": "补充原讨论中的字段确认要求。",
  "modifiedText": "先确认任务字段，再实现任务创建。"
}
```

否决请求：

```json
{
  "action": "REJECT",
  "reason": "现有讨论尚未形成一致意见。",
  "modifiedText": null
}
```

后端要求：

1. 核验当前用户是该项目有权限的负责人，再读取和更新决策。
2. 只允许审核 `PENDING_REVIEW`；重复审核或过期状态返回 409，不覆盖原审核记录。
3. 三种动作的 `reason` 均不能为空。
4. MODIFY 要求完整、非空且不同于原稿的 `modifiedText`；其余动作不接受改稿。
5. 现有 AI 审核服务还有额外内容校验：采纳文本不能只有数字、编号或标点，应包含实际文字。前端尚未完成这一额外校验，后端仍必须执行。
6. 审核人和审核时间来自登录身份与服务端，不能让模型或浏览器自行指定。
7. 成功直接返回完整 Candidate。ACCEPT/MODIFY 必须返回 `APPROVED`，REJECT 必须返回 `REJECTED`，且 `id` 与请求编号一致。
8. 审核接口不自动入库，不返回 `PUBLISHED`，也不自动创建平台任务或变更排期。

请求中的理由和改稿目前没有单独设置最大长度。后端如需长度上限，应明确数值后同步前端，而不是静默截断审核依据。

## 7 发布已采纳决策

### 7.1 POST /api/ai/decisions/{id}/publish

请求体：

```json
{}
```

发布成功返回 Publication。下例中的 `decision` 应与审核接口使用同一对象结构；其中状态和发布时间已更新。

```json
{
  "decision": {
    "id": "decision-101",
    "originalText": "先实现任务创建。",
    "approvedText": "先确认任务字段，再实现任务创建。",
    "status": "PUBLISHED",
    "simulation": false,
    "sourceMessages": [
      {
        "id": "message-101",
        "speaker": "成员姓名",
        "content": "请先确认任务字段，再继续实现。"
      }
    ],
    "createdAt": "2026-10-18T03:01:00Z",
    "review": {
      "action": "MODIFY",
      "reviewer": "负责人姓名",
      "reason": "根据原讨论补充字段确认要求。",
      "reviewedAt": "2026-10-18T03:05:00Z"
    },
    "publishedAt": "2026-10-18T03:06:00Z"
  },
  "imported": {
    "name": "decision-101",
    "chunks": 1,
    "unchanged": false
  }
}
```

前端判断成功的必要条件是 `decision.id` 匹配、`decision.status === "PUBLISHED"`；`imported` 可保留现有 AI 服务的发布摘要，但页面目前不展示它。

后端要求：

- 仅负责人可操作；只允许发布 APPROVED，已发布记录可按幂等方式返回原结果。
- 以服务端保存的 `approvedText` 为准，不接受浏览器另传一份“已批准”正文或凭据。
- 入库失败保持 APPROVED，不写入虚假的发布时间；支持在核对状态后重试。
- 重复发布不产生重复知识条目、不覆盖原审核记录或首次发布时间。
- 发布成功后，工作区接口应能查到同一条记录的 PUBLISHED 状态。

现有 AI 模块的 PUBLISHED 只表示进入当前进程的检索索引，默认内存数据重启会丢失。正式平台如果要将页面“已入库”作为可长期使用的状态，后端须同时落实审核记录和知识存储；不能只把内存索引成功包装成已完成数据库持久化。

## 8 各类结果的数据结构

以下对象用于工作区响应或提问响应的对应字段。列表没有内容时用 `[]`；整类结果尚未生成时用 null 或在提问响应中省略。

### 8.1 文档引用 sources

```json
[
  {
    "number": 1,
    "source": "项目约定",
    "chunk": 0,
    "text": "任务创建前需要确认字段。",
    "scope": "PROJECT",
    "url": null,
    "decision": null
  }
]
```

前端主要展示 `number`、`source`、`text`、`url` 和可选的 `decision`。`url` 仅有效的绝对 HTTP/HTTPS 地址会形成链接；没有有效地址只展示来源。不要返回浏览器无法访问的服务器本地文件路径。

若引用已审核决策，`decision` 可按现有 `DecisionProvenance` 返回，其中页面使用 `decisionId`、`simulation`、`reviewer`、`reason`。已有的 `score`、`publisher`、`version` 等额外字段可以保留，但不会自动增加页面展示项。引用必须来自当前用户有权访问的资料，空库或未命中不能伪造来源。

### 8.2 纪要 minutes

```json
{
  "summary": [
    { "text": "团队讨论了任务创建前的字段确认。", "sourceMessageIds": ["message-101"] }
  ],
  "decisions": [
    { "text": "先确认任务字段，再实现任务创建。", "sourceMessageIds": ["message-101"] }
  ],
  "suggestions": [],
  "actionItems": [
    {
      "task": "整理任务字段",
      "owner": "成员姓名",
      "deadline": "待确认",
      "sourceMessageIds": ["message-101"]
    }
  ],
  "openQuestions": []
}
```

`summary`、`decisions`、`suggestions`、`openQuestions` 使用 `{text, sourceMessageIds}`。`actionItems` 使用 `{task, owner, deadline, sourceMessageIds}`。不要擅自把“明天”等原文日期换算成没有依据的绝对截止时间。

纪要中的 `decisions` 是提取出的文本，不等于页面可审核的 Candidate。后端需要为需要审核的决策生成并保存稳定编号，再通过 `candidates` 返回。

### 8.3 需求草稿 requirements

```json
{
  "result": {
    "userStories": [
      {
        "id": "US-1",
        "title": "创建任务",
        "role": "项目负责人",
        "goal": "创建任务并指定负责人",
        "benefit": "明确工作安排",
        "acceptanceCriteria": ["提交后可查看任务和负责人。"]
      }
    ],
    "developmentTasks": [
      {
        "id": "TASK-1",
        "title": "实现任务创建",
        "description": "字段以团队确认结果为准。",
        "storyIds": ["US-1"]
      }
    ],
    "openQuestions": ["哪些字段必填？"]
  },
  "grounding": {
    "sources": []
  }
}
```

保留 `result` 这一层，不能直接把 `userStories` 放在 `requirements` 顶层。`grounding.sources` 使用上一节的引用结构。已有 SavedDraft 的 `file`、`preview`、检索状态等可以保留，但页面不读取本地文件路径，不表示已经将故事或任务写进 Product Backlog。

### 8.4 风险 risks 和协调 coordination

`risks` 字段：

```json
{
  "risks": [
    {
      "id": "R-1",
      "observation": "前置任务尚未完成。",
      "potentialImpact": "可能影响后续任务开始。",
      "sourceMessageIds": ["message-101"],
      "sourceTaskIds": ["task-101"],
      "referencesSnapshot": true
    }
  ],
  "informationGaps": [
    {
      "text": "尚未提供预计完成时间。",
      "sourceMessageIds": [],
      "sourceTaskIds": ["task-101"],
      "referencesSnapshot": true
    }
  ]
}
```

`coordination` 字段：

```json
{
  "recommendations": [
    {
      "title": "核实前置任务进度",
      "rationale": "先补充预计完成时间，再由团队判断是否调整计划。",
      "riskIds": ["R-1"],
      "sourceMessageIds": ["message-101"],
      "sourceTaskIds": ["task-101"],
      "referencesSnapshot": true
    }
  ]
}
```

`referencesSnapshot` 只表示依据任务快照，不代表查询过实时系统。当前页面不展示 Run 的 `facts` 统计面板、执行阶段 `trace`、模型配置或整份报告审核控件；不要仅把整个 Run 放进 `answer`，应将其 `minutes`、`risks`、`coordination` 分别映射到同名字段。

## 9 对接现有 AI 服务

| 前端需要的数据或操作 | 可复用的内部服务 | 后端仍要补的适配 |
| --- | --- | --- |
| 普通回答 | `AssistantService.ask(conversationId, question)` | 将字符串包装成 `{answer, sources: []}`，校验会话权限 |
| 知识问答及引用 | `KnowledgeService.ask` 或 `askPublic` | 明确项目资料与公共资料边界，按授权范围检索 |
| 纪要 | `DiscussionService.analyze(messages)` | 从业务系统取得授权消息，返回到 `minutes` |
| 需求草稿 | `DecompositionService.decompose` 或 `decomposeWithKnowledge` | 保留 SavedDraft 的 `result`、`grounding` 层次 |
| 风险及协调建议 | `CollaborationService.collaborate` | 当前是模拟文件入口，需要适配真实业务数据；拆出对应结果字段 |
| 生成候选决策 | `DecisionReviewService.extract` | 当前读取文件；改为受控业务输入或服务端适配，不接收浏览器任意路径 |
| 查询候选 | `DecisionReviewService.get/list` | 限定项目和会话范围；不能将全进程候选列表直接暴露给任意用户 |
| 审核 | `DecisionReviewService.review` | 用登录身份补审核人，执行项目权限检查并保存记录 |
| 入库 | `DecisionReviewService.publish` | 服务端注入知识服务，落实实际保存及幂等语义 |

现有 AI 模块没有 HTTP Controller、平台身份鉴权和正式跨项目隔离，默认数据在内存。进度及三角色协作入口对模拟输入有约束；真实任务数据的接入需要适配，不能简单改一个标记就当作已完成。

这些接口是 Web 后端与内部 AI 服务之间的适配工作，不要求浏览器持有 DeepSeek、向量服务密钥，也不要求前端访问 Docker MySQL。

## 10 异常与并发要求

| HTTP 或异常情况 | 当前前端行为 | 后端对接要求 |
| --- | --- | --- |
| 401 | 提示登录失效 | 不返回登录页 HTML，不泄露原数据 |
| 403 | 提示无权限 | 检查角色、项目和会话归属，不靠按钮隐藏保护接口 |
| 404 | 提示 AI 服务暂不可用 | 接口未提供或资源不存在时使用适当状态；如需细分提示，需同步前端 |
| 409 | 提示状态变化；审核或入库区域提供刷新状态 | 重复审核、并发修改等冲突不能覆盖记录 |
| 400 或 422 | 当前显示通用请求失败 | 用于服务端参数校验；当前不会直接显示响应体里的 message |
| 5xx 或网络故障 | 保留原内容，不显示假成功 | 日志保留排障信息，响应不泄露供应商正文、密钥或内部路径 |
| 审核或入库超时、5xx、无法解析成功响应 | 认为结果待确认，需先刷新状态 | 工作区返回最新状态；客户端超时不能据此撤销或重复执行写操作 |
| 非 JSON、错误字段或错误状态枚举 | 不作为正常成功处理 | 按本文结构返回；不能用 200 加错误对象代替非 2xx |

前端不会读取错误响应体中的详细 `message`，也没有根据错误码自动跳转登录页的实现。如果后端计划依赖统一业务错误码或详细字段错误，需要另行调整适配层。

后端须保证审核状态检查与写入不会被并发请求绕过。重复审核返回冲突或双方约定的已确认结果，但不得重复生成审核记录；重复发布应幂等。客户端防重复点击不等于服务端已具备并发保护。

页面当前没有自动轮询。状态刷新来自进入页面、用户手动重新加载、结果待确认后的“刷新状态”，或者未连接时再次提问触发的加载。

## 11 配置和文件位置

以下路径均相对于 `aimanage-ai` 项目根目录。

| 文件 | 作用 |
| --- | --- |
| `src/main/resources/static/js/ai-api.js` | 四类接口路径、HTTP 调用、超时、响应及本地校验 |
| `src/main/resources/static/js/ai-workspace.js` | 页面加载、提问、标签展示、审核及入库交互 |
| `src/main/resources/templates/fragments/ai-workspace.html` | 页面内容和审核、入库弹窗 |
| `src/main/resources/static/css/ai-workspace.css` | AI 页面专用样式 |
| `src/main/resources/templates/prototype-page.html` | 原页面中接入 AI 区块和专用资源 |
| `src/main/resources/static/js/app.js` | 原公共脚本；已避免公共弹窗关闭逻辑干扰 AI 审核弹窗 |
| `src/test/frontend/ai-api.test.mjs` | 前端接口及校验测试 |
| `src/test/java/com/aimanage/prototype/WebFlowTest.java` | 原网站流程与 AI 页面集成检查 |
| `aiguanli-ai/handoff/README.md` | 现有 AI 内部服务说明，非已发布 HTTP 协议 |

若默认路径可以采用，无需额外注入配置。需要覆盖路径时，在 AI 页面模块执行前设置完整的四项配置；下面仅演示配置语法，路径应替换成后端确认的实际地址。

```javascript
window.aimanageAiConfig = {
  endpoints: {
    load: "/backend/ai/workspace",
    ask: "/backend/ai/questions",
    review: id => `/backend/ai/decisions/${encodeURIComponent(id)}/review`,
    publish: id => `/backend/ai/decisions/${encodeURIComponent(id)}/publish`
  }
};
```

配置 `endpoints` 时须提供完整四项，不会与默认路径自动逐项合并。如果网站部署在非根上下文，默认 `/api/ai/...` 路径也需对应调整，不能假设它会自动带上应用前缀。

需要 CSRF 时，可设置 `window.aimanageAiConfig.csrf = {headerName, token}`，或由页面提供 `_csrf_header`、`_csrf` 两个 meta 标签。Token 来自后端生成，不能在源码写死。若全局 CSP 禁止行内脚本，应通过允许加载的配置脚本或现有页面配置机制完成注入，不要为此放宽安全策略。

## 12 后端交付与联调清单

### 12.1 联调前需要确认

1. 当前项目如何确定；没有既有上下文时，项目参数如何传递。
2. 首次进入页面的会话如何初始化，以及会话是个人、项目共享还是按群组划分。
3. 是否采用本文四个默认路径、直接 JSON 格式和 Cookie 登录方式。
4. 单一提问入口如何选择普通问答、纪要、需求拆解或风险分析。
5. 消息、草稿、审核记录及知识索引分别由现有哪一部分保存，PUBLISHED 的正式业务含义是什么。
6. AI 调用是否能在 30 秒内返回；审核理由和修改文本的最大长度。

上述事项尚未全部落实前，不能仅凭前端页面和内部 Java 服务存在，就认定真实后端已接通。

### 12.2 最小联调顺序

1. 先接工作区：有正确权限、真实会话和空数组即可，不要求马上返回所有分析结果。
2. 接提问：先返回回答和空引用，再接真实知识引用与需要的结构化结果。
3. 接候选决策：明确来源、编号和状态，负责人可操作、开发人员只读。
4. 接审核与独立发布：确认状态检查、审核留痕及重复发布语义。
5. 最后验证刷新恢复、跨项目权限和异常分支。

### 12.3 验收场景

- [ ] 无历史消息时工作区返回有效会话、明确权限及空列表，页面不报结构错误。
- [ ] 普通成员可查看授权消息并提问，不能审核、入库；篡改请求仍被服务端拒绝。
- [ ] 提问成功显示真实回答；无引用使用空数组，有引用可核对来源。
- [ ] 纪要、需求、风险按实际返回更新；省略字段不清空其他结果，完整候选列表不遗漏历史条目。
- [ ] ACCEPT、MODIFY、REJECT 都要求理由；修改后采纳保留原稿、完整改稿及来源。
- [ ] 采纳后为 APPROVED，不自动变成 PUBLISHED；已否决记录不能发布。
- [ ] 发布失败不出现假成功；发布成功刷新后状态和内容一致。
- [ ] 两个用户同时审核不覆盖已有记录，重复发布不重复入库。
- [ ] 修改会话、决策、消息或资料编号，不能读取或操作其他项目数据。
- [ ] 模型失败、超时、服务端错误和非 JSON 响应不会泄露密钥或显示假结果。
- [ ] 用户在连接失败后仍能编辑问题、点击重试，原文不丢失。
- [ ] 应用重启后的实际表现符合已确认的保存规则，而不是把进程内存当持久化。

前端现有测试及页面检查不能替代上述真实模型、真实身份、跨项目和持久化联调。
