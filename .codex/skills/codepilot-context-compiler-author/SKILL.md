---
name: codepilot-context-compiler-author
description: 创建或优化 CodePilot 的上下文编译策略和 compilation profile。当需要为新的语言/框架编写编译 profile、调整 token 预算分配、或在评测报告暴露上下文质量问题后调优编译策略时使用。
---

# CodePilot 上下文编译 Profile 编写 Skill

## 解决什么问题

Context Compilation 是 CodePilot 的核心差异化——不是"把文件塞给 LLM"，而是 AST 感知的动态编译。但编译策略本身有一组互相冲突的约束：

1. **Token 预算有限**——8000 token 里要塞 diff、结构化事实、代码片段、记忆，不可能全塞
2. **不同 review 场景优先级不同**——安全 review 需要看调用链，性能 review 需要看数据访问层，style review 只看变更文件
3. **AST 解析可能失败**——Lombok、注解处理器、非标准 Java 代码都可能让 JavaParser 崩溃
4. **拉太多文件会稀释关键上下文**——50 个文件的 PR 如果全拉进来，关键变更的上下文反而被淹没

这个 skill 解决"怎么分配 token 预算、怎么选文件优先级、怎么设 AST 策略、AST 失败怎么降级"的问题。

## 两种模式

1. `create-profile` — 为新的语言/框架组合创建编译 profile
2. `optimize-compilation` — 基于评测报告调优现有 profile

## Minimal loading path

先读：

1. `02-架构设计.md` — Context Compilation 5 步编译流程
2. `03-技术实现方案.md` — ContextGovernor 实现和 token 预算分配逻辑
3. `AGENTS.md` — "上下文编译是第一公民"等冻结判断
4. 现有 compilation profiles：`codepilot-core/src/main/resources/compilation-profiles/`

再按需读 reference 文件。

## Compilation Profile 结构

```json
{
  "profileId": "java-springboot-maven",
  "language": "java",
  "framework": "spring-boot",
  "buildTool": "maven",
  "astParser": "javaparser",

  "tokenBudget": {
    "total": 8000,
    "structuredFacts": 500,
    "diffSummary": 1000,
    "impactSet": 500,
    "codeSnippets": 4000,
    "memories": 1500,
    "reserve": 500
  },

  "filePriority": [
    "changed_files",
    "direct_callers",
    "interface_implementations",
    "direct_callees",
    "configuration_files",
    "test_files",
    "indirect_dependencies"
  ],

  "astModes": {
    "changed_files": "FULL",
    "direct_callers": "METHOD_SIG",
    "interface_implementations": "SYMBOLS",
    "direct_callees": "SYMBOLS",
    "configuration_files": "REGEX_TEXT",
    "test_files": "REGEX_TEXT",
    "indirect_dependencies": "SYMBOLS"
  },

  "fallbackStrategy": "REGEX_TEXT_ANALYSIS",

  "excludePatterns": [
    "**/generated/**",
    "**/target/**",
    "**/node_modules/**"
  ]
}
```

## create-profile 模式

### Step 1: 确定语言/框架/构建工具

不要试图做一个"通用 profile"。每个 profile 必须针对具体的组合，因为：
- Spring Boot 的依赖注入模式 → 需要追踪 `@Autowired` 注入的 Bean
- MyBatis 的 SQL 映射 → 需要关联 Mapper 接口和 XML
- JPA 的 Repository → 需要追踪实体关系

### Step 2: 分配 token 预算

分配原则：
- **diffSummary 不低于 800**——diff 是 review 的核心输入，压缩太狠会丢关键变更信息
- **codeSnippets 占大头（40-50%）**——Agent 需要看代码才能判断问题
- **memories 不低于 1000**——项目记忆是 CodePilot 的差异化，不能因为 token 紧张就砍掉
- **reserve 不低于 400**——Agent Loop 的 Tool 调用结果需要空间

不同 review 策略可以有不同的预算分配：
- SECURITY_FIRST → codeSnippets 占比提升（需要看更多调用链），memories 侧重安全 pattern
- PERFORMANCE_FIRST → codeSnippets 占比提升（需要看数据访问层），memories 侧重性能反模式
- QUICK_SCAN → codeSnippets 占比下降（只看变更文件），diffSummary 占比提升

### Step 3: 设定文件优先级

优先级决定"token 不够时先砍谁"。规则：
1. 变更文件永远最优先——这是 review 的直接对象
2. 直接调用方第二——变更方法的调用者可能受影响
3. 接口实现第三——接口签名变了要看所有实现
4. 被调用方第四——变更方法调用的下游可能有问题
5. 配置文件第五——Spring 配置可能影响行为
6. 测试文件第六——验证变更是否破坏测试
7. 间接依赖最后——token 宽裕时才拉

### Step 4: 设定 AST 解析模式

| 模式 | 用途 | Token 成本 |
|------|------|-----------|
| `FULL` | 完整 AST：类结构 + 方法体 + 字段 | 高 |
| `METHOD_SIG` | 方法签名 + 返回类型 + 参数 | 中 |
| `SYMBOLS` | 类名 + 字段名 + 方法名 | 低 |
| `REGEX_TEXT` | 正则匹配（AST 失败的降级方案） | 最低 |

规则：只有 `changed_files` 用 `FULL`，其他层级用更轻的模式。

### Step 5: 设定 fallback 策略

JavaParser 失败的常见场景：
- Lombok 的 `@Data` / `@Builder` 等注解处理器生成的代码
- Java 21 新语法（record pattern、sealed class 等 JavaParser 尚未支持）
- 非标准源码路径

Fallback 策略：`REGEX_TEXT_ANALYSIS`——用正则提取方法签名和类名，精度低但不会崩。

## optimize-compilation 模式

当评测报告暴露上下文质量问题时：

### Step 1: 从 scorecard 定位问题

- `TOKEN_EFFICIENCY` 过高（>0.5）→ 拉了太多文件，需要调整 filePriority 或降低 codeSnippets 预算
- `RECALL` 低且归因 `context compilation` → 关键文件没被拉到，需要提升其优先级
- `RECALL` 低且归因 `prompt/decision` → 不是编译问题，不要改 profile

### Step 2: 从 raw-evidence 看实际 ContextPack

检查实际编译产出的 ContextPack：
- 有没有拉到 `expectations.contextFiles` 里指定的文件？
- 拉到的文件用了什么 AST 模式？信息够不够？
- token 在各 section 的实际分配比例是多少？

### Step 3: 调整 profile

只改 profile JSON，不改 Agent prompt：
- 文件没拉到 → 提升优先级或增加 codeSnippets 预算
- 文件拉到了但 AST 信息不够 → 升级 AST 模式（SYMBOLS → METHOD_SIG）
- 拉了太多无关文件 → 降低低优先级层的 AST 模式或调整 excludePatterns
- token 溢出被 ContextGovernor 裁剪了关键内容 → 调整各 section 预算比例

### Step 4: 声明验证方式

"重跑 {scenarioId}，预期 TOKEN_EFFICIENCY 从 0.45 降到 0.30，RECALL 从 0.55 升到 0.65"

## Hard guardrails

1. **不要用"加大 token budget"作为首选方案**——先检查是不是 filePriority 排错了导致拉了太多无关文件
2. **reserve 预算不能砍**——Agent Loop 的 Tool 调用结果需要空间，砍了会导致运行时溢出
3. **不要让所有文件都用 FULL AST**——8 个文件全 FULL 会直接超 token 预算
4. **不要在通用 profile 里写场景特定逻辑**——场景差异用 scoringOverrides 处理
5. **不要跳过 fallback 策略**——JavaParser 一定会遇到解析不了的情况
