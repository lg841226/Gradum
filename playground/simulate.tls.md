我先看一下项目的核心配置结构,确认 Agent 的权限与模式门。

<tls>
  <t nam="read_file" pth="src/main/kotlin/gradum/AgentConfiguration.kt" lin="1-40"/>
</tls>

看到了配置载体。接着我搜一下工具调用管道里有哪些关键的权限判断,顺便确认所有 Skill 的注册名。

<tls>
  <t nam="grep" pth="src/main" ptr="allowedToolModes" include="*.kt" limit="10"/>
  <t nam="glob" pth="src/main/kotlin" ptr="**/Skill*.kt"/>
</tls>

Skill 都在 `gradum.skill` 包下。下面我打开其中一个实现,确认参数映射 (比如 lineRange 的取值方式)。

<tls>
  <t nam="read_file" pth="src/main/kotlin/gradum/skill/ReadFileSkill.kt" lin="90-130"/>
</tls>

`path` 必填、`lineRange` 走 `"start-end"` 字符串,与三字母缩写 `pth` / `lin` 完全对应。最后跑一个无害命令拿到当前分支名,收尾。

<tls>
  <t nam="run_cmd" cmd="git branch --show-current" reason="context"/>
</tls>
