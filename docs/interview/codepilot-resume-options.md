# CodePilot 简历写法备选

## 1. 现在能不能写进简历

可以，而且已经到了“建议写进简历”的阶段。

原因不是“功能看起来很多”，而是当前仓库已经具备了一个可解释、可运行、可验证的工程闭环：

- 有明确主链：`Diff接收 -> Planning -> Context编译 -> Multi-Agent Review -> Merge -> Report -> Memory沉淀`
- 有真实后端工程形态：Java 21、Spring Boot、多模块、Webhook、SSE、MCP、持久化、事件恢复
- 有 Agent 差异化能力：自主实现 `ReviewEngine`、`ContextCompiler`、`ReviewerPool`、`DreamService`
- 有量化能力：`codepilot-eval` 支持 `codepilot / direct_llm / full_context_llm / lint_only` 4 路 baseline
- 有恢复与可靠性设计：`SessionStore` 和 `GitHubReviewWorker` 已支持 `PLANNING / REVIEWING / MERGING / REPORTING` 阶段恢复
- 有验证证据：当前最近一次全仓验证为 82 个测试通过

如果你是大二学生，这个项目已经明显超过“课程作业”或“玩具 demo”的强度，完全可以作为主项目之一写进简历。

## 2. 写进简历时的总原则

### 该怎么定位

推荐把它定位成：

`CodePilot：面向真实研发场景的 AI Code Review Agent`

或者：

`CodePilot：基于 Multi-Agent 与上下文编译的 AI Code Review 系统`

### 该突出什么

- 对后端岗：突出工程化、模块化、接口、恢复、评测、稳定性
- 对 Agent 岗：突出 Agent Loop、Context Compiler、Memory、Eval、Baseline
- 对在校生身份：突出“我不是只接了一个模型 API，而是把它做成了可运行系统”

### 不要写太满的点

- 不要写“自动修复代码”，因为项目只做只读审查
- 不要写“问题确认率/F1 达到某具体数字”，除非你拿的是自己最新跑出来的 eval 报告
- 不要把 `finding` 直接写成“确认的 bug”
- 不要把 Dream 写成成熟的通用知识平台，当前更准确的说法是“review 后的项目记忆沉淀”

## 3. 建议你简历里的项目标题

你可以从下面 3 个标题里选一个：

### 平衡版标题

`CodePilot：面向真实研发场景的 AI Code Review Agent`

### 后端优先版标题

`CodePilot：基于 Java / Spring Boot 的 AI Code Review 后端系统`

### Agent 优先版标题

`CodePilot：基于 Multi-Agent、上下文编译与评测闭环的 AI Code Review Agent`

## 4. 平衡版

这个版本最适合海投，也是我最推荐的主版本。

### 简版

> **CodePilot｜面向真实研发场景的 AI Code Review Agent**  
> 基于 Java 21、Spring Boot 构建多模块 AI Code Review 系统，围绕 `Diff接收 -> Planning -> Context编译 -> Multi-Agent Review -> Merge -> Report -> Memory沉淀` 主链实现只读审查闭环。  
> - 自主实现 `ReviewEngine`、`ContextCompiler`、`ReviewOrchestrator`，实现 Planning、多 Reviewer 并行审查、finding merge 与 token 预算治理  
> - 实现 GitHub Webhook、SSE 进度推送、PR comment 回写、本地 CLI 和 MCP Server，对外暴露 `review_diff / review_pr / search_memory` 能力  
> - 实现 Dream 记忆沉淀与 Session 事件恢复，支持 `PLANNING / REVIEWING / MERGING / REPORTING` 阶段续跑，避免中断后重复执行已完成任务  
> - 构建 Eval Center，支持 `codepilot / direct_llm / full_context_llm / lint_only` 4 路 baseline、7 个默认场景和 JSON/Markdown 报告输出；全仓验证 82 个测试通过

### STAR版

> **CodePilot｜面向真实研发场景的 AI Code Review Agent**  
> **S**：很多 AI 项目只停留在“把 diff 丢给 LLM”的 demo，缺少上下文治理、恢复机制和量化评测，难以支撑真实工程场景。  
> **T**：独立实现一个可写进简历、可解释、可验证的 AI Code Review 系统，而不是只封装模型调用。  
> **A**：基于 Java 21 + Spring Boot 设计多模块架构，自主实现 `ReviewEngine`、`ContextCompiler`、`ReviewOrchestrator`、`DreamService`、`SessionStore`；实现 GitHub Webhook、SSE、CLI、MCP Server，以及 4 路 baseline 的 Eval Center。  
> **R**：项目形成了完整的只读审查主链，支持项目记忆沉淀、阶段恢复、MCP 工具暴露和评测报告输出；当前仓库已完成 P17，默认支持 7 个评测场景，最近一次全仓测试 82 项通过。

## 5. 后端优先版

这个版本更适合传统后端开发岗，会弱化一点 Agent 术语，但保留 AI 项目的特色。

### 简版

> **CodePilot｜基于 Java / Spring Boot 的 AI Code Review 后端系统**  
> - 使用 Java 21、Spring Boot 搭建 Maven 多模块后端系统，拆分 `core / gateway / cli / eval / mcp-server`，围绕代码审查流程实现可运行服务  
> - 实现 GitHub Webhook 接收、SSE 进度推送、PR comment 回写和 Review 查询接口，打通从 PR 事件接入到审查结果回写的后端链路  
> - 基于事件回放实现 Session 恢复，支持 `PLANNING / REVIEWING / MERGING / REPORTING` 阶段续跑，减少中断后重复执行  
> - 构建评测模块和 CLI 工具，支持多 baseline 对比、Markdown/JSON 报告输出；全仓验证 82 个测试通过

### STAR版

> **CodePilot｜基于 Java / Spring Boot 的 AI Code Review 后端系统**  
> **S**：目标是做一个真正可运行的后端项目，而不是只有单机脚本或前端页面的 AI demo。  
> **T**：把 AI Code Review 需求拆成稳定的后端系统能力，包括接入、编排、恢复、查询和评测。  
> **A**：使用 Java 21、Spring Boot 和 Maven 多模块搭建系统；在 `gateway` 中实现 Webhook、SSE、Comment 回写；在 `core` 中实现 review 编排、Memory、Session 恢复；在 `eval` 中实现 baseline 对比与报告输出；在 `mcp-server` 中将能力暴露为标准 MCP 工具。  
> **R**：系统已形成从 GitHub PR 接入到结果回写、从本地 CLI 到 MCP 暴露、从运行到评测的后端闭环，具备明确模块边界和恢复能力，适合作为后端工程项目展示。

## 6. Agent 优先版

这个版本更适合投 Agent 开发、AI 应用开发、AI 工程方向。

### 简版

> **CodePilot｜基于 Multi-Agent 与上下文编译的 AI Code Review Agent**  
> - 自主实现 `ReviewEngine`，完成 Reviewer 决策解析、Tool 调用、Context Governance、Loop Detection，而非依赖 Spring AI / LangChain4j 高层 Agent 编排  
> - 实现 `ContextCompiler`，基于 diff、AST、影响范围、Project Memory 与 Global Knowledge 组装 `ContextPack`，并显式纳入 token budget 治理  
> - 实现 Planner + Reviewer Pool + MergeAgent 的多 Agent 审查链路，并通过 Dream 机制将高置信度 finding 沉淀为项目记忆  
> - 构建 Eval Center，对比 `codepilot / direct_llm / full_context_llm / lint_only` 4 路 baseline，输出 scorecard、baseline delta 和 scenario matrix

### STAR版

> **CodePilot｜基于 Multi-Agent 与上下文编译的 AI Code Review Agent**  
> **S**：通用 LLM Review 往往存在上下文冗余、结果不稳定、缺少评测基线等问题，难以体现 Agent 工程能力。  
> **T**：实现一个具备自主编排、上下文治理、记忆沉淀和评测闭环的 AI Code Review Agent。  
> **A**：自主实现 `ReviewEngine`、`ContextCompiler`、`ReviewerPool`、`MergeAgent`、`DreamService`、`EvalRunner`；以 diff 和 AST 为输入动态编译 review 上下文，按规划结果调度多 reviewer，并用 Eval Center 进行 4 路 baseline 对比。  
> **R**：项目已形成从 Agent 编排、上下文编译、项目记忆、恢复能力到评测报告输出的完整链路，明显区别于“单 prompt + 向量检索”的轻量 AI demo。

## 7. 如果你只能放 3 条 bullet，我建议这样写

### 方案 A：最稳

- 基于 Java 21、Spring Boot 设计并实现多模块 AI Code Review 系统，围绕 `Diff接收 -> Planning -> Context编译 -> Multi-Agent Review -> Merge -> Report -> Memory沉淀` 主链完成只读审查闭环
- 自主实现 `ReviewEngine`、`ContextCompiler`、`ReviewOrchestrator`，实现多 Reviewer 并行审查、token 预算治理、项目记忆沉淀与 Session 阶段恢复
- 构建 Eval Center，支持 4 路 baseline、7 个默认场景以及 JSON/Markdown 报告输出；项目最近一次全仓验证 82 个测试通过

### 方案 B：偏后端

- 使用 Java 21、Spring Boot、Maven 多模块实现 AI Code Review 后端系统，拆分 `core / gateway / cli / eval / mcp-server`
- 打通 GitHub Webhook、SSE、PR comment 回写、Review 查询和 MCP 工具暴露，形成完整服务接入与结果输出链路
- 通过事件回放支持多阶段 Session 恢复，并构建评测模块完成多 baseline 对比与报告输出

### 方案 C：偏 Agent

- 自主实现 AI Code Review Agent 核心循环，完成 Tool 调用、Loop Detection、Context Governance 和多 Reviewer 编排
- 基于 diff、AST、Project Memory、Global Knowledge 构建上下文编译链路，在 token budget 内动态组织 `ContextPack`
- 设计并实现 4 路 baseline 的 Eval Center，用于量化对比 Agent 主链与直接 LLM / Full Context / Lint Only 的差异

## 8. 如果你要投不同岗位，怎么选

### 投后端开发

优先顺序：

1. 后端优先版
2. 平衡版
3. Agent 优先版

你在项目名下面最好补一个技术栈：

`Java 21 / Spring Boot / Maven / MyBatis / Redis / JGit / JavaParser`

### 投 Agent 开发

优先顺序：

1. Agent 优先版
2. 平衡版
3. 后端优先版

你在项目名下面最好补一个能力关键词：

`Agent Loop / Context Engineering / Multi-Agent / Memory / Eval / MCP`

### 海投实习

优先建议：

- 简历主版本用平衡版
- 如果岗位明显偏 Java 后端，就替换成后端版
- 如果岗位明显写了 Agent、LLM、AI 应用、RAG、MCP，就替换成 Agent 版

## 9. 我最推荐你现在直接用的版本

如果你现在就要投，我建议你先用这版：

> **CodePilot｜面向真实研发场景的 AI Code Review Agent**  
> 基于 Java 21、Spring Boot 构建多模块 AI Code Review 系统，围绕 `Diff接收 -> Planning -> Context编译 -> Multi-Agent Review -> Merge -> Report -> Memory沉淀` 主链实现只读审查闭环。  
> - 自主实现 `ReviewEngine`、`ContextCompiler`、`ReviewOrchestrator`，实现 Planning、多 Reviewer 并行审查、finding merge 与 token 预算治理  
> - 实现 GitHub Webhook、SSE 进度推送、PR comment 回写、本地 CLI 和 MCP Server，对外暴露 `review_diff / review_pr / search_memory` 能力  
> - 实现 Dream 记忆沉淀与 Session 事件恢复，支持 `PLANNING / REVIEWING / MERGING / REPORTING` 阶段续跑，避免中断后重复执行已完成任务  
> - 构建 Eval Center，支持 `codepilot / direct_llm / full_context_llm / lint_only` 4 路 baseline、7 个默认场景和 JSON/Markdown 报告输出；全仓验证 82 个测试通过

## 10. 下一步我还能继续帮你做什么

如果你愿意，我下一步可以继续直接帮你做下面任一项：

1. 按你真实简历模板，把这段压缩成“一页简历可直接贴入”的最终版
2. 再给你一版“更像大厂实习生风格”的精炼措辞
3. 顺手把你的整个项目经历区写成统一风格，包括技术栈、难点、亮点和面试追问点
