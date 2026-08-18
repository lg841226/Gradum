# Gradum AWF (Agent Workflow File) — DSL Specification

> Version: **0.1 草案** · 状态: 与 `docs/roadmap.md` 同步规划 · 实现节奏: 慢

AWF 是 Gradum 的工作流 DSL。它把现有 Skill（`read_file` / `grep` / `edit_file` / ...）、LLM 调用、事件流、录制组合成一份 **声明式
XML**，可被 Gradum runtime 解析、调度、执行、录制。

**根元素**：`<awf>`
**文件扩展名**：`.awf` / `.awf.xml`（都接受） **编码**：UTF-8 **文档类型**：XML 1.0

---

## 一、文档结构

```xml
<?xml version="1.0" encoding="UTF-8"?>
<awf name="..." model="...">
  <env>
    <var name="..." value="..."/>
  </env>
  <step id="..." run="..." path="...">
    <out name="..." from="..."/>
  </step>
  <step id="..." model="..." needs="..." retry="..." on-error="...">
    <prompt>...</prompt>
    <tool name="..." path="..."/>
    <out name="..." from="..."/>
  </step>
  <trigger cron="..." timeZone="..." enabled="false"/>
</awf>
```

---

## 二、根元素 `<awf>`

| 属性    | 必需 | 说明                                                                       |
|---------|------|----------------------------------------------------------------------------|
| `name`  | ✓   | 工作流标识。写进录制 JSON 的 `workflow` 字段，必须全局唯一（在同一项目下） |
| `model` | ✗   | 默认模型。未声明 `model=` 的 model step 继承此值                           |

---

## 三、`<env>` 块

声明 **只读**常量。在 `${env.X}` 插值中可用。

```xml
<env>
  <var name="repo" value="gradum"/>
  <var name="branch" value="main"/>
</env>
```

- `name` 必需。标识
- `value` 必需。常量值（ **字符串字面量**；v1 不支持引用其他变量）

---

## 四、`<step>` —— 工作流节点

### 4.1 工具 step

```xml
<step id="scan" run="explore_project" depth="3">
  <out name="tree" from="tree"/>
</step>
```

| 属性       | 必需 | 说明                                                   |
|------------|------|--------------------------------------------------------|
| `id`       | ✓   | 全局唯一标识。用于 `${steps.X.outputs.Y}` 插值         |
| `run`      | ✓   | Skill 名。值域见下表                                   |
| `path`     | ✗   | 当 `run` 需要路径时                                    |
| `pattern`  | ✗   | grep / glob 用                                         |
| `command`  | ✗   | run_cmd 用                                             |
| `depth`    | ✗   | explore_project 用                                     |
| `needs`    | ✗   | 逗号分隔的 step id 列表。声明依赖（第 2 步）           |
| `if`       | ✗   | 表达式字符串。条件执行（第 3 步）                      |
| `retry`    | ✗   | 失败重试次数（第 3 步）                                |
| `on-error` | ✗   | `fail` (default) / `continue` / `step.<id>`（第 3 步） |
| `model`    | ✗   | 仅 model step 必需                                     |

#### `run` 值域

```
read_file  edit_file  save_file  run_cmd
explore_project  grep  glob
to_do  finish_to_do_item
search_web
```

未来新增 Skill **自动**出现在此列表。 **不发明新工具**。

### 4.2 `<out>` —— output 声明

```xml
<out name="text" from="content"/>
```

| 属性   | 必需 | 说明                                 |
|--------|------|--------------------------------------|
| `name` | ✓   | 在 `${steps.<stepId>.<name>}` 中引用 |
| `from` | ✓   | 工具 result map 的 dotted path       |

工具 result 形状（来自现有 Skill）：

```json
{
  "success": true,
  "content": "...",
  "error": { "code": "...", "message": "..." },
  "metadata": { "lines": 42 }
}
```

`from` 支持：

- `content` → `result["content"]`
- `error.message` → `result["error"]["message"]`
- `metadata.lines` → `result["metadata"]["lines"]`

### 4.3 Model step（第 4 步）

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

- 一个 model step = **一次完整 agent turn**（`Agent.executeTask` 调用）
- `<prompt>` 内容做 `${...}` 占位符替换
- `<tool>` 声明 **本 step 内 LLM 可调用的工具白名单**。不声明则用 SkillRegistry 全部
- `model` 覆盖根 `model` 属性

---

## 五、变量插值

语法：`${scope.path}` 或 `${fn()}`

### 5.1 Scope

| scope                  | 含义                 | 例                  |
|------------------------|----------------------|---------------------|
| `steps.<id>.<outName>` | 上游 step 的 output  | `${readme.text}`    |
| `env.<name>`           | `<env>` 块声明的常量 | `${env.repo}`       |
| `input.<name>`         | 触发器传入的入参     | `${input.prNumber}` |

### 5.2 零参函数

| 函数     | 返回            |
|----------|-----------------|
| `now()`  | 当前 ISO 时间戳 |
| `uuid()` | 随机 UUID       |

### 5.3 行为

- **取得到值** → 字符串替换
- **取不到值**（id 不存在、outName 未声明、env 未定义）→ **原样保留** `${X}` + 录制里 emit
  `interpolation_unresolved` 警告事件。 **不报错**

---

## 六、表达式引擎（第 3 步）

**仅 `if=` 字段使用**。其他属性（`path` / `pattern` 等）只接受字符串字面量。

### 6.1 支持

| 类别   | 运算符                                                      |
|--------|-------------------------------------------------------------|
| 比较   | `==` / `!=` / `>` / `<` / `>=` / `<=`                       |
| 字符串 | `contains` / `startsWith` / `endsWith` / `empty` / `length` |
| 布尔   | `&&` / `\|\|` / `!`                                         |
| 字面量 | 字符串 `"..."` / 数字 `42` / 布尔 `true` `false` / `null`   |
| 变量   | `${...}` 同插值语法                                         |

### 6.2 不支持

- 字符串拼接（`+`）
- 算术（`+` / `-` / `*` / `/`）
- list / map 字面量
- 用户函数
- 字符串内插（第 5 步再做）

### 6.3 例

```xml
<step id="deploy" if='${tests.failed} == 0 && ${env.dryRun} == "false"'
      run="run_cmd" command="deploy.sh"/>

<step id="warn" if='${readme.text.contains("TODO")}'
      run="run_cmd" command="echo TODO exists"/>
```

---

## 七、`<trigger>`（第 5 步）

```xml
<trigger cron="0 2 * * *" timeZone="Asia/Shanghai" enabled="false"/>
<trigger onFileChange="src/**/*.kt" debounceMs="500" enabled="false"/>
<trigger onCommand="gradum.runWorkflow(name=nightly-codereview)" enabled="false"/>
```

**3 种 trigger，全部 `enabled="false"` 默认**。显式 `enabled="true"` 才挂载。

### 7.1 `cron`

| 属性       | 必需 | 说明                           |
|------------|------|--------------------------------|
| `cron`     | ✓   | 5 字段 cron：`分 时 日 月 周`  |
| `timeZone` | ✗   | IANA 时区，如 `Asia/Shanghai`  |
| `enabled`  | ✗   | `true` / `false`，默认 `false` |

### 7.2 `onFileChange`

| 属性           | 必需 | 说明                          |
|----------------|------|-------------------------------|
| `onFileChange` | ✓   | glob 模式，相对于 projectRoot |
| `debounceMs`   | ✗   | 防抖毫秒数                    |
| `enabled`      | ✗   | 默认 `false`                  |

### 7.3 `onCommand`

| 属性        | 必需 | 说明         |
|-------------|------|--------------|
| `onCommand` | ✓   | IDE 命令 ID  |
| `enabled`   | ✗   | 默认 `false` |

### 7.4 不支持

- webhook（v2 再说）
- 跨 workflow trigger
- matrix 并发

---

## 八、错误处理

| `on-error` 值  | 行为                                                |
|----------------|-----------------------------------------------------|
| `fail`（默认） | 立即终止 workflow，标记 `WorkflowRun.Status.Failed` |
| `continue`     | 跳过该 step 和所有 `needs` 它的 step，继续          |
| `step.<id>`    | 跳到那个 step 继续                                  |

`retry="N"` 在 `on-error` **之前**生效：先重试 N 次， **最后一次失败再走 `on-error`**。

---

## 九、与现有 Skill 参数的对应

工具 step 的非控制属性（除 `id` / `run` / `needs` / `if` / `retry` / `on-error` / `model` 外） **完全对应 Skill
的输入参数**。这 意味着新增 Skill **不需要改 AWF parser**。

例：

```xml
<!-- run_cmd skill 接受 command, reason, detached, ... -->
<step id="branch" run="run_cmd" command="git branch --show-current" reason="curiosity"/>
```

参数语义以 Skill 文档为准，AWF 不重新定义。

---

## 十、最小完整示例

```xml
<?xml version="1.0" encoding="UTF-8"?>
<awf name="nightly-codereview" model="qwen2.5-coder">
  <env>
    <var name="repo" value="gradum"/>
  </env>

  <step id="scan" run="explore_project" depth="3">
    <out name="tree" from="tree"/>
  </step>

  <step id="readme" run="read_file" path="README.md">
    <out name="text" from="content"/>
  </step>

  <step id="review" model="qwen2.5-coder" needs="scan,readme">
    <prompt>Review this README against the project tree.

Project tree:
${scan.tree}

README:
${readme.text}
    </prompt>
    <tool name="edit_file" path="REVIEW.md"/>
    <out name="text" from="final_reply"/>
  </step>

  <step id="commit" needs="review" run="run_cmd" command="git add REVIEW.md"/>

  <trigger cron="0 2 * * *" timeZone="Asia/Shanghai" enabled="false"/>
</awf>
```
