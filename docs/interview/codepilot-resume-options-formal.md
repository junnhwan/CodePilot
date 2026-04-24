# CodePilot 简历写法备选（正式版）

## 1. 使用说明

这份版本专门面向正式简历表达，原则是：

- 不写项目内部类名
- 不堆代码实现细节
- 重点写清楚系统能力、工程亮点、可验证结果
- 语言尽量正式、务实、适合投递

当前内容基于仓库真相整理，适合你投 `后端开发`、`Agent 开发`、`AI 应用开发` 相关实习岗位。

## 2. 平衡版

这个版本最适合海投，也是最推荐的默认版本。

### 简版

> **CodePilot｜面向真实研发场景的 AI Code Review Agent**  
> 基于 Java 21、Spring Boot 设计并实现多模块 AI Code Review 系统，围绕 `Diff接收 -> Planning -> Context编译 -> Multi-Agent Review -> Merge -> Report -> Memory沉淀` 主链完成只读审查闭环。  
> - 自主实现多阶段审查编排、动态上下文编译、多 Reviewer 协同审查与结果合并，支持 token 预算治理  
> - 打通 GitHub Webhook、SSE 进度推送、PR comment 回写、本地 CLI 与 MCP 工具暴露，形成完整接入与输出链路  
> - 实现项目记忆沉淀、会话事件回放与阶段恢复，并构建支持 4 路 baseline 的评测模块；当前默认支持 7 个评测场景，最近一次全仓验证 82 个测试通过

### STAR版

> **CodePilot｜面向真实研发场景的 AI Code Review Agent**  
> **S**：常见 AI Code Review 项目多停留在“直接把 diff 交给模型”的 demo 阶段，缺少上下文治理、恢复机制和量化评测。  
> **T**：实现一个可运行、可验证、可对外暴露能力的 AI Code Review 系统，而不是只封装模型调用。  
> **A**：基于 Java 21、Spring Boot 设计多模块架构，自主实现审查规划、上下文编译、多 Reviewer 协同、项目记忆沉淀、会话恢复、Webhook 接入、MCP 工具暴露和多 baseline 评测。  
> **R**：项目形成完整只读审查闭环，支持 GitHub 接入、本地 CLI、MCP 工具和评测报告输出，当前仓库已完成 P17，最近一次全仓验证 82 个测试通过。

## 3. 后端优先版

这个版本更适合传统后端开发岗。

### 简版

> **CodePilot｜基于 Java / Spring Boot 的 AI Code Review 后端系统**  
> - 使用 Java 21、Spring Boot、Maven 多模块实现代码审查后端系统，拆分核心审查、网关接入、评测、CLI 与 MCP 服务模块  
> - 实现 GitHub Webhook 接收、SSE 进度推送、PR comment 回写与审查结果查询，打通从事件接入到结果输出的服务链路  
> - 基于事件回放支持多阶段会话恢复与续跑，并构建评测模块完成多 baseline 对比和 Markdown/JSON 报告输出

### STAR版

> **CodePilot｜基于 Java / Spring Boot 的 AI Code Review 后端系统**  
> **S**：希望做一个具备真实服务形态的后端项目，而不是只有单机脚本或页面展示的 AI demo。  
> **T**：把 AI Code Review 需求拆成稳定的后端系统能力，包括接入、编排、恢复、查询和评测。  
> **A**：采用 Java 21、Spring Boot 和 Maven 多模块组织系统，实现 Webhook 接入、SSE 推送、PR comment 回写、会话恢复、CLI 入口、MCP 能力暴露以及评测报告输出。  
> **R**：系统已形成从 GitHub PR 接入到结果回写、从本地命令行到标准协议暴露、从运行到评测的完整后端闭环，具备明确模块边界和稳定性设计。

## 4. Agent 优先版

这个版本更适合 Agent 开发、AI 应用开发、AI 工程方向。

### 简版

> **CodePilot｜基于 Multi-Agent 与上下文编译的 AI Code Review Agent**  
> - 自主实现 AI 审查循环，支持工具调用、上下文治理、循环检测和多 Reviewer 协同编排，而非依赖高层 Agent 编排框架  
> - 基于 diff、AST、影响范围、项目记忆和通用知识动态编译审查上下文，并显式纳入 token budget 治理  
> - 构建支持 4 路 baseline 的评测体系，用于对比自主编排主链与直接 LLM、全量上下文和规则型 baseline 的差异

### STAR版

> **CodePilot｜基于 Multi-Agent 与上下文编译的 AI Code Review Agent**  
> **S**：通用 LLM Review 容易出现上下文冗余、结果不稳定、缺少量化基线等问题，难以体现 Agent 工程能力。  
> **T**：实现一个具备自主编排、上下文编译、记忆沉淀与评测闭环的 AI Code Review Agent。  
> **A**：自主实现审查规划、多 Reviewer 协同、动态上下文编译、项目记忆沉淀、阶段恢复和多 baseline 评测，并通过 CLI、Gateway 和 MCP 工具对外提供能力。  
> **R**：项目已形成从 Agent 编排、上下文工程、记忆机制到评测与协议暴露的完整链路，明显区别于“单 prompt + 检索”的轻量 AI demo。

## 5. 三条版

如果你的简历项目区只能放 3 条 bullet，建议直接用下面之一。

### 平衡版三条

- 基于 Java 21、Spring Boot 设计并实现多模块 AI Code Review 系统，围绕固定审查主链完成只读审查闭环
- 自主实现多阶段审查编排、动态上下文编译、项目记忆沉淀与会话恢复，支持多 Reviewer 协同与 token 预算治理
- 构建支持 4 路 baseline 的评测模块和 Markdown/JSON 报告输出能力；当前默认支持 7 个评测场景，最近一次全仓验证 82 个测试通过

### 后端版三条

- 使用 Java 21、Spring Boot、Maven 多模块实现 AI Code Review 后端系统，拆分核心审查、网关接入、评测与工具暴露模块
- 打通 GitHub Webhook、SSE 推送、PR comment 回写、结果查询与 MCP 暴露，形成完整服务链路
- 基于事件回放支持多阶段会话恢复，并构建多 baseline 对比与报告输出能力

### Agent版三条

- 自主实现 AI 审查循环、多 Reviewer 协同与工具调用机制，不依赖高层 Agent 编排框架
- 基于 diff、AST、项目记忆和 token budget 动态编译审查上下文，提升审查主链的上下文组织能力
- 构建 4 路 baseline 评测体系，对比自主编排主链与直接 LLM、全量上下文、规则型 baseline 的差异

## 6. 我最推荐你现在直接用的版本

如果你现在就投，建议先用下面这版：

> **CodePilot｜面向真实研发场景的 AI Code Review Agent**  
> 基于 Java 21、Spring Boot 设计并实现多模块 AI Code Review 系统，围绕 `Diff接收 -> Planning -> Context编译 -> Multi-Agent Review -> Merge -> Report -> Memory沉淀` 主链完成只读审查闭环。  
> - 自主实现多阶段审查编排、动态上下文编译、多 Reviewer 协同审查与结果合并，支持 token 预算治理  
> - 打通 GitHub Webhook、SSE 进度推送、PR comment 回写、本地 CLI 与 MCP 工具暴露，形成完整接入与输出链路  
> - 实现项目记忆沉淀、会话事件回放与阶段恢复，并构建支持 4 路 baseline 的评测模块；当前默认支持 7 个评测场景，最近一次全仓验证 82 个测试通过

## 7. 投递建议

- 投后端开发：优先用后端优先版，其次平衡版
- 投 Agent / AI 应用：优先用 Agent 优先版，其次平衡版
- 海投实习：优先用平衡版

如果你后面要继续精修，这份正式版会比带类名的版本更适合直接贴进简历。
