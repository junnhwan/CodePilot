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
  - `DONE`
- 实际产出
  - `codepilot-core` 新增 `ReviewEngine`、`ToolCallParser`、`TokenCounter`、`ReviewPromptTemplates`，形成单 `ReviewAgent` 的最小 ReAct Loop
  - 新增 `ToolRegistry` + `ToolExecutor` 基础版，当前按注册顺序串行执行只读 Tool，遇到未知工具或运行异常时 fail fast
  - 新增基础 Tool：`read_file`、`ast_parse`、`search_pattern`，支持文件读取、Java AST 符号提取和仓库内模式搜索
  - `codepilot-cli` 新增 `LocalReviewRunner`，能从 unified diff 构造最小 `ContextPack`，驱动单 `security-reviewer` 走完 review 闭环
  - `CodePilotCli` 落地 `review --diff ... [--repo ...] [--response-file ...]` 命令，支持脚本化 LLM 响应做离线演示，也保留 OpenAI-compatible live mode 接口
  - 新增 P3 定向测试，覆盖 native function calling / prompt-driven tool call 解析、基础 Tool 执行、单 Agent Loop 和 CLI 最小链路
- 验收结果
  - `2026-04-23` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=ToolCallParserTest,ToolExecutorTest,ReviewEngineTest" test`，4 个 P3 core 测试通过
  - `2026-04-23` 执行 `.\mvnw.cmd -pl codepilot-cli -am "-Dtest=LocalReviewRunnerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，CLI 最小链路测试通过

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
  - `DONE`
- 实际产出
  - `codepilot-core` 新增 `domain/context` 正式边界：`ContextCompiler`、`CompilationStrategy`、`AstParser`
  - 新增 `application/context` 的 `DiffAnalyzer`、`ImpactCalculator`、`DefaultContextCompiler`，把 P4 编译链路收敛到 `core`
  - 新增 `infrastructure/context` 的 `JavaParserAstParser` 与 `ClasspathCompilationStrategyLoader`，支持 JavaParser 解析和 `REGEX_TEXT_ANALYSIS` 降级
  - 新增编译 profile `codepilot-core/src/main/resources/compilation-profiles/java-springboot-maven.json`
  - `LocalReviewRunner` 改为复用 `ContextCompiler` 产出 `ContextPack`，移除 P3 中内嵌的临时 diff/context 拼装逻辑
  - 新增 P4 定向测试，覆盖 diff 分析、AST 解析/降级、影响范围计算、ContextPack 组装，以及 CLI 对新编译链路的消费
- 验收结果
  - `2026-04-23` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=DiffAnalyzerTest,JavaParserAstParserTest,ImpactCalculatorTest,DefaultContextCompilerTest" test`，5 个 P4 定向测试全部通过
  - `2026-04-23` 执行 `.\mvnw.cmd -pl codepilot-cli,codepilot-core -am "-Dtest=DiffAnalyzerTest,JavaParserAstParserTest,ImpactCalculatorTest,DefaultContextCompilerTest,LocalReviewRunnerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P4 核心测试和 CLI 链路测试全部通过
  - `2026-04-23` 执行 `.\mvnw.cmd compile`，多模块编译通过
  - `2026-04-23` 执行 `.\mvnw.cmd test`，全量 21 个测试通过
  - `2026-04-23` 执行 `git diff --check`，检查通过

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
  - `DONE`
- 实际产出
  - `codepilot-gateway` 新增 `WebhookReceiver`、`SseController`、`ReviewApiController`，打通 GitHub Webhook 接收、SSE 订阅和 Review 查询/报告接口
  - 新增 `GitHubWebhookVerifier`、`GitHubPullRequestClient`、`GitHubCommentWriter`，支持 HMAC 校验、PR diff/文件内容拉取，以及 PR summary comment + inline comment 回写
  - 新增 `RedisWebhookDeduplicator` + `RedisStreamReviewEventBuffer`，实现同一 `repo + pr + headSha` 去重和 Redis Stream 事件缓冲
  - 新增 `GitHubWebhookIntakeService` + `GitHubReviewWorker`，把 Webhook 事件接到现有单 `ReviewAgent` 主链，复用 `ContextCompiler`、`ReviewEngine`、基础 Tool 完成最小闭环
  - `codepilot-core` 新增 `InMemoryReviewSessionRepository` 作为当前 Gateway 运行时会话存储，并补齐 `SessionEvent` 的 task/finding 事件类型
  - 修正 `MybatisReviewSessionRepository` 的事件保存语义，保证 `append` 的 session 事件不会被后续 `save` 覆盖
  - 新增 P5 定向测试，覆盖 Webhook 接收、Query API、GitHub diff 拉取、comment 回写、去重入队，以及 worker 端到端执行
- 验收结果
  - `2026-04-23` 执行 `.\mvnw.cmd -pl codepilot-core,codepilot-gateway -am "-Dtest=MybatisReviewSessionRepositoryTest,WebhookReceiverTest,ReviewApiControllerTest,GitHubCommentWriterTest,GitHubPullRequestClientTest,GitHubWebhookIntakeServiceTest,GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P5 定向测试全部通过

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
  - `DONE`
- 实际产出
  - `codepilot-core` 升级 `ToolExecutor`：按 `readOnly/exclusive` 分区执行，只读 Tool 并行、互斥 Tool 串行；同签名调用复用缓存结果；单次 Tool 调用支持超时失败回写
  - 新增 Git Tool：`GitBlameTool`、`GitLogTool`、`GitDiffContextTool`，基于 JGit 读取 blame、文件历史和带上下文 diff
  - 新增 AST Tool：`AstFindReferencesTool`、`AstGetCallChainTool`，复用现有 `AstParser` 和轻量仓库 AST 索引完成类型/方法引用与调用链查询
  - 新增 `MemorySearchTool`，最小复用 `ProjectMemoryRepository` 按关键词读取 `ReviewPattern` / `TeamConvention`，未提前引入完整 `MemoryService`
  - `codepilot-cli` 与 `codepilot-gateway` 已接入新的 Tool 注册列表，保持现有 `ReviewEngine` / `ToolRegistry` 主链不变
  - 新增 P6 定向测试，覆盖并发分区、缓存、超时、Git Tool、AST Tool、`memory_search`，并更新 Gateway/CLI 受影响链路测试
- 验收结果
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=ToolExecutorTest,GitRepositoryToolsTest,AstNavigationToolsTest,MemorySearchToolTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P6 core 定向测试 9 项全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-cli,codepilot-gateway -am "-Dtest=LocalReviewRunnerTest,GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，受影响 CLI / Gateway 链路测试全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd test`，全仓 36 个测试全部通过
  - `2026-04-24` 执行 `git diff --check`，检查通过（仅提示工作区 LF/CRLF 转换警告，无格式错误）

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
  - `DONE`
- 实际产出
  - `codepilot-core` 新增 `ContextGovernor`，落地 `Microcompact + HistorySnipping + OrphanCleanup` 三段式消息治理；优先压缩旧 Tool 结果，再按轮次裁剪历史，最后清理失配消息
  - `codepilot-core` 新增 `LoopDetector`，先基于连续重复 Tool signature 做 pattern 检测，并预留 `LoopJudge` 小模型判定接入点，当前默认只走 pattern 模式
  - `ReviewEngine` 升级为治理版循环：每轮 LLM 调用前执行 token 预算检查和上下文压缩；连续重复 Tool 调用或预算无法收敛时返回 `partial=true` 的降级结果；同时累积并去重 partial findings，避免循环时重复上报
  - `ReviewEngine` 追加标准化的 assistant/tool 历史消息格式，保证 `ContextGovernor` 和 `LoopDetector` 能基于统一消息协议工作，不提前引入 P8 的多 Agent / NEED_CONTEXT 分支
  - 新增 P7 定向测试：`ContextGovernorTest`、`LoopDetectorTest`、`ReviewEngineTest` 扩展，覆盖旧 Tool 结果压缩、历史轮次裁剪、孤儿消息清理、预算治理和 loop 降级返回 partial findings
- 验收结果
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=ContextGovernorTest,LoopDetectorTest,ReviewEngineTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P7 review 核心定向测试 8 项全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-cli,codepilot-gateway -am "-Dtest=LocalReviewRunnerTest,GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，受影响 CLI / Gateway 链路测试全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd test`，全仓 43 个测试全部通过
  - `2026-04-24` 执行 `git diff --check`，检查通过（仅提示工作区 LF/CRLF 转换警告，无格式错误）

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
  - `DONE`
- 实际产出
  - `codepilot-core` 新增 `PlanningAgent`，基于现有 `DiffAnalyzer` 做规则驱动规划：从 diff 生成 `ReviewPlan`，稳定产出 `SECURITY / PERF / STYLE / MAINTAIN` 四类任务，并按变更路径、内容信号和 diff 规模给出策略、优先级、`focusHints` 与基础依赖
  - `codepilot-core` 新增 `ReviewerPool`、`ReviewOrchestrator`、`MergeAgent`，在不重造 Agent Loop 的前提下复用既有 `ReviewEngine`、`ContextCompiler`、`ContextGovernor`、`LoopDetector`，实现“单份 `ContextPack` + 多 reviewer + 无依赖并行/有依赖分波次执行 + merge 去重排序与低信号过滤”的 P8 最小闭环
  - `MergeAgent` 明确保留 `FINDING_REPORTED != ISSUE_CONFIRMED` 语义：只合并 `REPORTED` findings，不做确认；对重复 finding 按严重度和置信度择优，并合并证据
  - `codepilot-cli` 的 `LocalReviewRunner` 已切换到 Planning + Multi-Agent 主链，不再只跑单个 security reviewer
  - `codepilot-gateway` 的 `GitHubReviewWorker` 已切换到 Planning + Multi-Agent 主链，先落 `plan_ready` 再执行同一份 plan；SSE 和 `SessionEvent` 能真实反映多任务执行过程
  - `codepilot-core` 的 `InMemoryReviewSessionRepository` 补齐并发事件追加的线程安全，避免 P8 并行 reviewer 下丢失 `TASK_STARTED / TASK_COMPLETED / FINDING_REPORTED` 事件
  - 新增/更新 P8 定向测试：`PlanningAgentTest`、`MergeAgentTest`、`ReviewOrchestratorTest`、`LocalReviewRunnerTest`、`GitHubReviewWorkerTest`；同时同步修正 `ReviewEngineTest` 的预算治理断言，使其对齐 P8 后更长的 reviewer prompt 与 compact 协议
- 验收结果
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=PlanningAgentTest,MergeAgentTest,ReviewOrchestratorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P8 core 定向测试全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-cli,codepilot-gateway -am "-Dtest=LocalReviewRunnerTest,GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，CLI / Gateway 受影响链路测试全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd test`，全仓 46 个测试全部通过

### P9 Memory 系统

- 目标
  - 在不提前实现 P10 / P11 / P12 的前提下，把 Project Memory 检索和最小可用的 Memory 注入接到现有 P8 主链。
  - 本阶段只完成 review 过程对项目记忆的最小闭环，不提前落 Dream 沉淀、Global Knowledge 或 PgVector 完整能力。
- 计划产出
  - `MemoryService` — Project Memory 相关 recall（基于 diff / impact / token budget）
  - Memory 注入 Context 编译流程
  - Reviewer Prompt 消费 `ReviewPattern` / `TeamConvention`
  - Gateway / CLI 复用新的最小 Memory 编译链路
- 优先级
  - `P1`
- 当前状态
  - `DONE`
- 实际产出
  - `codepilot-core` 新增 `application/memory/MemoryService`，在不引入 PgVector 和 Dream 的前提下，基于 `DiffSummary + ImpactSet + rawDiff + token budget` 从现有 `ProjectMemory` 聚合中召回相关 `ReviewPattern / TeamConvention`
  - `DefaultContextCompiler` 接入 `MemoryService`，把 Project Memory recall 正式纳入 Context 编译链路；当前只注入相关记忆而不是整包聚合，并把记忆内容纳入 `TokenBudget` 使用量估算
  - `ReviewPromptTemplates` 升级为显式向 reviewer 注入 `Project memory patterns` 和按任务类型筛选后的团队规范，保持 `FINDING_REPORTED != ISSUE_CONFIRMED` 语义不变
  - `codepilot-gateway` 与 `codepilot-cli` 已切换到新的 Memory-aware `DefaultContextCompiler` 构造方式，P8 的 Planning → Multi-Agent Review → Merge 主链保持不变
  - 新增 P9 定向测试：`MemoryServiceTest`、`DefaultContextCompilerTest` 扩展、`GitHubReviewWorkerTest` 扩展；覆盖相关记忆召回、ContextPack 注入和 Gateway 主链下 reviewer prompt 实际消费项目记忆
- 验收结果
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-core,codepilot-gateway -am "-Dtest=MemoryServiceTest,DefaultContextCompilerTest,GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P9 定向测试全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd test`，全仓 47 个测试全部通过
  - `2026-04-24` 执行 `git diff --check`，检查通过（仅提示工作区 LF/CRLF 转换警告，无格式错误）

### P10 Eval Center

- 目标
  - 在 `codepilot-eval` 中建立最小可运行评测主链：`EvalScenario` 加载 → `EvalRunner` 调用现有 review pipeline → `Scorecard` 输出。
  - 评测对象对齐当前已实现主链：Planning → Context Compiler → Multi-Agent Review → Merge；Memory 只接入 P9 已有的最小 recall / injection。
  - 不提前实现 baseline 对比、可视化、Dream 沉淀或 Global Knowledge / PgVector 完整能力。
- 计划产出
  - `EvalScenario` / `EvalScenarioLoader`
  - `EvalRunner` — 端到端评测运行器
  - `Scorecard` 生成
  - 资源化最小场景 pack（若干高信号真实场景）
  - P10 定向测试与端到端验证
- 优先级
  - `P1`
- 当前状态
  - `DONE`
- 实际产出
  - `codepilot-eval` 新增 `EvalScenario`、`EvalScenarioLoader`、`EvalRunner`、`Scorecard`，落地最小可运行 Eval Center 闭环：场景加载、临时 fixture repo 物化、复用现有 `ReviewOrchestrator` 主链跑评测、汇总 scorecard
  - `EvalRunner` 直接复用现有 `PlanningAgent`、`DefaultContextCompiler`、`ReviewEngine`、`ReviewerPool`、`MergeAgent`，不额外发明平台化抽象；当前评测工具集刻意收敛到文件 / AST / Memory 工具，避免在没有真实 Git 历史的 fixture 上伪造 git 能力
  - 新增 `codepilot-eval/src/main/resources/eval/scenarios/minimal-scenario-pack.json`，提供 4 个高信号真实场景：SQL 注入、循环内仓储调用、依赖 P9 Project Memory 注入的 token guard 模式，以及一个无问题安全重构场景
  - 新增 `EvalScenarioLoaderTest`、`EvalRunnerTest`、`ScorecardTest`，覆盖场景 pack 加载、scorecard 统计，以及 `Planning → Context Compiler → Multi-Agent Review → Merge` 的端到端评测主链
  - 为了让全仓验证稳定，通过最小改动把 `codepilot-cli` 的 `LocalReviewRunnerTest` 采样容器改为线程安全实现，修复并发 reviewer 下偶发丢 task type 记录的测试抖动，不影响产品逻辑
- 验收结果
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-eval -am "-Dtest=EvalScenarioLoaderTest,ScorecardTest,EvalRunnerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P10 定向测试全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd test`，全仓 50 个测试全部通过
  - `2026-04-24` 执行 `git diff --check`，检查通过（仅提示工作区 LF/CRLF 转换警告，无格式错误）

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
  - `DONE`
- 实际产出
  - `codepilot-core` 新增 `application/session/SessionStore`，在现有 `ReviewSessionRepository + SessionEvent` 基础上落地最小恢复闭环：优先从 checkpoint session 恢复，再回放 task/finding 事件重建 `ReviewPlan.taskGraph` 和已完成 task 的 `ReviewResult`
  - `ReviewSession.initialize` 的 `SESSION_CREATED` 事件补齐 `projectId / prNumber / prUrl` 元数据，使 session 在没有 checkpoint 时也能从事件恢复到最小可运行阶段状态
  - `ReviewOrchestrator` 新增带 `seedResults` 的执行入口，支持把已完成 task 的回放结果直接带入 merge，避免 review 恢复时把已完成 task 全量重跑
  - `codepilot-gateway` 的 `GitHubReviewWorker` 已切换为按 session 当前阶段恢复：`IDLE/PLANNING` 继续 planning，`REVIEWING` 只执行剩余 task，`MERGING/REPORTING` 直接基于 checkpoint 或事件回放结果继续推进
  - `FINDING_REPORTED` 事件已补齐完整 finding 载荷，保证中断后不仅能恢复正确阶段，还能把已完成 reviewer 的 finding 带入后续 merge / report
  - 新增 P11 定向测试：`SessionStoreTest` 覆盖 checkpoint + event replay 和 event-only restore，`GitHubReviewWorkerTest` 覆盖 `REVIEWING` 阶段中断后只续跑剩余 task 的恢复链路
  - 本阶段保持现有 task timeout 运行语义不变，没有为了“恢复”重写 `ReviewEngine / ReviewOrchestrator` 的超时分支
- 验收结果
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=SessionStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P11 core 恢复测试通过
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-gateway -am "-Dtest=GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，Gateway worker 恢复测试通过
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-core,codepilot-gateway -am "-Dtest=SessionStoreTest,ReviewSessionTest,MybatisReviewSessionRepositoryTest,ReviewOrchestratorTest,GitHubReviewWorkerTest,GitHubWebhookIntakeServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，受影响 core / gateway 定向验证全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd test`，全仓 53 个测试全部通过
  - `2026-04-24` 执行 `git diff --check`，检查通过（仅提示工作区 LF/CRLF 转换警告，无格式错误）

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
  - `DONE`
- 实际产出
  - `codepilot-mcp-server` 已从空壳模块收敛为最小可运行 MCP Server：新增 `CodePilotMcpServerApplication`、`CodePilotMcpServerFactory`，基于官方 MCP Java SDK `stdio` transport 暴露 CodePilot 能力，不重写现有 review 主链
  - 新增 `McpReviewService`，在 `mcp-server` 模块内最小复用现有 `PlanningAgent -> ContextCompiler -> Multi-Agent Review -> Merge` 链路；`review_diff` 直接消费本地 `repo_root + raw_diff`，`review_pr` 通过最小 GitHub PR 读取器拉 diff / head sha / 文件快照后落临时工作区再复用同一 review 主链
  - 新增 MCP 工具 handler：`review_diff`、`review_pr`、`search_memory`；三者都提供显式参数校验、稳定结构化输出和 `isError=true` 的可定位错误结果，不提前引入平台化抽象或额外插件层
  - `search_memory` 已对齐 P9 的最小 Project Memory 能力：基于现有 `ProjectMemoryRepository` 聚合读取 `ReviewPattern / TeamConvention`，返回稳定的结构化 match 列表和文本摘要
  - 新增最小 GitHub PR 读取适配 `com.codepilot.mcp.review.GitHubPullRequestClient`，保持 `mcp-server -> core` 依赖方向不变，不反向依赖 `gateway`
  - 新增 `logback.xml`，把 MCP Server 运行日志强制打到 `stderr`，避免 `stdio` 协议流被日志污染
  - 新增 P12 定向测试：`ReviewDiffToolHandlerTest`、`SearchMemoryToolHandlerTest`、`McpReviewServiceTest`、`GitHubPullRequestClientTest`、`CodePilotMcpServerIntegrationTest`，覆盖参数校验、结果结构、GitHub PR 读取和 `stdio` MCP 工具真实可调用闭环
- 验收结果
  - `2026-04-24` 执行 `mvn -pl codepilot-mcp-server -am "-Dtest=GitHubPullRequestClientTest,McpReviewServiceTest,ReviewDiffToolHandlerTest,SearchMemoryToolHandlerTest,CodePilotMcpServerIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P12 定向测试 7 项全部通过
  - `2026-04-24` 执行 `mvn test`，全仓 60 个测试全部通过，`core / gateway / eval / mcp-server / cli` 无回退
  - `2026-04-24` 执行 `git diff --check`，检查通过（仅提示 LF/CRLF 转换警告，无格式错误）

### P13 评测与量化

- 目标
  - 在现有 `codepilot-eval` 基础上，把 P10 的最小 Eval Center 收敛成 P13 的最小可交付评测闭环。
  - 补齐 baseline 对比、稳定 scorecard / report 输出、可执行评测入口和验证链路。
- 计划产出
  - Baseline 对比实验结果
  - 更完整的 Scorecard / Report 输出
  - 可执行评测入口
  - 统计正确性、输出稳定性、错误可定位的测试
- 优先级
  - `P1`
- 当前状态
  - `DONE`
- 实际产出
  - `codepilot-eval` 新增 `EvalBaseline`、`EvalBaselineReviewer`、`EvalSuiteRunner`、`EvalSuiteResult`，在不重写现有 review 主链的前提下，把评测收敛为 “`CodePilot / Direct LLM / Full Context LLM`” 三路最小 baseline 对比闭环；其中 `CodePilot` 仍然复用现有 `Planning -> Context Compiler -> Multi-Agent Review -> Merge`
  - `EvalRunner` 升级为支持按 baseline 运行场景，保留原有 `run(List<EvalScenario>)` 语义不变；补充 `fullContextTokens`、`tokenEfficiency` 和带 `baseline + scenarioId` 的错误信息，便于定位统计异常和运行失败
  - `Scorecard` 升级为输出更完整的量化指标：除现有 precision / recall / F1 / falsePositiveRate / endToEndSuccessRate 外，新增 `avgFullContextTokens`、`avgTokenEfficiency`，便于对比上下文编译收益
  - `EvalScenarioLoader` 补齐文件系统场景包加载，支持评测入口从 classpath resource 或外部 JSON pack 读取场景，不局限于内置 `minimal-scenario-pack`
  - 新增 `EvalReportWriter`，将评测结果稳定落盘为 `report.json + report.md`；Markdown 报告包含 scorecard 汇总、baseline delta 和 scenario matrix，可直接作为后续简历 / 面试材料的原始数据来源
  - 新增 `CodePilotEvalCli` 并为 `codepilot-eval` 配置 Spring Boot 可执行主类，支持本地运行 `java -jar codepilot-eval\\target\\codepilot-eval-0.1.0-SNAPSHOT.jar run ...` 生成评测报告
  - 新增 P13 定向测试：`EvalSuiteRunnerTest`、`EvalReportWriterTest`、`CodePilotEvalCliTest`，并扩展 `EvalRunnerTest`、`EvalScenarioLoaderTest`、`ScorecardTest`；覆盖 baseline 对比、报告输出、CLI 入口、文件场景包加载和错误定位
  - 本阶段刻意没有把 `Lint Only`、Dream、Global Knowledge、PgVector 完整检索混入 P13，保持评测能力与当前仓库真相一致，避免为了“看起来完整”引入假能力
- 验收结果
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-eval -am "-Dtest=EvalRunnerTest,EvalScenarioLoaderTest,ScorecardTest,EvalSuiteRunnerTest,EvalReportWriterTest,CodePilotEvalCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，P13 定向测试 8 项全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd test`，全仓 64 个测试全部通过，`core / gateway / eval / mcp-server / cli` 无回退
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-eval -am package -DskipTests`，成功生成可执行 `codepilot-eval-0.1.0-SNAPSHOT.jar`
  - `2026-04-24` 执行 `git diff --check`，检查通过（仅提示 LF/CRLF 转换警告，无格式错误）

### P14 Dream 最小记忆沉淀闭环

- 目标
  - 让 `REVIEW_COMPLETED != MEMORY_UPDATED` 从设计约束落到真实代码：review 完成后独立执行 Dream，分析 `ReviewResult` 并回写 `ProjectMemoryRepository`
  - 保持现有 `Planning -> Context Compiler -> Multi-Agent Review -> Merge -> Report` 主链不重写，只在 review/report 成功后追加最小 Dream 后处理
- 优先级
  - `P1`
- 当前状态
  - `DONE`
- 实际产出
  - `codepilot-core` 新增 `domain/memory/MemoryPlan` 与 `application/memory/DreamService`，落地最小 Dream 两阶段闭环：Phase 1 从 `ReviewResult` 中筛选高置信度、高信号 finding，生成待沉淀的 `ReviewPattern`；Phase 2 将其幂等并入 `ProjectMemory`
  - `ProjectMemoryRepository` 新增 `load(projectId)` 便捷读取语义，避免 Dream / CLI / Gateway 在应用层重复拼装 “find or empty” 胶水代码
  - `DreamService` 当前对重复 Dream 做最小幂等保护：同一 pattern 在 `lastSeenAt` 未推进时不会重复插入或重复累计频次；仓储保存失败时会带 `projectId + sessionId` 上下文抛出异常
  - `codepilot-gateway` 的 `GitHubReviewWorker` 已在 report 完成并标记 `DONE` 后独立触发 Dream；Dream 失败只记日志，不会把已完成 review 反向改成 `FAILED`
  - `codepilot-cli` 的 `LocalReviewRunner` 已复用同一 `DreamService`，并以内存版 `ProjectMemoryRepository` 保存本地会话的最小沉淀结果，避免在 CLI 中重新实现一套 Dream 逻辑
  - 新增/扩展测试：`DreamServiceTest` 覆盖成功沉淀、空沉淀、低置信度过滤、重复 Dream 幂等、保存失败上下文；`GitHubReviewWorkerTest` 覆盖 Gateway 成功沉淀与 Dream 失败不回滚 review；`LocalReviewRunnerTest` 覆盖 CLI review 结束后的 memory 写回
- 验收结果
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-core "-Dtest=DreamServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，Dream 核心定向测试 5 项全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-gateway -am "-Dtest=GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，Gateway Dream 接入与失败语义测试 3 项全部通过
  - `2026-04-24` 执行 `.\mvnw.cmd -pl codepilot-cli -am "-Dtest=LocalReviewRunnerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`，CLI Dream 接入测试通过
  - `2026-04-24` 执行 `.\mvnw.cmd test`，全仓 71 个测试全部通过，`core / gateway / eval / mcp-server / cli` 无回退
  - `2026-04-24` 执行 `git diff --check`，检查通过（仅提示 LF/CRLF 转换警告，无格式错误）

---

## 延期清单

以下能力当前明确不在主链范围内，避免把它们重新混进开发：

1. **前端 UI** — MVP 用 CLI + PR Comment，前端不阻塞核心功能
2. **多语言支持** — 先做 Java，其他语言通过 compilation profile 扩展
3. **代码自动修复** — Agent 只做审查，不做代码生成/修改
4. **IDE 插件** — 先不做 VS Code / JetBrains 插件
5. **自托管模型** — 先用云端 API，本地模型是后续优化方向
6. **多 Git 平台** — 先做 GitHub，GitLab 等通过 adapter 扩展
