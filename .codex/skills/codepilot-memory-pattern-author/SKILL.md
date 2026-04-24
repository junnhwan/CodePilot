---
name: codepilot-memory-pattern-author
description: 创建或修订 CodePilot 的项目记忆 pattern 和团队编码规范。当需要为 Memory 系统新增 bug/security/perf pattern、维护团队编码约定、或根据 review 反馈更新已有 pattern 时使用。
---

# CodePilot 记忆 Pattern 编写 Skill

## 解决什么问题

CodePilot 的 Memory 不是 RAG 的换皮——三层记忆 + Dream 沉淀 = 跨 PR 的主动学习。但 pattern 编写有几个容易犯的错：

1. **Pattern 描述太抽象**——"注意 SQL 注入"没有用，Agent 需要看到具体的代码特征才能匹配
2. **缺少 bad/good 对照**——只有"不要这样做"没有"应该这样做"，Agent 无法给出修复建议
3. **LEARNED convention 置信度虚高**——从一次 review 学到的规范不能当铁律，需要多次验证
4. **Pattern 之间重叠**——"N+1 查询"和"循环内 DB 调用"是同一个问题的两种描述，放两个 pattern 会稀释检索

这个 skill 解决"怎么写一个对 Agent 真正有用的 pattern"的问题。

## 两种模式

1. `create-pattern` — 新增 review pattern 或团队规范
2. `revise-pattern` — 根据评测报告或 review 反馈更新已有 pattern

## Minimal loading path

先读：

1. `02-架构设计.md` — Memory 三层分层和检索流程
2. `03-技术实现方案.md` — `project_memory` 和 `team_convention` 表结构
3. `AGENTS.md` — 编码约束
4. 现有种子数据：`db/seeds/memory/`

再按需读 reference 文件。

## Pattern 分类

### BUG_PATTERN — 代码 Bug 模式

Agent 需要从变更代码中识别这些常见 bug：

```sql
INSERT INTO project_memory (project_id, pattern_type, title, description, code_example, frequency)
VALUES ('global', 'BUG_PATTERN', 'Null Pointer Dereference Chain',
-- description 要写清楚触发条件和代码特征，不能只写概念
'当方法返回 Optional 或可能为 null 的对象，调用方未经判空直接调用 .method() 或 .getValue()，形成空指针链路。',
-- code_example 必须有 bad 和 good 对照
'// BAD: User user = userService.findById(id); return user.getName();
// GOOD: User user = userService.findById(id); if (user == null) { throw new UserNotFoundException(id); } return user.getName();',
1);
```

**编写要点**：
- description 里写"触发条件 + 代码特征"——`当...时，调用方...，导致...`
- code_example 里 bad 是 Agent 要识别的目标，good 是 Agent 要建议的修复

### SECURITY_PATTERN — 安全漏洞模式

```sql
INSERT INTO project_memory (project_id, pattern_type, title, description, code_example, frequency)
VALUES ('global', 'SECURITY_PATTERN', 'SQL Injection via String Concatenation',
-- 安全 pattern 要写清楚攻击向量和影响范围
'用户输入通过字符串拼接直接嵌入 SQL 语句的 WHERE/VALUES 子句，攻击者可注入任意 SQL。常见于 MyBatis ${} 占位符或 JDBC 直接拼接。',
'// BAD: String sql = "SELECT * FROM users WHERE name = ''" + name + "''";
// BAD (MyBatis): SELECT * FROM users WHERE name = ${name}
// GOOD: @Select("SELECT * FROM users WHERE name = #{name}") User findByName(@Param("name") String name);
// GOOD (JDBC): PreparedStatement ps = conn.prepareStatement("SELECT * FROM users WHERE name = ?"); ps.setString(1, name);',
1);
```

**编写要点**：
- 安全 pattern 要区分框架特定写法（MyBatis 的 `${}` vs `#{}`）和通用写法（JDBC 拼接）
- good example 要给出框架推荐的修复方式，不是只给通用方案

### PERF_PATTERN — 性能反模式

```sql
INSERT INTO project_memory (project_id, pattern_type, title, description, code_example, frequency)
VALUES ('global', 'PERF_PATTERN', 'N+1 Query in Loop',
'在循环体内执行数据库查询，每次迭代触发一次 DB 请求。N 条数据 = N+1 次查询（1 次查列表 + N 次查详情）。常见于 for/forEach 内调用 Mapper/Repository 方法。',
'// BAD: List<Long> ids = orderMapper.findAllIds(); for (Long id : ids) { Order o = orderMapper.findById(id); ... }
// GOOD: List<Long> ids = orderMapper.findAllIds(); List<Order> orders = orderMapper.findByIds(ids); ...',
1);
```

**编写要点**：
- 性能 pattern 要写清楚"为什么慢"——不只是"N+1"这个名词，而是"N 条数据 = N+1 次 DB 请求"
- good example 要给出批量替代方案

### CONVENTION — 团队编码规范

```sql
INSERT INTO team_convention (project_id, category, rule, example_good, example_bad, confidence, source)
VALUES ('global', 'NAMING', 'Service 类以 Service 结尾，Controller 类以 Controller 结尾',
'Spring 组件按职责后缀命名，Service 处理业务逻辑，Controller 处理 HTTP 路由。',
'UserService / OrderController',
'UserManager / OrderHandler',
1.0, 'MANUAL');
```

**编写要点**：
- MANUAL 规范 confidence=1.0，LEARNED 规范 confidence 不超过 0.8
- LEARNED 规范必须有至少 2 次独立 review 验证才能设到 0.6 以上

## create-pattern 流程

1. 先确认是 pattern（Bug/Security/Perf）还是 convention（命名/格式/架构规范）
2. 在现有种子数据中搜索是否已有同类 pattern——避免重复
3. 编写 description（触发条件 + 代码特征 + 影响）
4. 编写 code_example（bad + good 对照）
5. 编写 embedding source text（description + code_example 的拼接，用于向量检索）
6. 设定初始 frequency=1
7. 关联一个 eval scenario 验证该 pattern 能被 Agent 检测到

## revise-pattern 流程

1. 从评测报告或 review 反馈定位到具体 pattern
2. 判断是哪种情况：
   - **Pattern 没被检索到** → description/embedding text 需要优化，增加同义描述
   - **Pattern 被检索到但 Agent 没用** → 不是 pattern 的问题，是 Agent prompt 的问题，不要改 pattern
   - **Pattern 的 good example 过时** → 更新 code_example
   - **Pattern 之间重叠导致检索混淆** → 合并为一个更通用的 pattern
3. 只改 pattern 数据，不改 Agent prompt
4. 重跑关联的 eval scenario 验证

## Hard guardrails

1. **code_example 不能只有 bad 没有 good**——Agent 需要能给出修复建议
2. **LEARNED convention confidence 不能超过 0.8**——单次 review 不构成规范
3. **不要创建只是换个标题的重复 pattern**——"SQL 注入"和"动态 SQL 注入"是同一个 pattern
4. **不要把项目特定的 pattern 放到 `global` project_id**——项目特定 pattern 要绑定到具体项目
5. **不要跳过 embedding source text**——没有 embedding 的 pattern 无法被向量检索命中
6. **pattern 修订后必须重跑关联 eval**——不能凭感觉认为"改了应该就好了"
