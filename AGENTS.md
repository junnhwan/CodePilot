# CodePilot Development Guide

本文件是项目内的开发规范入口，目标只有两个：

1. 让后续编码始终围绕当前固定架构推进，不长出胶水代码森林。
2. 让新开工的 AI coding agent 能快速找到真相来源、代码落点和编码约束。

---

## 1. 项目定位

这是面向真实研发场景的 AI Code Review Agent——不是框架，不是平台，是一个有明确应用场景的 Agent 工程。

当前已冻结的核心判断：

1. **只读审查，不做代码生成**。Agent 只有建议权，没有修改权。
2. **固定 Review Pipeline**：`Diff接收 → Planning → Context编译 → Multi-Agent Review → Merge → Report → Memory沉淀`。
3. **手搓 Agent 内核**，不在 Agent Loop、Context 编译、Tool 调度、Memory 管理上用框架高层封装。
4. **上下文编译是第一公民**。不是"塞文件"，是 AST 感知的动态编译。
5. **Memory 不是 RAG 的换皮**。三层记忆 + Dream 沉淀 = 跨 PR 主动学习。
6. **可评测、可量化**。评测是架构一等公民，不是事后补充。
7. **`FINDING_REPORTED != ISSUE_CONFIRMED`**。Agent 报出 finding 不等于它真的是问题，需要置信度 + 证据支撑。
8. **`REVIEW_COMPLETED != MEMORY_UPDATED`**。review 完成不等于记忆已沉淀，Dream 必须执行。

---

## 2. 真相来源与必读文档

开始动代码前，先看这些文档，不要凭印象写：

1. `01-项目概述.md`
   - 项目定位、与竞品对比、核心设计理念
2. `02-架构设计.md`
   - 系统总览、模块拆分、Agent 设计、Context 编译、Memory、Planning、Tool
3. `03-技术实现方案.md`
   - 技术栈、关键组件实现代码、接口设计、Prompt 模板
4. `04-评测与交付计划.md`
   - 评测指标、场景、Scorecard、Baseline 对比、分期计划
5. `progress.md`
   - 当前实现阶段、验收方式、最近工作
6. `db/schema/` 目录
   - 当前表结构真相

优先级规则：

1. 架构边界真相看 `02-架构设计.md`
2. 实现细节真相看 `03-技术实现方案.md`
3. 评测标准真相看 `04-评测与交付计划.md`
4. 当前阶段和进度看 `progress.md`
5. 表结构真相看 `db/schema/`

---

## 3. 项目结构索引

### `codepilot-gateway`

Web 层，只放 HTTP 接收/推送、鉴权去重。

当前目录：

1. `controller` — WebhookReceiver, SSEController, ReviewApiController
2. `config` — Security, CORS, Webhook 验签
3. `filter` — 限流、去重

### `codepilot-core`

Agent 内核，只放 Agent 逻辑和领域模型。

#### `domain/`

领域层，只放模型、状态机、不变量、领域端口。

当前切片：

1. `agent` — AgentDefinition, AgentState, AgentDecision
2. `plan` — ReviewPlan, ReviewTask, TaskGraph
3. `context` — ContextPack, ContextCompiler, CompilationStrategy
4. `memory` — ProjectMemory, ReviewPattern, TeamConvention
5. `tool` — Tool, ToolRegistry, ToolExecutor, ToolResult
6. `review` — Finding, Severity, CodeLocation, ReviewResult
7. `session` — ReviewSession, SessionEvent, SessionStore

#### `application/`

用例编排，只放业务流程编排。

#### `infrastructure/`

外部适配，只放 LLM、Git、VectorDB、AST 等外部依赖的实现。

### `codepilot-eval`

评测中心，只放评测场景、Runner、Scorecard。

### `codepilot-mcp-server`

MCP Server，把 review 能力暴露为标准 MCP 工具。

### `codepilot-cli`

CLI 模式，本地命令行跑 review。

---

## 4. 依赖与分层规则

硬规则：

1. `gateway -> core`
2. `eval -> core`（评测依赖核心模型，但核心不知道评测的存在）
3. `cli -> core`
4. `gateway <-> eval` 只能通过查询接口交互
5. `domain` 不依赖任何外部框架（Spring、Lombok 除外）
6. `application` 只编排 `domain` 对象，不引入新业务概念
7. `infrastructure` 实现 `domain` 定义的端口接口
8. **不允许把数据库表直接当跨层公共 API**

---

## 5. 编码风格核心原则

### 5.1 先保主链，再补扩展

新增代码先问自己两个问题：

1. 这是不是 Diff接收 → Planning → Review → Merge → Report → Memory 主链上的必要代码？
2. 这段代码如果不写，会不会直接挡住当前阶段目标？

如果答案都是否，就不要急着写。

### 5.2 不做占位抽象

不允许为了"未来可能会用"而引入：

1. 多余的 manager / helper / facade / adapter 层
2. 泛化过头的策略接口
3. 没有第二个实现的抽象
4. 只有转发作用的 service

判断标准：如果一个类的主要工作只是把参数搬到另一个类，不增加边界价值，就不要建。

### 5.3 不发明平行概念

当前已有核心术语：

1. `ReviewSession`
2. `ReviewPlan`
3. `ReviewTask`
4. `ReviewAgent`
5. `Finding`
6. `ContextPack`
7. `ProjectMemory`

后续不要轻易再造：

1. `Issue` / `Bug` / `Defect`（用 Finding）
2. `ReviewJob` / `ReviewMission`（用 ReviewTask）
3. `ReviewContext` / `ReviewEnvironment`（用 ContextPack）
4. `KnowledgeBase` / `RuleEngine`（用 ProjectMemory）

如果只是已有概念换个名字，就是制造混乱。

### 5.4 Agent Loop 是手搓的，不是框架给的

以下组件必须自己实现，不依赖 Spring AI / LangChain4j 高层封装：

1. `ReviewEngine`（Agent Loop：决策解析 + Tool 执行 + Loop Detection + Context Governance）
2. `ContextCompiler`（上下文编译：AST 感知 + Token 预算 + 动态拉取）
3. `ToolExecutor`（Tool 调度：并发分区 + 结果缓存 + 超时控制）
4. `MemoryService`（三层 Memory + Dream 沉淀 + 混合检索）
5. `LoopDetector`（死循环检测：pattern + LLM 双模式）

底层 LLM 调用可以用 Spring AI 的 ChatModel 做薄封装，但上面这些全部自己写。

---

## 6. 注释规范

### 6.1 应该写注释的地方

1. 状态迁移的关键约束
2. Context 编译的 token 预算分配逻辑
3. Loop Detection 的判断阈值和原因
4. 为什么这里不能直接调用另一层
5. 为什么这里必须 fail fast
6. 临时设计折中和后续替换点

### 6.2 不该写注释的地方

1. 对着代码复述"给字段赋值"
2. 每个 getter / setter / record 字段解释一遍
3. 方法名已经很清楚时再翻译一遍
4. 大段无信息量的分隔线注释

### 6.3 注释写法要求

1. 优先解释 `why` 和 `invariant`，不要只解释 `what`
2. 注释要短，能一两行说清就不要写一段
3. 注释必须和当前设计一致，设计变了要一起改

---

## 7. 反胶水代码与反 DTO 膨胀规则

### 7.1 什么叫胶水代码

1. 一层层 request -> dto -> vo -> entity -> po 来回复制同样字段
2. service A 只调 service B，不增加约束
3. mapper 之外又包一层纯搬运 converter
4. 一个业务动作拆成 4 个空心 helper 类

### 7.2 DTO 只在真实边界创建

允许创建 DTO 的地方：

1. HTTP request / response
2. 外部 LLM / Git API 的入参与回包
3. 事件总线或异步消息契约
4. 专门的查询视图对象
5. MyBatis row 读取结果在 persistence 内部的中间映射

默认不允许创建 DTO 的地方：

1. domain -> application 之间纯搬运
2. application -> infrastructure 之间纯搬运
3. 同一用例内部只为了"看起来分层"而新增 DTO
4. 为每张表各造一组 `XxxDTO / XxxVO / XxxBO`

### 7.3 判断标准

如果一个对象满足下面任一情况，就不要新建：

1. 字段和来源对象 1:1 一样
2. 只是为了"显得专业"改了类名
3. 没有独立的边界语义
4. 删除后代码会更清楚

---

## 8. 异常处理与快速失败规范

### 8.1 基本规则

1. 不允许 `catch (Exception)` 后吞掉
2. 不允许记录日志后返回 `null`
3. 不允许记录日志后假装成功继续跑
4. 不允许把真正错误偷偷降级成 boolean
5. 不允许同一个异常在多层重复打印

### 8.2 domain 层

1. 领域规则不满足时，优先抛明确的领域异常
2. 状态不变量被破坏时，直接 fail fast
3. domain 不负责吞异常，也不负责兜底恢复

### 8.3 application / infrastructure 层

1. 外部依赖失败时要补上下文再抛出
2. 异常信息里优先带这些 ID：
   - `sessionId`
   - `taskId`
   - `agentName`
3. 如果是后台执行场景，异常除了抛出，还要落成 SessionEvent

### 8.4 日志规则

1. 失败日志优先在边界层打一次
2. domain 层不做 noisy logging
3. 运行类异常要明确区分：
   - LLM 调用失败
   - Tool 执行超时
   - Context 编译 token 超限
   - Loop Detection 触发

---

## 9. 状态机实现规则

### 9.1 当前关键状态机

1. `ReviewSession`：IDLE → PLANNING → REVIEWING → MERGING → REPORTING → DONE / FAILED
2. `ReviewTask`：PENDING → READY → IN_PROGRESS → COMPLETED / SKIPPED / TIMEOUT
3. `Finding`：REPORTED → CONFIRMED / DISMISSED / DOWNGRADED

### 9.2 状态机实现约束

1. 不允许绕过聚合直接改状态字段
2. 状态迁移必须能解释触发命令、守卫条件和副作用
3. `COMPLETED` 不代表 finding 被确认，只代表 task 执行完毕
4. Agent 超时和 Loop Detection 触发都应回写到 SessionEvent

---

## 10. Git 分支与提交规则

### 10.1 分支策略

1. 每个 Phase 开一个新分支，命名格式：`phase/P1`、`phase/P2`...
2. 分支从 `main` 创建，完成后合并回 `main`
3. 合并时机由人工控制，AI 不自动 merge 回 `main`

### 10.2 AI 允许的 Git 操作

1. `git checkout -b phase/P{n}` — 创建并切换到新分支
2. `git add` + `git commit` — 提交当前 Phase 的改动
3. `git status` / `git diff` / `git log` — 查看状态
4. `git checkout main` — 切回主分支（不合并）

### 10.3 AI 禁止的 Git 操作

1. **禁止 `git merge`** — 合并由人工 review 后执行
2. **禁止 `git push`** — 不往远程推
3. **禁止 `git rebase`** — 交互式 rebase AI 搞不了
4. **禁止 `git reset --hard`** — 不可逆操作
5. **禁止 `git clean`** — 不可逆操作
6. **禁止 `git branch -D`** — 删除分支由人工决定

### 10.4 提交信息格式

```
feat(P{n}): 具体完成内容
fix(P{n}): 修复内容
docs: 文档更新
```

示例：`feat(P1): 建立 Maven 多模块骨架 + LlmClient 接口`

---

## 11. 修改前检查清单

动代码前，先逐条过一遍：

1. 这次改动属于 `gateway`、`core`、`eval` 哪一层？
2. 有没有打破"固定 Review Pipeline"的基本策略？
3. 有没有在 Agent Loop / Context 编译 / Tool 调度 / Memory 上用框架封装代替手搓？
4. 有没有新建不必要的 DTO / VO / BO / Helper？
5. 有没有写高信号注释，而不是流水账注释？
6. 异常是不是做到快速失败、带上下文、易定位？
7. 评测场景是否需要同步更新？
8. 是否更新了 `progress.md`？

## 12. 提交前检查清单

1. `git diff --check`
2. 这次新增的类，是否真的有边界价值？
3. 是否出现纯搬运方法或纯转发 service？
4. 是否新增了本该合并到现有聚合/应用服务里的碎片类？
5. 注释是否解释了关键约束而不是重复代码？
6. 异常栈里是否能直接定位到 session / task / agent？
7. `mvn test` 是否通过？
8. 是否在 `progress.md` 记录了本次完成内容？
