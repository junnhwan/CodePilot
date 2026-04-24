# CodePilot — AI Coding Session Prompt 指南

> 本文档定义了用 AI coding agent 开发 CodePilot 时的 Prompt 写法，包括首次启动 Prompt 和上下文续接 Prompt。

---

## 1. 首次启动 Prompt

当开一个全新 session 开始编码时，Prompt 必须包含三部分：**项目身份 → 必读文档 → 当前任务**。

```
你正在开发 CodePilot —— 一个 AI Code Review Agent 项目。

## 项目核心约束（必须遵守）

1. 只读审查，不做代码生成
2. 固定 Review Pipeline：Diff接收 → Planning → Context编译 → Multi-Agent Review → Merge → Report → Memory沉淀
3. Agent Loop / Context 编译 / Tool 调度 / Memory 管理——全部手搓，不用 Spring AI / LangChain4j 的高层封装
4. 上下文编译是第一公民，不是"塞文件"
5. FINDING_REPORTED != ISSUE_CONFIRMED
6. 不做占位抽象、不发明平行概念、不写胶水代码

## 必读文档（按顺序读）

1. AGENTS.md —— 开发规范和冻结判断（最优先）
2. 02-架构设计.md —— 系统架构、Agent 设计、Context 编译、Memory、Tool
3. 03-技术实现方案.md —— 技术栈、关键组件实现代码、接口设计、Prompt 模板
4. progress.md —— 当前实现阶段和下一步任务

读完这 4 个文件后，再开始编码。

分支规则：见 AGENTS.md。

## 当前任务

[P1] Maven 多模块骨架 + 基础设施

目标：
- 建立 codepilot-gateway / codepilot-core / codepilot-eval / codepilot-mcp-server / codepilot-cli 五模块 Maven 项目
- Java 21 + Spring Boot 3.5+
- 补齐 LlmClient 接口 + OpenAI 兼容实现（含流式 Flux<LlmChunk>）
- MySQL + PgVector + Redis 连接配置
- 基础 application.yml

验收：
- mvnw compile 通过
- Spring Boot 可启动

完成后：
- 更新 progress.md 中 P1 的状态为 DONE，记录产出和验收结果
- git commit，提交信息格式：`feat(P1): 具体完成内容`
```

### 关键点

- **项目核心约束必须写在 Prompt 里**——不要假设 AI 会去读 AGENTS.md 后自动遵守，直接在 Prompt 中声明最关键的 6 条
- **必读文档按优先级排序**——AGENTS.md 最优先，因为它包含冻结判断和编码约束
- **当前任务精确到 Phase 编号 + 具体产出 + 验收方式**——不要写"开始编码"，要写"完成 P1，产出 X/Y/Z，验收方式是 mvnw compile"
- **完成后动作明确**——更新 progress.md + git commit，确保 session 产出可追溯

---

## 2. 上下文续接 Prompt

当上下文满了开新 session 继续时，Prompt 必须让新 AI 快速恢复状态，不需要重新读所有文档。

### 2.1 续接 Prompt 模板

```
你正在继续开发 CodePilot —— AI Code Review Agent 项目。

## 项目核心约束（同前）

1. 只读审查，不做代码生成
2. 固定 Review Pipeline：Diff接收 → Planning → Context编译 → Multi-Agent Review → Merge → Report → Memory沉淀
3. Agent Loop / Context 编译 / Tool 调度 / Memory 管理——全部手搓，不用框架高层封装
4. 上下文编译是第一公民
5. FINDING_REPORTED != ISSUE_CONFIRMED
6. 不做占位抽象、不发明平行概念、不写胶水代码

## 恢复状态（先读这些文件）

1. progress.md —— 查看当前进度，哪些 Phase 完成了，哪个正在进行
2. AGENTS.md —— 开发规范（快速回顾约束）

读完 progress.md 后，根据"当前状态：IN_PROGRESS"的 Phase 继续工作。

## 上次断点

上一个 session 完成了 [具体描述，如：P1 Maven 多模块骨架，但 LlmClient 的流式实现还没写完，只完成了同步调用部分]

当前需要继续：
[具体描述，如：完成 LlmClient 的 Flux<LlmChunk> 流式实现 + 补齐 application.yml 中的模型路由配置]

## 注意事项

- 不要重做已经完成的部分，先读 progress.md 确认
- 如果不确定某个功能是否已实现，先读对应模块的代码再判断
- 完成后同样要更新 progress.md + git commit
```

### 2.2 关键点

- **续接 Prompt 要写"上次断点"**——精确到"完成了什么、还差什么"，不要写"继续上次的工作"
- **先读 progress.md**——这是进度真相来源，比任何口头描述都可靠
- **不需要重新读所有设计文档**——progress.md 记录了哪些 Phase 完成了，新 session 只需要读当前 Phase 相关的设计文档
- **核心约束再次声明**——新 session 没有"上次对话"的记忆，必须重新声明

---

## 3. 按 Phase 的 Prompt 变体

不同 Phase 需要额外指定不同的设计文档：

| Phase | 额外必读 | 原因 |
|-------|---------|------|
| P1 骨架 | 03-技术实现方案.md §1 技术栈 | 知道用什么依赖和版本 |
| P2 Domain | 02-架构设计.md §2 模块拆分 + 03-技术实现方案.md §2.5 Memory 存储 SQL | 需要领域模型和表结构 |
| P3 Agent Loop | 02-架构设计.md §3.3 Agent Loop + 03-技术实现方案.md §2.2 ReviewEngine + §2.4 LoopDetector | Agent Loop 是手搓核心，必须严格按设计实现 |
| P4 Context | 02-架构设计.md §4 Context 编译 + 03-技术实现方案.md §2.3 ContextGovernor | Context 编译是第一公民 |
| P5 Gateway | 03-技术实现方案.md §3 接口设计 | Webhook/SSE/PR Comment 的接口协议 |
| P6 Tool 增强 | 02-架构设计.md §7 Tool 系统 | Tool 并发分区逻辑 |
| P7 Governance | 03-技术实现方案.md §2.3 + §2.4 | Context Governance + Loop Detection |
| P8 Multi-Agent | 02-架构设计.md §3 Agent 设计 + §6 Planning | Planner/Reviewer/Merger 角色和状态机 |
| P9 Memory | 02-架构设计.md §5 Memory + 03-技术实现方案.md §2.5 | 三层记忆 + Dream 沉淀 |
| P10 Eval | 04-评测与交付计划.md §1-2 | 评测指标、场景、Scorecard |

### 使用方式

在 Prompt 的"当前任务"部分，加上：

```
## 当前任务

[P3] Agent Loop V1 — 单 ReviewAgent

额外必读文档：
- 02-架构设计.md §3.3 Agent Loop 核心流程
- 03-技术实现方案.md §2.2 ReviewEngine 实现
- 03-技术实现方案.md §2.4 LoopDetector 实现

目标：
- 实现 ReviewEngine（Agent Loop：LLM 调用 → 决策解析 → Tool 执行 → 循环）
- 实现 ToolCallParser（双模式：Function Calling + Prompt 驱动）
- 实现 ToolRegistry + ToolExecutor（基础版：串行执行）
- 实现 TokenCounter
- 实现 CLI 模式

验收：
- CLI 能跑通：输入有 SQL 注入的 diff → 输出 finding
- mvnw test 通过
```

---

## 4. 用 Skills 的 Prompt 写法

当任务匹配某个 skill 时，在 Prompt 中显式触发：

```
## 当前任务

[P10] Eval Center

在编写评测场景时，使用 .codex/skills/codepilot-review-scenario-author/SKILL.md 的规范。

在解读评测报告时，使用 .codex/skills/codepilot-eval-report-reader/SKILL.md 的规范。

目标：
- 实现 EvalRunner
- 编写 47 个评测场景 JSON
- ...

验收：
- ...
```

---

## 5. 常见错误 Prompt 及修正

| 错误 Prompt | 问题 | 修正 |
|-------------|------|------|
| "帮我写 CodePilot 项目" | 太模糊，AI 不知道从哪开始 | 精确到 Phase + 产出 + 验收 |
| "继续开发" | 没有断点信息，AI 不知道上次做到哪 | 写明"上次完成了 X，还差 Y" |
| "按照设计文档开发" | 没指定哪个文档、哪个章节 | 写明"读 02-架构设计.md §3.3，实现 ReviewEngine" |
| "用 Spring AI 实现 Agent" | 违反"手搓 Agent 内核"约束 | Prompt 里声明约束，AI 就不会用框架封装 |
| "把所有文档都读一遍" | 浪费上下文，不是每个 Phase 都需要所有文档 | 按 Phase 指定必读文档 |
| "优化一下代码" | 没有方向，AI 可能做无关重构 | 基于评测报告，用 eval-report-reader skill 归因后再指定优化方向 |

---

## 6. Prompt 检查清单

每次写 Prompt 前过一遍：

1. 是否声明了 6 条核心约束？
2. 是否指定了必读文档（AGENTS.md + 当前 Phase 相关文档）？
3. 当前任务是否精确到 Phase 编号 + 具体产出 + 验收方式？
4. 如果是续接 session，是否写明了上次断点？
5. 完成后的动作是否明确（更新 progress.md + git commit）？
6. 如果任务匹配某个 skill，是否在 Prompt 中引用了该 skill？
