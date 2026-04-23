# CodePilot Progress

## 使用规则

1. 每完成一个小步骤，必须更新本文件中的状态、产出和验收结果。
2. 每完成一个小步骤，必须提交到本地 git 仓库。
3. 提交信息要明确说明本次完成了什么，不写笼统信息。
4. 当前优先级：
   - 先 `core`（Agent 内核）
   - 再 `gateway`（Web 层接入）
   - 最后 `eval`（评测中心）

---

## 已完成阶段

（尚无已完成的编码阶段，项目处于设计文档阶段）

### P0 项目基线

- 目标
  - 初始化项目骨架、git 仓库、文档基线。
- 当前状态
  - `DONE`（文档阶段）
- 备注
  - 已完成 4 份设计文档：`01-项目概述.md` / `02-架构设计.md` / `03-技术实现方案.md` / `04-评测与交付计划.md`
  - 已完成 `AGENTS.md` 开发规范
  - 已完成 5 个 AI coding skills
  - 已完成本文件 `progress.md`

---

## 当前阶段

### P1 Maven 多模块骨架 + 基础设施

- 目标
  - 建立 `codepilot-gateway / codepilot-core / codepilot-eval / codepilot-mcp-server / codepilot-cli` 多模块 Maven 项目。
  - 补齐 Spring Boot 3.5+ 基础配置、数据库连接（MySQL + PgVector）、Redis 配置。
  - 补齐 LLM 调用薄封装（OpenAI 兼容协议）。
- 计划产出
  - Maven 多模块 pom.xml
  - Spring Boot 主启动类
  - `LlmClient` 接口 + OpenAI 兼容实现（含流式）
  - MySQL + PgVector + Redis 连接配置
  - 基础 application.yml
- 验收方式
  - `mvnw compile` 通过
  - Spring Boot 可启动（不依赖外部服务时）
- 当前状态
  - `DONE`
- 实际产出
  - 根 `pom.xml` + 五模块 `pom.xml` + Maven Wrapper（`mvnw` / `.mvn/wrapper`）
  - `codepilot-gateway` Spring Boot 启动入口和基础 `application.yml`
  - `codepilot-core` 中的 `LlmClient`、`LlmRequest`、`LlmResponse`、`LlmChunk`
  - OpenAI 兼容 LLM 实现，支持同步 `chat` 和流式 `Flux<LlmChunk>`
  - MySQL、PgVector、Redis、Gateway、LLM 的类型化配置绑定
- 验收结果
  - `2026-04-23` 执行 `.\mvnw.cmd compile`，多模块编译通过
  - `2026-04-23` 执行 `.\mvnw.cmd -pl codepilot-gateway -am package -DskipTests` 后，`java -jar codepilot-gateway\\target\\codepilot-gateway-0.1.0-SNAPSHOT.jar --server.port=0` 启动成功
  - 启动日志确认 `CodePilotGatewayApplication` 在无外部服务依赖下完成启动，Tomcat 随机端口启动正常

### P2 Domain 骨架

- 目标
  - 围绕固定 Review Pipeline 建立最小领域模型。
- 计划产出
  - `domain/agent` — AgentDefinition, AgentState, AgentDecision
  - `domain/plan` — ReviewPlan, ReviewTask, TaskGraph
  - `domain/context` — ContextPack, DiffSummary, ImpactSet
  - `domain/memory` — ProjectMemory, ReviewPattern, TeamConvention
  - `domain/tool` — Tool, ToolResult, ToolCall
  - `domain/review` — Finding, Severity, ReviewResult
  - `domain/session` — ReviewSession, SessionEvent
  - MyBatis 持久化骨架（mapper / repository）
- 验收方式
  - `mvnw compile` 通过
  - `mvnw test` 通过（基础单元测试）
- 当前状态
  - `DONE`
- 实际产出
  - `codepilot-core` 新增 `domain/agent`、`plan`、`context`、`memory`、`tool`、`review`、`session` 最小领域模型
  - `ReviewTask`、`Finding`、`ReviewSession` 落地关键状态机与 fail-fast 约束，显式区分 `REPORTED` 与 `CONFIRMED`
  - 新增 `ProjectMemoryRepository`、`ReviewSessionRepository` 领域端口
  - 新增 MyBatis 持久化骨架：`ReviewSession` / `SessionEvent` / `ReviewPattern` / `TeamConvention` 的 mapper、row、repository adapter
  - 新增 `db/schema/001_p2_domain.sql` 作为当前 P2 表结构真相
  - 新增领域与持久化基础测试，覆盖状态迁移、依赖 DAG、session 事件装载、memory 聚合装载
- 验收结果
  - `2026-04-23` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=ReviewTaskTest,TaskGraphTest,FindingTest,ReviewSessionTest" test`，9 个领域测试全部通过
  - `2026-04-23` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=MybatisReviewSessionRepositoryTest,MybatisProjectMemoryRepositoryTest" test`，2 个持久化骨架测试全部通过
  - `2026-04-23` 执行 `.\mvnw.cmd compile` 与 `.\mvnw.cmd test`，多模块编译与测试通过

### P3 Agent Loop V1 — 单 ReviewAgent

- 目标
  - 实现最短路径的 Agent Loop：输入 diff → 单 ReviewAgent → 输出 findings。
  - 不做 Planning、不做 Multi-Agent、不做 Memory——先让核心循环跑通。
- 计划产出
  - `ReviewEngine`（Agent Loop 核心：LLM 调用 → 决策解析 → Tool 执行 → 上下文注入 → 循环）
  - `ToolCallParser`（双模式：Function Calling + Prompt 驱动）
  - `ToolRegistry` + `ToolExecutor`（基础版：串行执行）
  - 基础 Tool：`read_file`, `ast_parse`, `search_pattern`
  - `TokenCounter`（简单 tiktoken 兼容实现）
  - Prompt 模板（ReviewAgent system prompt + 结构化输出格式）
  - CLI 模式：`java -jar codepilot-cli.jar review --diff ./changes.diff`
- 验收方式
  - CLI 模式能跑通：输入一个有 SQL 注入的 diff → 输出 finding
  - Agent Loop 至少能完成一次完整的 ReAct 循环
  - `mvnw test` 通过
- 当前状态
  - `PENDING`

### P4 Context Compiler V1

- 目标
  - 实现 Context 编译基础版：diff 分析 → AST 解析 → 文件拉取 → 上下文组装。
  - 不做 token 预算治理、不做增量编译——先让编译链路跑通。
- 计划产出
  - `ContextCompiler` 基础实现
  - `DiffAnalyzer` — 解析 diff 提取变更文件和方法
  - `AstParser` — 基于 JavaParser 的 AST 解析
  - `ImpactCalculator` — 基于引用关系的初步影响范围计算
  - `ContextPack` 组装逻辑
  - 编译 profile：`java-springboot-maven.json`
- 验收方式
  - 给定一个 diff，ContextCompiler 能产出 ContextPack
  - ContextPack 包含变更文件内容 + 直接依赖的符号信息
  - `mvnw test` 通过
- 当前状态
  - `PENDING`

### P5 Gateway + GitHub 集成

- 目标
  - 打通 Webhook 接收 → Agent Review → PR Comment 回写的完整链路。
- 计划产出
  - `WebhookReceiver` — GitHub Webhook 接收 + HMAC 验签
  - `SseController` — SSE 流式推送 review 进度
  - `GitHubCommentWriter` — 回写 PR Review + Inline Comments
  - `ReviewApiController` — REST 查询接口
  - 去重逻辑（同一 PR + commit 只处理一次）
  - Redis Stream 事件缓冲
- 验收方式
  - 提交一个测试 PR → Webhook 触发 → Agent 自动 review → PR 收到 comment
  - SSE 推送可连接
  - `mvnw test` 通过
- 当前状态
  - `PENDING`

---

## 下一阶段

### P6 Tool 系统增强 + 并发分区

- 目标
  - 补齐更多 Tool + 实现并发分区（只读并行 / 互斥串行）。
- 计划产出
  - 更多 Tool：`git_blame`, `git_log`, `git_diff_context`, `ast_find_references`, `ast_get_call_chain`, `memory_search`
  - `ToolExecutor` 升级：并发分区执行（CompletableFuture.allOf）
  - Tool 结果缓存
  - Tool 超时控制
- 优先级
  - `P1`
- 当前状态
  - `PENDING`

### P7 Context Governance + Loop Detection

- 目标
  - 实现 Context 治理（Microcompact + History Snipping）和 Loop Detection（pattern + LLM 双模式）。
- 计划产出
  - `ContextGovernor` — Microcompact + HistorySnipping + OrphanCleanup
  - `LoopDetector` — pattern 检测 + LLM 判断
  - Token 预算分配和动态检查
  - Agent 降级策略（Loop Detection 触发时返回 partial findings）
- 优先级
  - `P1`
- 当前状态
  - `PENDING`

### P8 Multi-Agent + Planning

- 目标
  - 实现多 Agent 编排：Planner 拆任务 → 专项 Reviewer 并行 → Merger 合并。
- 计划产出
  - `PlanningAgent` — 分析 diff 产出 ReviewPlan
  - `ReviewAgent Pool` — Security / Perf / Style / Maintainability
  - `Orchestrator` — 状态机 + 任务调度 + 并行执行
  - `MergeAgent` — findings 合并、去重、排序
  - SSE 事件类型扩展（task_started / finding_found / task_completed）
- 优先级
  - `P1`
- 当前状态
  - `PENDING`

### P9 Memory 系统

- 目标
  - 实现三层 Memory + Dream 沉淀。
- 计划产出
  - `MemoryService` — 三层 Memory 检索（Working / Project / Global）
  - PgVector 向量检索接入
  - 规则匹配引擎（Global Knowledge）
  - `DreamProcessor` — 两阶段沉淀（Phase1 分析 + Phase2 增量更新）
  - Memory 注入 Context 编译流程
  - 种子数据（安全 pattern + 性能反模式 + 编码规范）
- 优先级
  - `P1`
- 当前状态
  - `PENDING`

### P10 Eval Center

- 目标
  - 实现 Eval Center + 47 个评测场景 + Scorecard。
- 计划产出
  - `EvalRunner` — 端到端评测运行器
  - `EvalScenario` 加载和解析
  - `Scorecard` 生成
  - 47 个评测场景 JSON
  - 3-5 个 fixture repo
  - Baseline 对比实验框架
  - 评测报告生成（scorecard.json + review-eval-report.md）
- 优先级
  - `P1`
- 当前状态
  - `PENDING`

### P11 Session 事件溯源 + 中断恢复

- 目标
  - 实现 Session 事件溯源，支持中断恢复。
- 计划产出
  - `SessionEvent` 事件追加
  - `SessionStore` 事件重放
  - 中断恢复逻辑（从最后 checkpoint 继续）
  - 超时处理（单个 ReviewTask 超时 → SKIPPED）
- 优先级
  - `P2`
- 当前状态
  - `PENDING`

### P12 MCP Server

- 目标
  - 把 review 能力暴露为标准 MCP 工具，支持其他 Agent 调用。
- 计划产出
  - `codepilot-mcp-server` 模块
  - MCP 工具：`review_diff`, `review_pr`, `search_memory`
  - JSON-RPC 2.0 协议实现
- 优先级
  - `P2`
- 当前状态
  - `PENDING`

### P13 评测与量化

- 目标
  - 在 47 个评测场景上跑端到端评测，产出 Baseline 对比数据，提炼简历亮点。
- 计划产出
  - 完整 Scorecard
  - Baseline 对比实验结果
  - 评测数据可视化
  - 简历项目描述 + 面试话术
  - 技术博客
- 优先级
  - `P1`
- 当前状态
  - `PENDING`

---

## 延期清单

以下能力当前明确不在主链范围内，避免把它们重新混进开发：

1. **前端 UI** — MVP 用 CLI + PR Comment，前端不阻塞核心功能
2. **多语言支持** — 先做 Java，其他语言通过 compilation profile 扩展
3. **代码自动修复** — Agent 只做审查，不做代码生成/修改
4. **IDE 插件** — 先不做 VS Code / JetBrains 插件
5. **自托管模型** — 先用云端 API，本地模型是后续优化方向
6. **多 Git 平台** — 先做 GitHub，GitLab 等通过 adapter 扩展
