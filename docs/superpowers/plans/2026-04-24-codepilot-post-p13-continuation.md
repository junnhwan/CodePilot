# CodePilot Post-P13 Continuation Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `P13 DONE` 之后继续推进 CodePilot 主线收尾，优先完成 Dream 记忆沉淀、检索增强、评测扩容与恢复增强，避免偏离固定 Review Pipeline。

**Architecture:** 继续复用现有 `Planning -> Context Compiler -> Multi-Agent Review -> Merge` 主链。所有新增能力都以最小增量挂接到 `codepilot-core / codepilot-gateway / codepilot-eval` 现有实现，不重写 review 主链，不引入平台化空抽象。由于剩余工作跨多个独立子系统，本计划拆成 `P14 ~ P17` 四个独立交付切片，每次新对话只执行一个 Task。

**Tech Stack:** Java 21, Spring Boot 3.5, Maven multi-module, MyBatis, JavaParser, JGit, existing CodePilot core/gateway/eval modules

---

## 使用说明

- 这个文档是“后续开发总计划”，不是要求一次性做完。
- 每个 Task 都应独立完成、独立验证、独立提交。
- 推荐执行顺序：`Task 1 -> Task 2 -> Task 3 -> Task 4`
- `Task 5` 是交付打磨，可以放到主线技术闭环稳定之后再做。

---

### Task 1: P14 Dream 最小记忆沉淀闭环

**目标：**
让 `REVIEW_COMPLETED != MEMORY_UPDATED` 从设计约束落到真实代码：review 完成后独立执行 Dream，分析 `ReviewResult` 并回写 `ProjectMemoryRepository`。

**Files:**
- Create: `codepilot-core/src/main/java/com/codepilot/core/domain/memory/MemoryPlan.java`
- Create: `codepilot-core/src/main/java/com/codepilot/core/application/memory/DreamService.java`
- Modify: `codepilot-core/src/main/java/com/codepilot/core/domain/memory/ProjectMemoryRepository.java`
- Modify: `codepilot-gateway/src/main/java/com/codepilot/gateway/review/GitHubReviewWorker.java`
- Modify: `codepilot-cli/src/main/java/com/codepilot/cli/LocalReviewRunner.java`
- Test: `codepilot-core/src/test/java/com/codepilot/core/application/memory/DreamServiceTest.java`
- Test: `codepilot-gateway/src/test/java/com/codepilot/gateway/review/GitHubReviewWorkerTest.java`
- Test: `codepilot-cli/src/test/java/com/codepilot/cli/LocalReviewRunnerTest.java`

- [ ] **Step 1: 写 DreamService 的失败测试**

```java
@Test
void extractsNewReviewPatternAndSavesUpdatedProjectMemory() {
    ProjectMemory existing = ProjectMemory.empty("demo-project");
    ReviewResult reviewResult = new ReviewResult(
            "session-1",
            List.of(Finding.reported(
                    "finding-1",
                    "task-security",
                    "security",
                    Severity.HIGH,
                    0.95,
                    new Finding.CodeLocation("src/main/java/com/example/AuthService.java", 42, 42),
                    "Missing token guard before repository access",
                    "tokenRepository is reached before validation.",
                    "Call tokenGuard.requireProjectAccess(token) first.",
                    List.of("The repository access appears before the guard call.")
            )),
            false,
            Instant.parse("2026-04-24T00:00:00Z")
    );

    DreamService dreamService = new DreamService(new InMemoryProjectMemoryRepository(existing));

    ProjectMemory updated = dreamService.dream("demo-project", reviewResult);

    assertThat(updated.reviewPatterns())
            .extracting(ReviewPattern::title)
            .contains("Missing token guard before repository access");
}
```

- [ ] **Step 2: 运行测试，确认失败原因正确**

Run: `.\mvnw.cmd -pl codepilot-core "-Dtest=DreamServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected:
- 编译失败，因为 `MemoryPlan` / `DreamService` 还不存在
- 或测试失败，因为 Dream 还没有回写 `ProjectMemory`

- [ ] **Step 3: 新建最小 `MemoryPlan` 领域模型**

```java
public record MemoryPlan(
        String projectId,
        List<ReviewPattern> patternsToAdd,
        List<TeamConvention> conventionsToAdd
) {
}
```

要求：
- 不引入 `MemoryManager` / `MemoryCoordinator` 这类空心中间层
- `MemoryPlan` 只承载 Dream Phase 1 的结果，不负责持久化

- [ ] **Step 4: 实现 `DreamService` 的最小 analyze + update**

```java
public ProjectMemory dream(String projectId, ReviewResult reviewResult) {
    ProjectMemory current = repository.findByProjectId(projectId)
            .orElse(ProjectMemory.empty(projectId));
    MemoryPlan memoryPlan = analyze(projectId, reviewResult, current);
    ProjectMemory updated = apply(memoryPlan, current);
    repository.save(updated);
    return updated;
}
```

要求：
- 只沉淀高置信度、高信号 finding
- Phase 1 负责判断“是否值得沉淀”
- Phase 2 负责把结果并入 `ProjectMemory`
- 幂等：同一 finding 重复 Dream 不应无限追加重复 pattern

- [ ] **Step 5: 为 Dream 增加边界与异常语义测试**

新增测试场景：
- 空 finding 不沉淀
- 低置信度 finding 不沉淀
- 重复 Dream 不重复插入相同 pattern
- repository 保存失败时，异常信息包含 `sessionId` 或 `projectId`

- [ ] **Step 6: 在 Gateway worker 中以“独立后处理”方式接入 Dream**

要求：
- `GitHubReviewWorker` 在 review / report 成功后调用 `DreamService`
- Dream 失败不能回写为“review 失败”
- 失败时要有明确日志和 session 上下文

- [ ] **Step 7: 在 CLI 路径接入同一 DreamService**

要求：
- `LocalReviewRunner` 完成 review 后复用同一个 `DreamService`
- 不在 CLI 里重新实现一套沉淀逻辑

- [ ] **Step 8: 运行受影响测试**

Run:
- `.\mvnw.cmd -pl codepilot-core "-Dtest=DreamServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `.\mvnw.cmd -pl codepilot-gateway -am "-Dtest=GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `.\mvnw.cmd -pl codepilot-cli -am "-Dtest=LocalReviewRunnerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected:
- 新增 Dream 测试通过
- Gateway / CLI 主链不回退

- [ ] **Step 9: 运行全量验证**

Run: `.\mvnw.cmd test`

Expected:
- 全仓测试通过
- 没有因为 Dream 接入破坏现有 `P9/P11/P13`

- [ ] **Step 10: 更新进度并提交**

Run:

```bash
git add progress.md codepilot-core codepilot-gateway codepilot-cli
git commit -m "feat(P14): 实现 Dream 最小记忆沉淀闭环"
```

---

### Task 2: P15 Project Memory 检索增强 + Global Knowledge 最小注入

**目标：**
在现有 P9 词法 recall 基础上，补一版更稳定的检索排序，并引入最小 `Global Knowledge` 注入能力。

**Files:**
- Create: `codepilot-core/src/main/java/com/codepilot/core/domain/memory/GlobalKnowledgeEntry.java`
- Create: `codepilot-core/src/main/java/com/codepilot/core/application/memory/GlobalKnowledgeService.java`
- Create: `codepilot-core/src/main/resources/global-knowledge/security-and-perf.json`
- Modify: `codepilot-core/src/main/java/com/codepilot/core/domain/context/ContextPack.java`
- Modify: `codepilot-core/src/main/java/com/codepilot/core/application/context/DefaultContextCompiler.java`
- Modify: `codepilot-core/src/main/java/com/codepilot/core/application/memory/MemoryService.java`
- Modify: `codepilot-core/src/main/java/com/codepilot/core/application/review/ReviewPromptTemplates.java`
- Test: `codepilot-core/src/test/java/com/codepilot/core/application/memory/GlobalKnowledgeServiceTest.java`
- Test: `codepilot-core/src/test/java/com/codepilot/core/application/memory/MemoryServiceTest.java`
- Test: `codepilot-core/src/test/java/com/codepilot/core/application/context/DefaultContextCompilerTest.java`

- [ ] **Step 1: 写 `GlobalKnowledgeServiceTest` 的失败测试**

```java
@Test
void returnsSecurityGuidanceForTokenAndSqlSignals() {
    GlobalKnowledgeService service = new GlobalKnowledgeService(loadFixtureCatalog());

    List<GlobalKnowledgeEntry> entries = service.recall(
            ReviewTask.TaskType.SECURITY,
            Set.of("token", "sql", "repository"),
            200
    );

    assertThat(entries).extracting(GlobalKnowledgeEntry::title)
            .contains("Parameterized queries required", "Token guard before repository access");
}
```

- [ ] **Step 2: 运行测试，确认当前缺失点**

Run: `.\mvnw.cmd -pl codepilot-core "-Dtest=GlobalKnowledgeServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected:
- 编译失败，因为 `GlobalKnowledgeEntry` / `GlobalKnowledgeService` 尚不存在

- [ ] **Step 3: 定义 `GlobalKnowledgeEntry` 并加载资源化知识源**

```java
public record GlobalKnowledgeEntry(
        String entryId,
        ReviewTask.TaskType taskType,
        String title,
        String guidance,
        List<String> triggerTokens
) {
}
```

要求：
- 先使用资源文件，不提前引入远程知识库
- 保持只读，不混入 Dream 编辑逻辑

- [ ] **Step 4: 增强 `MemoryService` 排序策略**

要求：
- 在现有词法匹配分数基础上，加入“命中字段权重 + 频次 / 置信度权重”
- 保持无 `PgVector` 时仍可工作
- 不要为了未来可能扩展，先引入没有第二实现的空接口

- [ ] **Step 5: 扩展 `ContextPack`，显式区分 `ProjectMemory` 和 `GlobalKnowledge`**

要求：
- reviewer prompt 里分开渲染两类信息
- `ProjectMemory` 优先，`GlobalKnowledge` 兜底
- token 预算必须覆盖新注入内容

- [ ] **Step 6: 增加编译链路测试**

新增断言：
- SECURITY task 能拿到相关 global knowledge
- STYLE task 不会错误注入 security knowledge
- token 预算超紧时，优先保留 project memory，再考虑 global knowledge

- [ ] **Step 7: 运行受影响测试**

Run:
- `.\mvnw.cmd -pl codepilot-core "-Dtest=GlobalKnowledgeServiceTest,MemoryServiceTest,DefaultContextCompilerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected:
- recall 与 context compiler 定向测试全部通过

- [ ] **Step 8: 运行全量验证并提交**

Run:

```bash
.\mvnw.cmd test
git add progress.md codepilot-core
git commit -m "feat(P15): 增强记忆检索并接入 Global Knowledge"
```

---

### Task 3: P16 扩容评测场景并补齐 `Lint Only` baseline

**目标：**
让 P13 的最小 eval 闭环更接近设计文档里的正式评测体系，先扩场景，再补一个低成本 baseline。

**Files:**
- Create: `codepilot-eval/src/main/java/com/codepilot/eval/LintOnlyBaselineReviewer.java`
- Create: `codepilot-eval/src/main/resources/eval/scenarios/security-scenario-pack.json`
- Create: `codepilot-eval/src/main/resources/eval/scenarios/perf-scenario-pack.json`
- Create: `codepilot-eval/src/main/resources/eval/scenarios/expanded-scenario-pack.json`
- Modify: `codepilot-eval/src/main/java/com/codepilot/eval/EvalBaseline.java`
- Modify: `codepilot-eval/src/main/java/com/codepilot/eval/EvalRunner.java`
- Modify: `codepilot-eval/src/main/java/com/codepilot/eval/EvalScenarioLoader.java`
- Modify: `codepilot-eval/src/main/java/com/codepilot/eval/EvalSuiteRunner.java`
- Modify: `codepilot-eval/src/main/java/com/codepilot/eval/EvalReportWriter.java`
- Test: `codepilot-eval/src/test/java/com/codepilot/eval/LintOnlyBaselineReviewerTest.java`
- Test: `codepilot-eval/src/test/java/com/codepilot/eval/EvalSuiteRunnerTest.java`
- Test: `codepilot-eval/src/test/java/com/codepilot/eval/EvalScenarioLoaderTest.java`

- [ ] **Step 1: 为 `LintOnlyBaselineReviewer` 写失败测试**

```java
@Test
void flagsStringBuiltSqlButKeepsSafeRefactorClean() {
    LintOnlyBaselineReviewer reviewer = new LintOnlyBaselineReviewer();

    ReviewResult risky = reviewer.review("lint-session-1", sqlInjectionScenario());
    ReviewResult clean = reviewer.review("lint-session-2", safeRefactorScenario());

    assertThat(risky.findings()).extracting(Finding::title).contains("SQL injection risk");
    assertThat(clean.findings()).isEmpty();
}
```

- [ ] **Step 2: 运行测试并确认失败**

Run: `.\mvnw.cmd -pl codepilot-eval -am "-Dtest=LintOnlyBaselineReviewerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected:
- 编译失败，因为 reviewer 尚不存在

- [ ] **Step 3: 实现低成本 `Lint Only` baseline**

要求：
- 只做规则 / 模式匹配，不接 LLM
- 先覆盖高信号规则：SQL 拼接、循环内 repository 调用、异常吞没、硬编码 token 等
- 不引入完整静态分析平台

- [ ] **Step 4: 扩充 scenario pack**

要求：
- 先把场景数扩到一个“明显高于 4 个”的阶段性规模
- 分类至少包含：安全、性能、无问题变更、跨文件影响
- 每个场景必须带稳定 ground truth
- `expanded-scenario-pack.json` 作为统一入口

- [ ] **Step 5: 扩展 suite / report**

要求：
- `EvalBaseline` 增加 `LINT_ONLY`
- 报告表格和 delta 逻辑支持 4 路 baseline
- CLI 可以选择 `--scenario-pack` 与 `--baselines codepilot,direct_llm,full_context_llm,lint_only`

- [ ] **Step 6: 运行定向测试**

Run:
- `.\mvnw.cmd -pl codepilot-eval -am "-Dtest=LintOnlyBaselineReviewerTest,EvalScenarioLoaderTest,EvalSuiteRunnerTest,EvalReportWriterTest,CodePilotEvalCliTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

Expected:
- 新增 baseline 和扩展 pack 都能被 suite 正常消费

- [ ] **Step 7: 手工跑一遍 CLI**

Run:

```bash
java -jar codepilot-eval\target\codepilot-eval-0.1.0-SNAPSHOT.jar run --scenario-pack eval/scenarios/expanded-scenario-pack.json --baselines codepilot,direct_llm,full_context_llm,lint_only
```

Expected:
- 正常生成 `report.json` 与 `report.md`
- 报告里能看到 `LINT_ONLY`

- [ ] **Step 8: 全量验证并提交**

Run:

```bash
.\mvnw.cmd test
git add progress.md codepilot-eval
git commit -m "feat(P16): 扩容评测场景并补齐 Lint baseline"
```

---

### Task 4: P17 增强 Session 恢复链路

**目标：**
把 P11 的最小 checkpoint + replay 收敛成更稳的恢复闭环，重点补齐更多中断点和后续阶段恢复能力。

**Files:**
- Modify: `codepilot-core/src/main/java/com/codepilot/core/application/session/SessionStore.java`
- Modify: `codepilot-core/src/main/java/com/codepilot/core/domain/session/ReviewSession.java`
- Modify: `codepilot-core/src/main/java/com/codepilot/core/application/review/ReviewOrchestrator.java`
- Modify: `codepilot-gateway/src/main/java/com/codepilot/gateway/review/GitHubReviewWorker.java`
- Test: `codepilot-core/src/test/java/com/codepilot/core/application/session/SessionStoreTest.java`
- Test: `codepilot-gateway/src/test/java/com/codepilot/gateway/review/GitHubReviewWorkerTest.java`

- [ ] **Step 1: 写恢复失败测试**

```java
@Test
void resumesFromReportingWithoutReRunningCompletedTasks() {
    ReviewSession checkpoint = reportingCheckpointSession();
    SessionStore sessionStore = new SessionStore(repositoryWith(checkpoint, replayEvents()));

    ReviewSession restored = sessionStore.restore(checkpoint.sessionId());

    assertThat(restored.status()).isEqualTo(ReviewSession.Status.REPORTING);
    assertThat(restored.reviewPlan().taskGraph().allTasks()).allMatch(ReviewTask::isTerminal);
}
```

- [ ] **Step 2: 运行测试，确认当前恢复能力不足**

Run:
- `.\mvnw.cmd -pl codepilot-core "-Dtest=SessionStoreTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

- [ ] **Step 3: 补强 `SessionStore` 事件重放**

要求：
- 支持 `MERGING / REPORTING` 阶段恢复
- 回放结果足够支撑后续 report / dream
- 不重复调度已完成 task

- [ ] **Step 4: 补强 Gateway worker 的恢复分支**

要求：
- `GitHubReviewWorker` 能按阶段继续，而不是一律重跑 review
- 恢复失败时异常信息带 `sessionId`

- [ ] **Step 5: 增加多中断点测试**

覆盖：
- `PLANNING` 中断恢复
- `REVIEWING` 中只续跑剩余 task
- `MERGING` 中不重复执行 reviewer
- `REPORTING` 中不重复执行 merge

- [ ] **Step 6: 运行受影响测试**

Run:
- `.\mvnw.cmd -pl codepilot-core "-Dtest=SessionStoreTest,ReviewOrchestratorTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`
- `.\mvnw.cmd -pl codepilot-gateway -am "-Dtest=GitHubReviewWorkerTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`

- [ ] **Step 7: 全量验证并提交**

Run:

```bash
.\mvnw.cmd test
git add progress.md codepilot-core codepilot-gateway
git commit -m "feat(P17): 增强 Session 恢复与阶段续跑能力"
```

---

### Task 5: 交付材料打磨（README / 面试 / 博客）

**目标：**
把已经完成的系统能力整理成对外可解释的材料，服务 README、简历和面试。

**Files:**
- Create or Modify: `README.md`
- Create: `docs/interview/codepilot-project-story.md`
- Create: `docs/blog/codepilot-eval-and-context-engineering.md`

- [ ] **Step 1: 先写 README 提纲**

要求：
- 项目定位
- 模块结构
- 当前已完成阶段
- 运行方式
- eval 使用方式

- [ ] **Step 2: 补一版面试故事线**

要求：
- 为什么手搓 Agent Loop
- 为什么 Context Compiler 是第一公民
- 为什么 `FINDING_REPORTED != ISSUE_CONFIRMED`
- 为什么评测是架构一等公民

- [ ] **Step 3: 写技术复盘文章草稿**

要求：
- 聚焦 `P10 -> P13` 的评测演进
- 聚焦 token efficiency / baseline 对比的工程价值

- [ ] **Step 4: 自查材料一致性并提交**

Run:

```bash
git add README.md docs/interview docs/blog
git commit -m "docs: 补充 CodePilot README 与交付材料"
```

---

## 推荐的新对话执行方式

每次新对话只执行一个 Task，并在提示词里明确：

- 工作目录
- 分支真相
- 要先读哪些文档
- 本次只执行哪个 Task
- 不要跨 Task 扩散

推荐从 `Task 1 / P14 Dream 最小记忆沉淀闭环` 开始。
