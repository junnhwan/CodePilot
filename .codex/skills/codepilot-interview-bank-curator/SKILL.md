---
name: codepilot-interview-bank-curator
description: 维护 CodePilot 面试题库。当需要把讨论中产生的面试题归档、按 Agent 工程模块分组整理面试题、或确保面试回答和当前实现真相一致时使用。
---

# CodePilot 面试题库维护 Skill

## 解决什么问题

CodePilot 作为简历项目，面试时会被从不同角度问：

1. **Agent 工程概念层**："ReAct 是什么？你的 Agent Loop 怎么实现的？"
2. **项目设计层**："为什么 Context 编译是第一公民？和直接塞文件有什么区别？"
3. **权衡取舍层**："为什么手搓 Agent Loop 不用 LangChain4j？手搓的代价是什么？"
4. **量化验证层**："你的评测数据说明了什么？哪些地方还有提升空间？"

面试题库的核心挑战：**不能只背概念，必须用 CodePilot 的真实设计来回答**，否则面试官一追问就露馅。

## 固定存储规则

面试题存放在：

1. `docs/interview/README.md` — 题库索引
2. `docs/interview/*.md` — 按主题分组的题目文件

**不要把面试回答混入架构/运行时真相文档**，除非用户明确要求。

## Minimal loading path

先读：

1. `AGENTS.md` — 项目冻结判断和编码约束
2. `docs/interview/README.md` — 现有题库索引
3. 最相关的主题文档

只加载回答问题所需的真相文档，不要批量读整个 docs 目录。

## 分类体系

面试题按 **Agent 工程模块** 分组，每组对应一个面试可能被深挖的方向：

| 主题文件 | 覆盖的面试方向 | 对应的核心模块 |
|----------|---------------|---------------|
| `agent-loop-and-react.md` | ReAct 循环、Tool 调用、Loop Detection、Context Governance | `ReviewEngine`, `LoopDetector`, `ContextGovernor` |
| `context-compilation.md` | 上下文编译、Token 预算、AST 策略、文件优先级 | `ContextCompiler`, `AstParser`, `CompilationProfile` |
| `multi-agent-and-planning.md` | 多 Agent 编排、Planning 策略、任务 DAG、并行调度 | `PlanningAgent`, `Orchestrator`, `MergeAgent` |
| `memory-and-dream.md` | 三层记忆、Dream 沉淀、向量检索 + 规则匹配、跨 PR 学习 | `MemoryService`, `DreamProcessor`, `ProjectMemory` |
| `evaluation-and-metrics.md` | 评测体系、Scorecard、Baseline 对比、误报/漏报归因 | `EvalRunner`, `Scorecard`, `EvalScenario` |
| `system-design-and-tradeoffs.md` | 为什么手搓、为什么只读审查、架构取舍、与竞品对比 | 全局架构决策 |

## 条目格式

1. **不用数字编号**——避免"第 3 题"这种引用方式，题目可能增删，编号会乱
2. **用 heading 格式**：`### [重要程度：高|中|低] 问题内容`
3. **每个问题独立一个 section**
4. **回答必须结合 CodePilot 真实设计**，不能只给通用概念

## 回答质量标准

每个回答必须做到：

### 1. 先说 CodePilot 怎么做的，再说通用概念

- 差："ReAct 是 Observation-Thought-Action 循环……"
- 好："CodePilot 的 ReviewEngine 实现了 ReAct 循环：Agent 先观察 diff 和 ContextPack，推理潜在问题，调用 AST/Git 工具验证假设，产出 finding 或继续迭代。和通用 ReAct 的区别是……"

### 2. 说清楚 why，不只是 what

- 差："Context 编译按文件优先级排序。"
- 好："Context 编译按文件优先级排序，因为 token 有限——一个 50 文件的 PR 如果全塞进去，关键变更文件的上下文会被无关代码稀释。优先级保证变更文件和直接调用方先占预算，间接依赖按剩余预算分配。"

### 3. 区分当前实现和目标设计

- 当前已经手搓实现了 `ReviewEngine` 和 `LoopDetector` → 标记为"已实现"
- Memory 的 Dream 沉淀还在 P9 阶段 → 标记为"目标设计，尚未实现"
- 面试时说错状态是大忌——面试官问"你 Dream 沉淀实现了吗"你说实现了但代码里没有，直接扣分

### 4. 主动暴露权衡和局限

- "手搓 Agent Loop 的代价是兼容性——不支持 Function Calling 的模型需要 Prompt 驱动模式，解析准确率低于原生 Function Calling"
- "Context 编译的 AST 策略目前只支持 Java，其他语言需要 Regex fallback，精度下降"

这种主动暴露局限反而显得你理解深入。

## Hard guardrails

1. **不要把未实现的功能写成已实现**——这是面试题库，不是营销文档
2. **不要在架构真相文档里混入面试风格解释**——真相文档是开发用的，面试题库是面试用的
3. **不要用数字编号**——用重要程度标签
4. **不要遗漏重要程度标签**——面试前优先复习"高"的题
5. **不要创建重复的主题文件**——先检查是否已有同类主题
