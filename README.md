# CodePilot

CodePilot 是一个面向真实研发场景的 AI Code Review Agent。它只做只读审查，不做代码生成；主链固定为 `Diff接收 -> Planning -> Context编译 -> Multi-Agent Review -> Merge -> Report -> Memory沉淀`。

当前仓库真相以 [AGENTS.md](AGENTS.md)、[02-架构设计.md](02-架构设计.md)、[03-技术实现方案.md](03-技术实现方案.md)、[04-评测与交付计划.md](04-评测与交付计划.md)、[progress.md](progress.md) 和 `db/schema/` 为准。

## 项目边界

- 只读审查，不做自动修复或代码生成
- Agent Loop / Context 编译 / Tool 调度 / Memory 管理全部手搓，不依赖 Spring AI / LangChain4j 高层编排
- `FINDING_REPORTED != ISSUE_CONFIRMED`：reviewer 报出 finding，不等于问题已经被系统确认
- `REVIEW_COMPLETED != MEMORY_UPDATED`：review 完成后 Dream 独立沉淀，失败不会反向伪装成 review 失败

## 当前完成度

截至当前仓库真相，主链已经推进到 `P17 DONE`：

- `codepilot-core`：Planning、Context Compiler、Multi-Agent Review、Merge、Dream、Session 恢复
- `codepilot-gateway`：GitHub Webhook 接收、SSE 进度推送、PR comment 回写、Review 查询接口
- `codepilot-cli`：本地 diff review 入口
- `codepilot-eval`：评测场景加载、4 路 baseline、JSON/Markdown 报告输出、CLI 入口
- `codepilot-mcp-server`：`review_diff` / `review_pr` / `search_memory` 三个 MCP 工具

`codepilot-eval` 当前默认使用 `expanded-scenario-pack.json`，包含 7 个场景；baseline 为 `codepilot`、`direct_llm`、`full_context_llm`、`lint_only`。

## 模块结构

```text
codepilot/
├── codepilot-core         # Agent 内核：Planning / Context / Review / Memory / Session
├── codepilot-gateway      # Web 层：Webhook / SSE / Query API / GitHub 回写
├── codepilot-cli          # 本地 diff review CLI
├── codepilot-eval         # Eval Center：场景、Runner、Scorecard、报告
├── codepilot-mcp-server   # MCP Server：review_diff / review_pr / search_memory
└── db/schema              # 当前表结构真相
```

## 快速开始

### 环境

- Java 21
- Maven Wrapper（仓库已提供 `mvnw` / `mvnw.cmd`）
- 如果要跑 live LLM：OpenAI-compatible API 地址和 Key
- 如果要跑 GitHub Webhook / PR review：GitHub token 与 webhook secret

### 基础验证

```powershell
.\mvnw.cmd test
```

## 运行方式

### 1. CLI：本地 review 一个 diff

先打包：

```powershell
.\mvnw.cmd -pl codepilot-cli -am package -DskipTests
```

live LLM 模式：

```powershell
$env:CODEPILOT_LLM_BASE_URL="https://api.openai.com/v1"
$env:CODEPILOT_LLM_API_KEY="sk-..."
java -jar codepilot-cli\target\codepilot-cli-0.1.0-SNAPSHOT.jar review --diff .\changes.diff --repo .
```

离线脚本响应模式：

```powershell
java -jar codepilot-cli\target\codepilot-cli-0.1.0-SNAPSHOT.jar review --diff .\changes.diff --repo . --response-file .\responses.json
```

### 2. Gateway：接 GitHub Webhook

先打包：

```powershell
.\mvnw.cmd -pl codepilot-gateway -am package -DskipTests
```

启动：

```powershell
$env:OPENAI_API_KEY="sk-..."
$env:GITHUB_API_TOKEN="ghp_..."
$env:GITHUB_WEBHOOK_SECRET="your-secret"
java -jar codepilot-gateway\target\codepilot-gateway-0.1.0-SNAPSHOT.jar
```

核心接口：

- `POST /api/v1/webhook/github`
- `GET /api/v1/reviews/{sessionId}`
- `GET /api/v1/reviews/{sessionId}/report`
- `GET /api/v1/reviews/{sessionId}/stream`

### 3. Eval：生成 scorecard 与报告

先打包：

```powershell
.\mvnw.cmd -pl codepilot-eval -am package -DskipTests
```

运行：

```powershell
$env:CODEPILOT_LLM_BASE_URL="https://api.openai.com/v1"
$env:CODEPILOT_LLM_API_KEY="sk-..."
java -jar codepilot-eval\target\codepilot-eval-0.1.0-SNAPSHOT.jar run --scenario-pack eval/scenarios/expanded-scenario-pack.json --baselines codepilot,direct_llm,full_context_llm,lint_only
```

输出会落到 `codepilot-eval\target\eval-reports\<run-id>\report.json` 和 `report.md`。

### 4. MCP Server：暴露 review 能力给其他 Agent

先打包：

```powershell
.\mvnw.cmd -pl codepilot-mcp-server -am package -DskipTests
```

启动：

```powershell
$env:OPENAI_API_KEY="sk-..."
java -jar codepilot-mcp-server\target\codepilot-mcp-server-0.1.0-SNAPSHOT.jar
```

当前暴露的 MCP 工具：

- `review_diff`
- `review_pr`
- `search_memory`

## 评测说明

Eval Center 当前已经具备以下能力：

- baseline 维度跑评测，而不是只跑单一路径
- 输出 `precision / recall / f1 / false_positive_rate / avg_token_efficiency`
- 输出 baseline delta 和 scenario matrix
- 同时写出 `report.json` 与 `report.md`

注意：设计文档中的示例 scorecard 数字是目标示例，不应直接当成当前仓库已经跑出的真实结果。真实指标应以你本地生成的 eval 报告为准。

## 推荐阅读

- [项目进度记录](progress.md)
- [面试故事线](docs/interview/codepilot-project-story.md)
- [评测与上下文工程复盘草稿](docs/blog/codepilot-eval-and-context-engineering.md)
