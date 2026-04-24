---
name: codepilot-review-scenario-author
description: 创建 CodePilot review 评测场景包和配套 fixture 仓库。当需要新增一个端到端 review 评测场景、为某个 Finding 类别补 ground truth、或验证 Context 编译/Planning/Memory 在特定 diff 模式下是否正常工作时使用。
---

# CodePilot Review 场景包编写 Skill

## 解决什么问题

CodePilot 的评测不是"给个 diff 跑一遍看结果"——场景包要精确控制**输入（什么 diff）、期望（什么 finding）、约束（什么策略/预算/Reviewer）**，才能区分"Agent 发现了"和"Agent 碰巧报了"。

场景包的核心挑战：

1. **ground truth 要精确到行号**——否则无法区分"Agent 报了同类问题但行号偏了"算不算命中
2. **场景要能区分 Agent 各模块的职责**——同一个 SQL 注入漏报，根因可能是 Context 编译没拉到那行代码，也可能是 Security Reviewer prompt 不够敏锐
3. **NO_ISSUE 场景和 ISSUE 场景同样重要**——只测"能不能发现问题"不测"会不会误报"，precision 永远是盲区

## 场景包结构

每个场景包是一个 JSON 文件，放在 `codepilot-eval/src/test/resources/evaluation/scenarios/` 下。

### 必须定义的字段

```json
{
  "scenarioId": "security-sql-injection-001",
  "name": "动态拼接 SQL 导致注入",
  "description": "UserService 中通过字符串拼接构造 SQL 查询，用户输入直接嵌入 WHERE 子句",

  "fixture": {
    "repoType": "local",
    "repoPath": "repo-fixtures/java-springboot-user-service/",
    "baseBranch": "main",
    "diffFile": "diffs/sql-injection-dynamic-query.diff"
  },

  "groundTruth": [
    {
      "id": "gt-001",
      "file": "src/main/java/com/example/UserService.java",
      "line": 42,
      "severity": "CRITICAL",
      "category": "SECURITY",
      "title": "SQL Injection via String Concatenation",
      "codeSnippet": "String query = \"SELECT * FROM users WHERE id = \" + userId;"
    }
  ],

  "expectations": {
    "reviewStrategy": "SECURITY_FIRST",
    "reviewerTypes": ["SECURITY"],
    "minIterations": 1,
    "maxIterations": 8,
    "contextFiles": ["UserService.java", "UserRepository.java"]
  },

  "stopPolicy": {
    "maxTimeMinutes": 5,
    "maxIterations": 15,
    "maxTokenBudget": 8000
  },

  "scoringOverrides": {}
}
```

### 字段说明

| 字段 | 为什么需要 |
|------|-----------|
| `fixture.diffFile` | 精确控制输入：同一个 fixture repo 可以有多个 diff，测不同变更模式 |
| `groundTruth.codeSnippet` | 让 eval runner 能做"近似匹配"——Agent 报的行号如果偏了几行，但代码片段一致，算部分命中 |
| `expectations.reviewStrategy` | 验证 Planner 是否选对了策略；如果选了 QUICK_SCAN 就是 Planning 模块的问题 |
| `expectations.contextFiles` | 验证 Context 编译是否拉到了关键文件；如果没拉到就是 ContextCompiler 的问题 |
| `expectations.minIterations` | 防止 Agent "秒报"——0 次迭代就报 finding 说明可能是 prompt 硬编码，不是真的推理 |
| `scoringOverrides` | 不同场景的评分权重可以不同（如 NO_ISSUE 场景 precision 权重更高） |

## 场景分类

每类场景验证不同的 Agent 能力模块：

| 分类 | 数量 | 主要验证模块 |
|------|------|-------------|
| `SECURITY` | 10 | Security Reviewer prompt + Context 编译是否拉到安全敏感代码 |
| `PERFORMANCE` | 10 | Perf Reviewer prompt + 是否识别出 N+1/资源泄漏等模式 |
| `STYLE` | 8 | Style Reviewer prompt + 是否识别命名/日志/异常规范 |
| `MAINTAINABILITY` | 8 | Maintain Reviewer prompt + 是否识别上帝类/深层嵌套/重复代码 |
| `NO_ISSUE` | 5 | **全体 Reviewer 的误报控制** + Planner 是否选了 QUICK_SCAN |
| `LARGE_PR` | 3 | **Planner 拆分策略** + **Context 编译 token 预算** + 是否按模块拆 ReviewTask |
| `CROSS_FILE` | 3 | **Context 编译的依赖追踪** + **AST 工具的调用链查询** |

## 场景编写流程

1. 先确定要验证什么（哪个 Agent 模块的什么能力）
2. 选择或创建 fixture repo
3. 编写 diff 文件（精确控制变更内容）
4. 编写 ground truth（精确到文件名 + 行号 + 代码片段）
5. 设定 expectations（预期策略、Reviewer 类型、必须拉到的上下文文件）
6. 设定 stop policy
7. 手动跑一遍确认 ground truth 正确

## Guardrails

1. **ground truth 必须精确到行号**——"大概在这个方法里"不算，eval runner 无法自动匹配
2. **diff 必须真实可 apply**——不能手写一个语法错误的 diff
3. **NO_ISSUE 场景不能有任何 ground truth finding**——否则就不是 NO_ISSUE 了
4. **不要为了"让场景更容易通过"而简化 diff**——评测的意义是暴露问题
5. **fixture repo 不要和另一个场景共享同一个 diff 文件**——每个场景的 diff 应该独立，避免修改一个影响另一个
6. **不要手写 Agent 输出作为 expected output**——我们验证的是 finding 的 file/line/severity，不是 Agent 的回复原文

## 验证

写完场景包后：

1. `mvnw -q -DskipTests test-compile` 编译通过
2. diff 文件能正确 apply 到 fixture repo
3. ground truth 中的行号和 fixture repo 的 base branch 代码一致
4. 如果有 LLM 环境，跑一遍 eval，确认 scorecard 能正常生成
