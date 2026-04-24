---
name: codepilot-eval-report-reader
description: 读取 CodePilot review 评测报告，产出面向 Agent 调优的优化方案。当需要解读 scorecard、分析误报/漏报根因、对比两次评测回归、或决定下一轮该调 Agent prompt 还是 Context 编译策略时使用。
---

# CodePilot 评测报告解读 Skill

## 解决什么问题

CodePilot 的评测报告不是"打完分就完事"——它的核心价值是指导下一轮 Agent 调优。但 Agent 调优有几个坑：

1. **误报和漏报的根因不同**，但表现类似（都是 finding 对不上 ground truth）
2. **调 Agent prompt 和调 Context 编译是两个方向**，搞混了会越调越差
3. **Loop Detection / Token 溢出是 runtime 问题**，不是 Agent prompt 能解的

本 skill 就是解决"看完报告后该改什么、怎么改、怎么验证"这个问题。

## 必须先读的输入

1. `scorecard.json` — 总分、各维度得分、hard gate 通过情况
2. `review-eval-report.md` — 人类可读报告，含 review 时间线和 per-finding 证据

如果存在，再补读：

1. `raw-evidence.json` — Agent 决策链、Tool 调用记录、Context 编译产出的 ContextPack 快照
2. `baseline-comparison.json` — 与 4 个 baseline 的对比数据

## 固定工作流

### Step 1: 读 scorecard，定位问题维度

- overall status 是 PASS / PARTIAL / FAIL？
- 9 个维度里哪些低于阈值？
  - `PRECISION` 低 → 误报多，Agent 报了不该报的
  - `RECALL` 低 → 漏报多，Agent 没发现该发现的
  - `TOKEN_EFFICIENCY` 差 → Context 编译拉了太多无关内容
  - `REVIEW_DELAY` 高 → Agent Loop 迭代太多或 LLM 调用慢
  - `LOOP_DETECTION_ACCURACY` 低 → 要么误触（不该停的停了）要么漏触（该停的没停）
  - `MEMORY_HIT_RATE` 低 → 项目记忆检索不准或 Agent 不愿意引用
  - `END_TO_END_SUCCESS_RATE` 低 → runtime 稳定性问题

### Step 2: 读 report，区分误报和漏报

- **误报分析**：Agent 报了 finding，但 ground truth 里没有
  - 记录每个误报的 file、line、severity、category
  - 误报是 Agent 幻觉？还是 Context 编译给了错误信息导致 Agent 推理错误？
- **漏报分析**：ground truth 里有 finding，Agent 没报
  - 记录每个漏报的 expected finding
  - 漏报是 Agent 看到了但没判断出来？还是 Context 编译根本没拉到相关代码？

### Step 3: 读 raw-evidence，追溯根因

对每个误报/漏报，追溯 Agent 的决策链：

1. **Agent 有没有调用 Tool 查看相关代码？**
   - 调了 → Tool 返回的信息够不够？→ 归因 `tool protocol` 或 `context compilation`
   - 没调 → Agent 为什么不调？→ 归因 `prompt/decision`
2. **Context 编译产出的 ContextPack 里有没有相关代码？**
   - 有 → Agent 看到了但没发现 → 归因 `prompt/decision`
   - 没有 → 为什么没拉到？AST 解析失败？优先级排序错误？→ 归因 `context compilation`
3. **Agent 有没有 Loop Detection 误触导致提前终止？**
   - 有 → 归因 `runtime robustness`
4. **Planner 选的策略对不对？该派 Security Reviewer 却派了 Style Reviewer？**
   - 不对 → 归因 `planning/strategy`
5. **Memory 有没有返回相关 pattern 但 Agent 没用？**
   - 有但没用 → 归因 `prompt/decision`
   - 没返回 → 归因 `memory/retrieval`

### Step 4: 输出优化方案

## 输出格式

必须严格包含以下六段：

### 1. 问题摘要
哪些维度不达标，总误报数 / 总漏报数，最严重的 3 个问题。

### 2. 逐 finding 归因
每个误报和漏报单独归因到以下类别之一：

| 归因类别 | 含义 | 典型表现 |
|----------|------|---------|
| `prompt/decision` | Agent 看到了代码但判断错了 | 漏报：代码在 ContextPack 里，Agent 也没调 Tool，但没报 finding |
| `context compilation` | 相关代码没进入 ContextPack | 漏报：影响的调用方没被拉取，Agent 只看了变更文件本身 |
| `tool protocol` | Tool 返回信息不足或格式误导 Agent | 误报：AST 解析返回了错误的方法签名 |
| `memory/retrieval` | 项目记忆检索不准 | 漏报：历史有类似 pattern 但检索没命中 |
| `planning/strategy` | Planner 选错了策略或拆分不当 | 漏报：安全敏感变更但只派了 Style Reviewer |
| `runtime robustness` | 超时、Loop Detection、LLM 调用失败 | 漏报：Agent 因 Loop Detection 被提前终止，partial findings 里没有该 finding |

### 3. 优先级排序的优化清单
按"影响面 × 实施成本"排序。规则：
- 归因集中在 `context compilation` → 优先调 ContextCompiler，因为一个编译策略的修改可以同时改善多个 finding
- 归因集中在 `prompt/decision` → 优先调 Agent prompt，但要注意 prompt 改动可能影响其他场景
- 归因分散在多个类别 → 优先改影响面最大的那个

### 4. 每项优化的目标落点
精确到类/方法级别，例如：
- "修改 `SecurityReviewer` 的 system prompt，增加'当看到字符串拼接 SQL 时必须报 CRITICAL finding'的 few-shot 示例"
- "修改 `ContextCompiler` 的 filePriority，把 `direct_callers` 优先级提到 `changed_files` 之后"
- "修改 `LoopDetector` 的 patternThreshold 从 3 调到 4"

### 5. 验证方式
每项优化必须指定验证方式：
- 修改 prompt → 重跑该场景的 eval，确认 precision/recall 变化
- 修改 Context 编译 → 重跑该场景 + 检查 ContextPack 的 token_efficiency 变化
- 修改 Loop Detection → 重跑所有场景，确认没有 regression

### 6. 不要动的边界
明确标注哪些东西不能改：
- 固定 Review Pipeline 不能改（不能因为一个场景漏报就加新的 workflow 节点）
- Agent Loop 手搓实现不能用框架封装替代
- 不能把评测数据写死到代码里

## 常见反模式

| 反模式 | 为什么错 | 正确做法 |
|--------|---------|---------|
| "换个更强的模型就行了" | 换模型可能提升 precision 但降低 recall，或者相反；没有归因数据 | 先归因，确认根因在 prompt 还是 context，再决定是否换模型 |
| "在 prompt 里加一条规则" | 每加一条规则都可能和其他规则冲突，导致新误报 | 用 few-shot 示例代替规则，用 eval 回归验证 |
| "加大 token budget" | 可能拉来更多噪声，降低 precision | 先检查是不是 filePriority 排错了，再考虑加预算 |
| "给这个场景单独写逻辑" | 场景特定 hack 会在其他场景产生 regression | 改通用策略，用 scoringWeights 区分场景重要性 |
