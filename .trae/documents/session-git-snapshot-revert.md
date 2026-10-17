# 会话 Git 快照与删除时还原

## Context

模型改完文件后，删除会话并不能撤销它对工作区的改动，事后手工回退很麻烦。方案：每个会话开始时对 projectRoot 打一个 Git
快照（不碰用户 HEAD/index），删除会话时把工作区还原到快照，再走原有删除流程。非 Git 项目静默跳过（不打快照、删除时也无从还原）。不加独立
revert 接口，改动全部在 server 侧。

## 技术选型

git CLI + plumbing，不引 JGit。全程使用 `GIT_INDEX_FILE` 指向临时 index，用户的工作区 index 和 HEAD 零接触。

- **打快照**（会话首条消息时，同一 sessionId 只打一次）：
    1. 临时 index：无 HEAD 则空树起步，有 HEAD 则 `git read-tree HEAD`
    2. `GIT_INDEX_FILE=<tmp> git add -A`（收编工作区全部变更，含未跟踪文件，遵循 .gitignore）
    3. `GIT_INDEX_FILE=<tmp> git write-tree` 得 tree hash
    4. `git commit-tree <tree> -m "gradum snapshot <sessionId>"`，parent 为上一个快照提交或无
    5. `git update-ref refs/gradum/session/<sessionId> <commit>`
- **还原**（/session/delete 内）：
    1. `git rev-parse refs/gradum/session/<sessionId>^{tree}`；ref 不存在 → 跳过还原，只走删除
    2. 临时 index：`git read-tree <tree>` + `git add -A`（当前工作区状态）
    3. `git diff-tree -p --binary <snapshotTree> <tmpTree>` 生成"当前→快照"补丁
    4. `git apply --binary --whitespace=nowarn` 应用到工作区（不碰 index）
    5. 还原成功后 `git update-ref -d refs/gradum/session/<sessionId>`；失败则保留 ref 并记日志、删除流程继续
- 已知限制（注释写明）：.gitignore 内文件不入快照；会话期间 AI 做的 git commit 不会被回退（只还原工作区）

## 改动文件

1. **新建 `src/main/kotlin/gradum/vcs/SessionSnapshotManager.kt`**
    - `fun createSnapshot(projectRoot: String, sessionId: String): Boolean`（非 Git 仓库 / git 缺失 / 失败 → false +
      debug 日志，绝不抛出）
    - `fun revertSnapshot(projectRoot: String, sessionId: String): Boolean`（无 ref → true，语义为"无需还原"）
    - `fun isGitRepository(projectRoot: String): Boolean`
    - 用 ProcessBuilder 跑 git，`directory = projectRoot`，环境变量注入 `GIT_INDEX_FILE`；两词 camelCase 命名，注释英文收拢
2. **`src/main/kotlin/gradum/agent/Agent.kt`**：`executeTask` 里 `sessionManager.reset()` 之后调用
   `createSnapshot(configuration.projectRoot, sessionId)`（sessionId 为空则跳过）；子会话（registerChildSession
   路径）不打快照，复用父会话的
3. **`src/main/kotlin/gradum/server/Routes.kt`**：`/session/delete`（约 L588）在 `ChatDb.deleteSession` 之前调用
   `revertSnapshot`，随后照旧删 DB 行和 conversation.md
4. **测试 `src/test/kotlin/gradum/vcs/SessionSnapshotManagerTest.kt`**（新建）：临时目录 `git init` + 基线提交 →
   模拟模型改动（改/删/建文件）→ createSnapshot 在改动前、revertSnapshot 后断言工作区与基线一致；另覆盖：非 Git 目录
   createSnapshot 返回 false、无 ref 的 revert 返回 true、还原后用户 index 状态未被动过（对比 `git status --porcelain` 前后）

## 验证

构建与测试由用户执行。手工链路：启动 server → 让模型改几个文件 → 插件里删除该会话 → `git status`
应显示工作区回到会话开始前；确认用户自己的未提交改动在快照语义内（会整体回到会话起点）。
