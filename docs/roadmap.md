# Gradum Roadmap — Agent Workflow (AWF) DSL

> Status: **草案 v0.1** · 决策记录: 2026-08-16 · 5 步规划 · 实现节奏: 慢

本文档记录 Gradum 演进的下一阶段方向： **用一份声明式 XML 把现有 Skill / LLM 调用 / 事件流 / 录制 组合成可调度的工作流**
。这 不是「再造 GitHub Actions」——本地优先、AI 一等公民、权限模型内建、零运维，定位差异化。

**重大决策（2026-08-16）**：

1. 根元素 `<tls>` **彻底废弃**（不保留 alias）， **不向后兼容**。项目尚未发布 v1，改面仅 13 个文件，3-4 小时可全量切换。
2. 新根元素 `<awf>` (Agent Workflow File) —— 5 字母、和 `.awf` / `.awf.xml` 文件扩展名对应、和 `.tls.xml` 区分开。
3. 5 步路线图按「 **每步独立 PR、独立可发布**」原则设计，用户今天用第 1 步就能少写很多重复 XML，第 5 步才出现「自动跑」语义。

---

## 一、5 步路线图

| 步    | 名                                        | 用户能用它做什么                                                         | 估值    |
|-------|-------------------------------------------|--------------------------------------------------------------------------|---------|
| **1** | **Step identity + outputs + 插值**        | step 加 `id`、把工具结果命名暴露，下游 `${id.field}` 拿                  | ~200 行 |
| **2** | **DAG 调度 (`needs=`)**                   | 并行跑独立 step，串行等依赖完成                                          | ~250 行 |
| **3** | **条件 (`if=`) + 重试 (`retry=`)**        | `if=${tests.failed}==0` 跳过、失败重试 3 次                              | ~200 行 |
| **4** | **Model step**                            | `model="..."` 节点跑 agent loop，能用上游 outputs 当 prompt 和 tool args | ~300 行 |
| **5** | **触发器（cron / file watch / command）** | `cron=` / `onFileChange=` / `onCommand=`，全部 `enabled="false"` 默认    | ~250 行 |

**每一步**：

- 独立 PR
- 独立测试
- 独立 demo 场景
- 独立 CHANGELOG 条目

**为什么这个顺序**：

- 1 是地基。没有 id/outputs 后面全没意义
- 2 是「真正的工作流」语义起点（拓扑），但 1 已经能让用户受益
- 3 是健壮性。必须在 model step 之前——因为 model step 失败率高
- 4 是差异化亮点。必须等 1-3 稳了再做
- 5 是「产品」语义收口。放最后（也最复杂）

---

## 二、DSL 设计

### 2.1 文件名 / 根元素

| 旧                         | 新                                      |
|----------------------------|-----------------------------------------|
| `.tls.xml`                 | `.awf` / `.awf.xml`                     |
| `<tls>`                    | `<awf>`                                 |
| `<t nam="..." pth="..."/>` | `<step id="..." run="..." path="..."/>` |

> **`nam` / `pth` 等 3 字母别名不继承**——v1 阶段就是干净一刀切。

### 2.2 根元素

```xml
<awf name="nightly-codereview" model="qwen2.5-coder">
  <env>
    <var name="repo" value="gradum"/>
  </env>
  <step id="scan" run="explore_project" depth="3">
    <out name="tree" from="tree"/>
  </step>
  <!-- ... -->
  <trigger cron="0 2 * * *" timeZone="Asia/Shanghai" enabled="false"/>
</awf>
```

- `name` **必需**。工作流标识，写进录制 JSON 的 `workflow` 字段
- `model` **可选**。作为 **未声明 `model=` 的 step 的默认模型**
- 顶层三件事：`<step>` 们、`<trigger>` 们（第 5 步）、`<env>` 块（常量）

### 2.3 Step —— 核心节点

#### 2.3.1 工具 step

```xml
<step id="readme" run="read_file" path="README.md">
  <out name="text" from="content"/>
</step>
```

- `id` 必需且 **全局唯一**
- `run` 必需。值就是 Skill 名（`read_file` / `grep` / `glob` / `edit_file` / `save_file` / `run_cmd` / `to_do` /
  `finish_to_do_item` /
  `explore_project`）， **不发明新工具**
- 其他属性是 Skill 的参数。 **复用 `ParsedToolCall` 的全部参数**（`path` / `pattern` / `command` 等），未来第 1 步时 **老
  demo 100% 能跑**——向后兼容由 Skill 参数层完成
- `<out name="X" from="Y"/>` 显式声明哪些字段是 outputs。`Y` 是工具 result map 的 dotted path（`content` /
  `error.message` /
  `metadata.lines`）， **不发明新语法**

#### 2.3.2 Model step（第 4 步）

```xml
<step id="review" model="qwen2.5-coder"
      needs="scan,readme"
      retry="2" on-error="continue">
  <prompt>Review this README against the tree: ${scan.tree}</prompt>
  <tool name="read_file" path="REVIEW.md"/>
  <tool name="edit_file" path="REVIEW.md" content="..."/>
  <out name="text" from="final_reply"/>
</step>
```

- 一个 model step = **一次完整 agent turn**（`Agent.executeTask` 调用）。 **不嵌套多轮 state machine**——v1 简化复杂度
- `<prompt>` 内容做 `${...}` 占位符替换
- `<tool>` 声明 **本 step 内 LLM 可以调用的工具白名单**。 **不声明**则用 SkillRegistry 全部
- `model` 覆盖根 `model` 属性

#### 2.3.3 复合 step（内联 group，留到 v2）

v1 不做。理由：嵌套 group + 命名空间复杂度翻倍，先把扁平跑顺。

### 2.4 变量插值（ **只做 string substitution**）

语法：`${scope.path}`

| scope                  | 含义                 | 例                  |
|------------------------|----------------------|---------------------|
| `steps.<id>.<outName>` | 上游 step 的 output  | `${readme.text}`    |
| `env.<name>`           | `<env>` 块声明的常量 | `${env.repo}`       |
| `input.<name>`         | 触发时传入的入参     | `${input.prNumber}` |
| `now()`                | 当前 ISO 时间戳      | `${now()}`          |
| `uuid()`               | 生成 UUID            | `${uuid()}`         |

**只支持**：dotted path 取值 + 上面 2 个零参函数 + 字符串拼接的最小子集。 **v1 不做算术、布尔、列表、map 字面量、用户函数**。

**取不到值** → `${X}` **原样保留** + 录制里 emit `interpolation_unresolved` 警告事件。 **不报错**——调试可见而非中断。

### 2.5 表达式引擎（ **第 3 步**）

v1 支持：

| 类别   | 运算符                                                      |
|--------|-------------------------------------------------------------|
| 比较   | `==` / `!=` / `>` / `<` / `>=` / `<=`                       |
| 字符串 | `contains` / `startsWith` / `endsWith` / `empty` / `length` |
| 布尔   | `&&` / `\|\|` / `!`                                         |
| 字面量 | 字符串 `"..."` / 数字 `42` / 布尔 `true` `false` / `null`   |

**`+` 字符串拼接不支持**。需要拼接的写法：上游 step 里做拼接，或写两个 if 分支。

### 2.6 触发器（ **仅第 5 步**）

```xml
<trigger cron="0 2 * * *" timeZone="Asia/Shanghai" enabled="false"/>
<trigger onFileChange="src/**/*.kt" debounceMs="500" enabled="false"/>
<trigger onCommand="gradum.runWorkflow(name=nightly-codereview)" enabled="false"/>
```

**3 个 trigger，IDE 启动时不挂载， **用户显式 `enabled="true"` 才生效**：

- 防 IDE 重启后悄悄跑 workflow（意外触发）
- 触发器是「主动行为」，写出来后用户必须自己说「我想要它自动跑」

**watchService 资源**：`onFileChange` 注册时检查 **该项目**未注册其他 watcher，避免重复。

**webhook 不做**（要绑端口、防火墙、签名，意义不大；后面要加再说）。

### 2.7 错误处理

| 字段                   | 行为                                                | 默认值     |
|------------------------|-----------------------------------------------------|------------|
| `on-error="fail"`      | 立即终止 workflow，标记 `WorkflowRun.Status.Failed` | ✓（默认） |
| `on-error="continue"`  | 跳过该 step 和所有 `needs` 它的 step，继续          |            |
| `on-error="step.<id>"` | 跳到那个 step 继续                                  |            |

`retry="N"` 独立，叠加在 `on-error` 之前：先重试 N 次， **最后一次失败再走 `on-error`**。

---

## 三、不做的事（明确边界）

- 远程 runner / 跨机调度
- secrets 存储（IDE 系统凭据已经管）
- matrix 策略（成本太高，先 1:1）
- 缓存（`actions/cache` 那种 key/restoration，先不做）
- artifact 跨 workflow 传递（只做 workflow 内 outputs）
- Action marketplace（生态留给社区）
- 复合 step / 嵌套 group（v2 再说）
- model step 内多轮 LLM 决策（v2 再说）
- webhook 触发器（v2 再说）

**这条边界**让整个 DSL 的体量控制在「用户 30 分钟读完语法、1 小时能写」。

---

## 四、和现有系统的对接点（第 1 步的精确接缝）

第 1 步是「今天就要埋雷」的，精确到代码层面：

**改动 13 个文件**：

| 文件                                                                       | 改动                                                                                                |
|----------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------|
| `playground/skills-demo.tls.xml`                                           | 改名 `skills-demo.awf.xml`；`<tls>` → `<awf>`；`<t nam=.../>` → `<step id=... run=.../>`            |
| `playground/simulate.tls.md`                                               | 同上                                                                                                |
| `src/main/kotlin/gradum/debug/ToolCallScenarioParser.kt`                   | 删掉 `<tls>` 分支；只接受 `<awf>`；根元素不匹配时**报错**（`Use <awf> (see docs/roadmap.md §2.1)`） |
| `src/main/kotlin/gradum/agent/Agent.kt`                                    | `playToolCallScenario` → `playWorkflow`；`playback_start/end` → `workflow_start/end`                |
| `src/main/kotlin/gradum/server/Routes.kt`                                  | `toolCallXml` 字段 → `workflowXml`                                                                  |
| `plugin/src/main/kotlin/gradum/idea/ui/GradumCallbacks.kt`                 | `.tls` 扩展名注册 → `.awf`                                                                          |
| `plugin/src/main/kotlin/gradum/idea/chat/state/GradumChatSession.kt`       | `toolCallXml` → `workflowXml`                                                                       |
| `plugin/src/main/kotlin/gradum/idea/chat/api/GradumApiClient.kt`           | 同上                                                                                                |
| `plugin/src/main/kotlin/gradum/idea/chat/model/MarkdownTlsScenario.kt`     | `<tls>` 块 → `<awf>` 块；类名 `MarkdownTlsScenario` → `MarkdownAwfScenario`                         |
| `src/test/kotlin/gradum/debug/ToolCallScenarioParserTest.kt`               | 跟着改根元素和 step 语法                                                                            |
| `src/test/kotlin/gradum/server/DebugPlaybackEndToEndTest.kt`               | 跟着改                                                                                              |
| `plugin/src/test/kotlin/gradum/idea/chat/model/MarkdownTlsScenarioTest.kt` | 跟着改                                                                                              |
| `src/main/java/gradum/ErrorCode.java`                                      | `INVALID_SCENARIO_XML` → `INVALID_WORKFLOW_XML`                                                     |
| `docs/ARCHITECTURE.md`                                                     | 文档更新（5 处引用）                                                                                |

**净增/减代码**： **约 -30 行**（砍掉老 parser 分支带来的减法比新增多）

**实际动手工时**：3-4 小时

**风险**：parser 报错信息 **指引用户去看 `docs/roadmap.md` §2.1**，不指引回去用 `<tls>`。

---

## 五、第 1 步的代码层面预制件（ **今天不动，仅记录**）

第 1 步里 4 件预制件让未来 2-5 步顺水推舟：

1. **给 `ParsedToolCall` 加 `id` 字段**（30 行）——未来 `${steps.X.outputs.Y}` 写得出
2. **`${...}` 字符串插值**（80 行 + 1 个新文件 `InterpolationEngine.kt`）——第 2 步变量传递的地基
3. **录制 JSON 改成 run 格式**（20 行）——顶层加 `runId` / `workflow`，`calls` → `steps`，每条 step 加 `id` / `startedAt` /
   `finishedAt` / `outputs`
4. **事件流加 `step_start` / `step_end` 边界**（30 行）——未来 UI 按 step 折叠的基础

**这 4 件一共 160 行 + 测试**。 **今天做完，未来 2-5 步不用回头改 parser / recorder / events**。

---

## 六、文档更新计划

| 文档                             | 改动                                                                                              |
|----------------------------------|---------------------------------------------------------------------------------------------------|
| `docs/roadmap.md`                | 本文档                                                                                            |
| `docs/awf-dsl.md`                | 新增，DSL 语法规范（`docs/ARCHITECTURE.md` 引用它）                                               |
| `docs/ARCHITECTURE.md`           | 把「ToolCallScenarioParser (tls.xml → scripted tool calls)」改为「AWF Parser (.awf.xml → workflow |
| run）」，链接 `docs/awf-dsl.md`  |                                                                                                   |
| `README.md`                      | 「Quick Start」加一段 `.awf` 示例                                                                 |
| `CHANGELOG.md`                   | 新增，v0.9.3 条目：「BREAKING: `<tls>` → `<awf>`，详见 `docs/roadmap.md` §2.1」                   |
| `playground/skills-demo.awf.xml` | demo 改名 + 改语法                                                                                |
| `playground/simulate.awf.md`     | 同上                                                                                              |

---

## 七、版本与节奏

- **v0.9.2**（当前）：`<tls>` 在用，无 workflow 调度
- **v0.9.3**（第 1 步上线）：`<tls>` 砍光，`<awf>` 上位
- **v0.9.4**（第 2 步）：DAG 调度 `needs=`
- **v0.9.5**（第 3 步）：`if=` / `retry=`
- **v1.0**（第 4 步）：Model step（差异化亮点）
- **v1.1**（第 5 步）：触发器三件套

**v0.9.3 之前不发布 v1.0**。`awf` 拼写 / 字段名 / 事件名 **在 v0.9.3 上线后冻结 6 个月**，不接受 break 变更。
