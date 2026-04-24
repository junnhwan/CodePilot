# CodePilot 面试故事线

## 1. 30 秒版本

CodePilot 是我手搓的 AI Code Review Agent，目标不是做通用 Agent 平台，而是围绕真实 PR 审查场景，把 `Diff接收 -> Planning -> Context编译 -> Multi-Agent Review -> Merge -> Report -> Memory沉淀` 这条主链做扎实。项目重点不在“接了一个大模型”，而在于我自己实现了 Agent Loop、上下文编译、工具调度、评测闭环和恢复机制。

## 2. 2 到 3 分钟版本

我一开始就把边界收得很死：CodePilot 只做只读审查，不做代码生成。这样主链会更清楚，架构也能围绕 code review 的真实问题展开，比如怎么在 token 预算内给 reviewer 足够上下文、怎么避免不同 reviewer 重复报同一个问题、怎么让 session 中断后续跑而不是整条链重来。

具体实现上，我把项目拆成五个模块：`core` 负责 Planning、Context Compiler、Review Loop、Memory、Session；`gateway` 负责 Webhook、SSE 和 GitHub 回写；`cli` 负责本地 diff review；`eval` 负责场景、baseline、scorecard 和报告；`mcp-server` 负责把 review 能力暴露成 MCP 工具。

到当前仓库真相，P1 到 P17 已经完成：主链可以跑通，Dream 已经独立沉淀项目记忆，Session 能在 `PLANNING / REVIEWING / MERGING / REPORTING` 阶段恢复，Eval Center 已支持 4 路 baseline 和 Markdown/JSON 报告输出。

## 3. 演进叙事

### P1 到 P5：先打通最小闭环

- 先建 Maven 多模块骨架、LLM 薄封装和领域模型
- 再做单 reviewer 的 Agent Loop、基础 Tool、Context Compiler V1
- 最后把 Gateway 接上 GitHub Webhook、SSE 和 PR comment 回写

这一步的重点是先让“能 review 一个 diff”成为真相，而不是先堆很多平台化抽象。

### P6 到 P9：把主链做成工程系统

- P6 补 Tool 并发分区、缓存和超时
- P7 加 Context Governance 和 Loop Detection
- P8 把单 reviewer 收敛成 Planner + Reviewer Pool + MergeAgent
- P9 开始把 Project Memory 真正注入 Context Compiler

这一步之后，CodePilot 不再是“一个 prompt 调一次模型”，而是一个有 Planning、编译、调度、merge 和记忆注入的 review pipeline。

### P10 到 P17：把“能跑”升级为“可解释、可恢复、可交付”

- P10/P13：补 Eval Center、baseline 对比、报告输出和 CLI
- P14/P15：补 Dream、Project Memory 排序增强、Global Knowledge 只读注入
- P16：扩场景并补 `lint_only` baseline
- P17：补强 Session 恢复链路，避免中断后重跑已完成任务

这一步的目标不是再铺新战线，而是把系统可信度、恢复能力和对外表达补齐。

## 4. 四个必聊问题

### 为什么手搓 Agent Loop，而不是直接用高层框架

我的取舍是：底层 LLM 调用可以薄封装，但 Agent Loop 不能交给黑盒框架。因为这个项目真正有区分度的部分，恰好就是决策解析、Tool 调度、Loop Detection、Context Governance 这些工程细节。

如果面试官问“你的 Agent 和 LangChain4j / Spring AI 的差别是什么”，我会直接回答：我没有把核心 loop 外包给框架；框架只负责 HTTP 调模型，Loop、Context、Tool、Memory 都是我自己定义的边界。

### 为什么 Context Compiler 是第一公民

Code review 不是聊天。给 reviewer 的上下文如果太少，会漏问题；如果把整个 repo 全塞进去，又会把 token 烧在无关代码上，还会干扰推理。

所以我把 Context Compiler 放到主链中央，让它基于 diff、AST、影响范围和 token 预算去编译上下文，而不是简单“塞文件”。这也是为什么 Eval Center 里我会专门统计 `avgTokenEfficiency`，因为我需要量化上下文工程到底有没有价值。

### 为什么 `FINDING_REPORTED != ISSUE_CONFIRMED`

这是我刻意保留的一条边界。reviewer 报出来的是 finding，不是已经确认的 bug。否则系统很容易把模型的猜测包装成事实。

在实现上，MergeAgent 只处理 `REPORTED` findings，做的是去重、排序和过滤，而不是替代人工确认。这个约束能帮助我在设计、测试和对外描述时都保持诚实。

### 为什么评测是架构一等公民

如果没有 Eval Center，项目就很容易滑成“我觉得它挺聪明”。所以我单独做了 `codepilot-eval` 模块，让它能跑 baseline、产出 scorecard、写 Markdown/JSON 报告，还能在 scenario matrix 里看每个场景到底是 pass、fail 还是 error。

我在面试里会强调：评测不是做完系统后补一个脚本，而是从一开始就作为架构模块设计进去，这样后续改 Prompt、改 Context Compiler、改 Memory 排序时，才有稳定回归依据。

## 5. 当前仓库能拿出来的证据

- 固定 review pipeline 已落到 `core / gateway / cli / mcp-server`
- Dream 已独立接入，满足 `REVIEW_COMPLETED != MEMORY_UPDATED`
- Session 已支持阶段恢复，不会无脑重跑已完成 task
- Eval Center 当前支持 4 路 baseline 和 7 个默认场景
- `progress.md` 记录的最近一次全仓验证为 82 个测试通过

## 6. 面试时不要说错的点

- 不要说 CodePilot 会自动修代码。它只做只读审查。
- 不要把设计文档里的示例 F1、precision、latency 当成当前真实结果。真实指标要看你本地生成的 eval 报告。
- 不要把 Dream 说成普通 RAG。当前仓库真相是“review 完成后的独立沉淀后处理”，不是一个通用知识平台。
- 不要把 `finding` 说成“系统确认的问题”。这个边界是项目设计里的关键约束。

## 7. 一个收尾句

如果只能用一句话总结，我会说：CodePilot 的亮点不是“接了模型做 code review”，而是我把 code review 场景里最难量化、最容易失真的三件事做成了工程边界：上下文编译、评测闭环和恢复能力。
