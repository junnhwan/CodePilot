# CodePilot 复盘草稿：从 P10 到 P13，为什么我要先把 Eval Center 和 Context Engineering 讲清楚

## 1. 先说结论

如果一个 AI Code Review 项目只有“能跑通”，它很难在工程上站住脚。真正决定系统可信度的，不只是 reviewer 能不能吐出 finding，而是下面两个问题有没有被认真回答：

1. 你到底给了模型什么上下文，为什么是这些？
2. 你怎么证明这条 review 主链比“直接把 diff 扔给 LLM”更值得保留？

我在 CodePilot 里把这两个问题分别落成了 `Context Compiler` 和 `Eval Center`。前者回答“上下文怎么编译”，后者回答“效果怎么量化”。

## 2. P10 之前的问题：能跑 review，不等于能解释系统

在 P8、P9 之后，CodePilot 已经有了比较完整的主链：

- Planner 先根据 diff 产出 `ReviewPlan`
- Context Compiler 再根据 diff、AST 和 token budget 组装 `ContextPack`
- Reviewer Pool 并行跑专项审查
- MergeAgent 做 finding 去重和排序
- Dream 在 review 完成后独立沉淀项目记忆

问题是，到这一步它更像一个“能工作的系统”，还不像一个“可解释的工程资产”。

如果我当时就停下来，项目会有几个明显短板：

- 我无法稳定对比 `CodePilot` 和 `直接 LLM review` 的差异
- 我没有统一的 scorecard 去看 precision、recall、误报率和 token 效率
- 我很难把一次迭代的收益沉淀成 README、面试和博客里的证据

所以 P10 到 P13 的重点不是继续堆 reviewer，而是把评测能力收敛成独立模块。

## 3. P10：先做最小 Eval Center，而不是追求“大而全”

P10 的做法很克制：我没有上来就做 47 个场景，也没有一口气补齐所有 baseline，而是先把最小闭环做出来：

- `EvalScenario`：定义 fixture repo、diff、ground truth 和 stop policy
- `EvalRunner`：负责把一个 baseline 跑过所有场景
- `Scorecard`：统一算 precision、recall、F1、误报率和端到端成功率

这一阶段最大的价值，是把“评测”从手工观察日志，变成一个可以独立演进的模块边界。

## 4. P13：从最小 runner 升级到真正可交付的评测闭环

到 P13，我开始补那些“只有在对外交付时才会暴露出重要性”的能力：

- baseline-aware `EvalSuiteRunner`
- `report.json + report.md` 双输出
- baseline delta
- scenario matrix
- 可执行的 `CodePilotEvalCli`

这一步做完以后，Eval Center 才真正能服务三个对象：

1. 开发者自己：改了 Prompt、Memory 排序、Context 编译策略之后，能不能快速看回归
2. README / 面试材料：不再只是说“我做了评测”，而是能展示 scorecard、delta 和 scenario matrix
3. 后续演进：P16 再往上扩场景、补 `lint_only` baseline 时，可以直接复用同一套主链

## 5. 为什么 token efficiency 值得单独当指标

很多 AI 项目会只谈 accuracy，但 code review 还有一个很现实的问题：上下文成本。

如果 review 时把整个 repo 都喂给模型，短期看可能“省事”，但长期会有三个问题：

- token 开销太高，成本不可控
- 无关代码过多，推理容易被噪声干扰
- 很难证明 Context Compiler 的工程投入到底有没有收益

所以在 CodePilot 里，我把 token efficiency 作为一等指标，而不是附属统计。具体做法是：

- 在 `EvalRunner` 中同时记录 `contextTokensUsed` 和 `fullContextTokens`
- 在 `Scorecard` 中输出 `avgTokenEfficiency`
- 在 Markdown 报告里把 token efficiency 和 precision / recall 放在同一张表里

这背后的想法很简单：Context Engineering 不是“感觉更聪明了”，而是应该能被量化成“更少 token，仍然保留关键上下文”。

## 6. 为什么 baseline 对比比单一路径分数更有价值

单看 `CodePilot` 自己的 precision 或 recall，其实很难判断系统设计到底值不值。只有把它放到对比里，工程价值才会清楚。

当前仓库已经支持四路 baseline：

- `codepilot`
- `direct_llm`
- `full_context_llm`
- `lint_only`

其中最关键的对比是前两组：

- `codepilot vs direct_llm`：证明 Planning、Tool、Memory、Merge 这些额外复杂度不是“自嗨”
- `codepilot vs full_context_llm`：证明 Context Compiler 不是为了省 token 而牺牲有效性

`lint_only` 的价值则更偏工程基线：它告诉我，如果完全不走 LLM，只靠高信号规则，我能得到什么下限。

## 7. 这篇文章为什么聚焦 P10 到 P13

严格来说，当前仓库已经继续往后走到了 P16 / P17：默认 scenario pack 已扩到 7 个场景，`lint_only` baseline 也已经补齐，Session 恢复能力也更完整了。

但我还是想把这篇复盘聚焦在 P10 到 P13，因为这是一个关键拐点：

- P10 之前，系统重点是“把 review 主链跑通”
- P13 之后，系统才开始具备“对自己做量化说明”的能力

换句话说，P10 到 P13 解决的不是“系统会不会工作”，而是“系统怎么被证明值得继续做”。

## 8. 如果继续写下去，我会补什么

后续如果把这篇草稿扩成正式博客，我会继续补三部分：

- 一段真实 `report.md` 截图或节选，展示 scorecard 和 scenario matrix 长什么样
- 一个具体场景，说明 `full_context_llm` 和 `codepilot` 在 token efficiency 上的差别
- 一段失败案例，解释为什么没有 Eval Center 时，很容易把 prompt 偶然命中的结果误当成系统能力

## 9. 收尾

我越来越确定一点：在 AI 工程里，`Context Compiler` 和 `Eval Center` 其实是一体两面。

- 前者定义“系统喂给模型什么”
- 后者定义“系统怎么证明这些选择是值得的”

如果没有 Context Compiler，评测很容易只是在比较 prompt 文案；如果没有 Eval Center，上下文工程又会退化成“我觉得这样更合理”。CodePilot 这段 P10 到 P13 的演进，本质上就是把这两件事同时收紧成工程边界。
