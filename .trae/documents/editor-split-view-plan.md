# 编辑器「同一文件双视图」分栏

## Context（背景与目标）

当前 skills-editor（原生 ES Module 前端）的编辑器是单实例结构：`features/editor.js` 的 `mountEditor` 用一个闭包持有唯一的代码面（gutter + 高亮覆层 + 标记层 + 绘制光标 + textarea），所有渲染/光标/滚动函数都假定「同一时刻只有一个 active 文档、一套 surface」。

用户希望编辑器支持「并排展示」：左右两个窗格显示**同一份文档**，两边都可编辑、各自独立滚动与光标，用于对照文件的不同部分；开关放在菜单栏右侧、主题切换按钮左边；分栏状态随刷新持久化（与既有布局持久化同一套机制）。

这是「同文件双视图」，不是两个不同文档，也不是只读镜像。图标使用 IntelliJ 平台现成的 `expui/actions/split_dark.svg`（左右两栏），需按 `ui/icons.js` 既有约定内联进 sprite（灰色改写为 `currentColor`）。

约束：前端无构建步骤，但服务器从 `build/resources/main/skills-editor/` 提供资源，**不重新构建看不到改动**；代码注释与 UI 文案一律英文。

## 方案

### 1. `features/editor.js` — 抽出 `createCodePane` 工厂

新增模块级工厂 `createCodePane(store, {onInput, onFocus})`，返回一个 pane 对象：

- 构建并持有自己的 DOM：`.code-pane > (.gutter > .gutter-inner) + (.code > .highlight > code, .markers, .caret, textarea.source)`。
- 输出 `root` 及各元素引用，方法：`measureMetrics / renderHighlight / loadDoc(doc, {focus}) / adoptText(text) / syncScroll / updateSelection / updateCaret / updateActiveLine / advance`，以及 pane 局部状态 `metrics / selectedLines / caretOffset / publishedCursor / renderedText`。
- 搬进工厂的现有闭包逻辑（editor.js:114–147、168–456）：DOM 子树、`measureMetrics / textPositionAt / advance / clearSelection / updateSelection / scrollOffset / syncScroll / updateCaret / lineNumberAt / updateActiveLine / renderHighlight`，以及 `source` 上的 `input/scroll/focus/blur/keydown` 监听。
- 保持模块级的纯函数不动：`tokenize / classOf / markGlyphs / displayColumns / WIDE_GLYPH`（无 DOM 依赖，两个 pane 共享）。

在 `mountEditor` 保留：`panel / header / tabStrip / body / emptyState / statusBar / 计数 / patchDoc / logLine / refreshFiles / run / build`，新增 `onInput(pane) / onFocus(pane) / render(state) / activePane() / toggleSplit()`。

关键细节：
- **两个 pane 在 mount 时就创建并都插入 DOM**，右 pane 仅靠 CSS 收起。这样 `panes` 长度恒定，无「开启时才补画诊断标记」的生命周期问题，`render` 只切 class。
- `loadDoc(doc, {focus = false})` 参数化：右 pane 载入文档不得抢焦点（现有实现无条件 `source.focus()`）。
- `updateCaret` 写 `state.cursor` 前加保护：仅当 `document.activeElement === source` 才发布，保证状态栏跟随「当前聚焦的 pane」。
- 新增 `adoptText(text)`：保存 `selectionStart/End` → 写 `source.value` → 钳制恢复 selection → 重绘；用于非聚焦 pane 的同步。
- `window.resize` 与 `document.selectionchange` 在 editor 层各注册**一次**，内部遍历 `panes`；`selectionchange` 只对 `source === document.activeElement` 的 pane 生效。
- 返回值收敛为 `{body, tabStrip, panes, activePane, source, highlightCode, run, build, toggleSplit}`；`source/highlightCode` 显式指向左（主）pane 供 minimap 使用；删除不再需要的 `metrics/markers/advance/syncScroll/updateActiveLine` 导出（diagnostics 改走 `panes`）。

### 2. 两 pane 共用缓冲的同步

- **活动 pane**：`activePane()` = 聚焦元素所在的 pane，否则 editor 层记录的最近焦点，再否则左 pane。
- **写**：任一 pane 触发 `input` → `onInput(pane)` 读 `pane.source.value` → 按现有逻辑 `withDoc` + `sourceRevision + 1` 写 store。
- **派发**：`render(state)` 遍历 `panes`：
  - 若 `pane.source.value !== doc.source` **且该 pane 未聚焦** → `adoptText(doc.source)`（保留并钳制其选区）。被聚焦的 pane 视为唯一写者，永不改写其 value/selection，因此光标、选区、IME 合成不受打扰。
  - 文档切换（`id` 变化）时两个 pane 都 `loadDoc`，仅活动 pane `focus: true`。
  - `body.classList.toggle("is-split", Boolean(state.split) && Boolean(doc))`、`toggle("is-empty", !doc)`，并同步分栏按钮的 `aria-pressed / disabled / tooltip`。
- **两个光标**：每个 pane 各自绘制光标与当前行/选区带；未聚焦 pane 不发布 `state.cursor`（状态栏只跟聚焦 pane）；两个 pane 的 `.line.active` 各自显示，能看到「对侧光标位置」，这正是对照价值。

### 3. `run() / build()` 读 store 而非 textarea

- `onInput` 读 `pane.source.value`（回调自带 pane，天然无歧义）。
- `run()`（editor.js:492）与 `build()`（editor.js:568）把 `const text = source.value` 改为 `doc.source`；`run()` 里 `baseline: source.value` 同样改用 `doc.source`。
- 理由：store 是单一真源，`onInput` 每次按键已同步 store，`doc.source` 与聚焦 textarea 恒等；分栏下不存在「读哪一个 textarea」的歧义。pane 内部的 `updateCaret/updateSelection` 继续读自己的 `source.selectionStart/End/value`（那是视图局部状态）。

### 4. DOM / CSS 结构

```
body.editor-body (flex row)
├── .code-pane[data-pane="left"]     flex:1 1 0; display:flex; min-width:0
├── .pane-divider                    静态 1px 细线（无拖拽）
├── .code-pane[data-pane="right"]    同结构，收起态
├── .empty-state                     现有
└── .minimap                         由 minimap.js 追加
```

- 分隔条：静态 1px，颜色沿用 `.editor-status` 的 `color-mix(in srgb, var(--border) 45%, transparent)`；`::after { inset: 0 -3px }` 预留命中区。不新增 token。
- 收起态 `.code-pane[data-pane="right"] { flex-grow:0; flex-basis:0; opacity:0; visibility:hidden; overflow:hidden }`，展开态由 `.editor-body.is-split` 恢复；`visibility` 顺带把右 pane 移出 Tab 焦点顺序。过渡只作用于 `flex-grow/opacity/visibility`，用 `--motion-fast` + `--ease-standard`；刷新恢复时被 `html.is-booting *` 压制，不播入场动画。
- 空状态：`.editor-body.is-empty .code-pane, .editor-body.is-empty .pane-divider { display:none }`，避免三分宽度。
- **minimap**：保持单条，绑定左（主）pane；在 `minimap.js` 的 `render` 中增加 `sourceRevision` 触发 `clone()`，以便在右 pane 输入（程序化写 `.value` 不触发 `input`）时克隆仍刷新。

### 5. 菜单栏按钮 / 图标 / 文案 / 按下态

- **位置**：按钮放在**菜单栏右侧分组**（`.menubar-right`）里、**主题切换按钮的左边**：`rightGroup.append(splitButton, themeButton)`（menubar.js:128-130）。该组为 `flex: 1 1 0; min-width: max-content; justify-content: flex-end`，两个按钮会一起贴右，顺序为先分屏、后主题。
- **图标**：`ui/icons.js` 新增 `split` 条目，取自 `expui/actions/split_dark.svg`，`fill="#CED0D6"` 改为 `currentColor` 并加 `stroke="none"`；文件头来源清单补 `actions`。
- **接线**：`mountMenubar` 增加入参 `onToggleSplit`（`mountMenubar(store, {canvas, onRun, onBuild, onToggleSplit})`）；`main.js` 传 `onToggleSplit: () => editor.toggleSplit()`（`mountEditor` 在 `mountMenubar` 之前调用，可拿到返回值）。按钮用 `createIconButton({icon: "split", tooltip, onClick: onToggleSplit})`。
- **状态**：在 menubar 既有的 `render(state)` 里按 `state.split` 切换 `aria-pressed` 与 tooltip（`split` / `unsplit`），无 active 文档时 `disabled`。
- **文案**（`ui/copy.js` 的 `tooltip`）：`split: "Split editor right"`、`unsplit: "Unsplit editor"`，随状态切换 `data-tooltip` 与 `aria-label`。
- **按下态**（`styles/components.css`）：在菜单栏图标按钮那段（`.menubar .btn[data-variant="icon"]`，components.css:102-115）之后加 `.menubar .btn[data-variant="icon"][aria-pressed="true"] { background: var(--accent-selection); color: var(--text); }`——用 `.menubar` 前缀保证与既有同优先级规则靠后生效，避免被 `--text-dim` 覆盖。
- `editor.toggleSplit()`：`store.setState({split: !split})`；关闭时若焦点在右 pane，先把焦点交还左 pane。

### 6. 持久化

- `core/persistence.js` 的 `LAYOUT_KEYS` 增加 `split: "boolean"`（`pickLayout` 已按 `typeof` 校验，自动往返），文件头注释补一句。
- `main.js` store 默认值增加 `split: false`（放在 `...loadLayout()` 之前）。首帧即应用，且处于 `is-booting` 期间，恢复不播动画。

### 7. `features/diagnostics.js` 改为遍历 `editor.panes`

- `clearMarks()` / `renderMarks()` 改为 `for (const pane of editor.panes)`，逐 pane 使用其 `metrics / advance / highlightCode / markers`，行文本用 `doc.source.split("\n")`；两个 pane 都出现 squiggle 与 `errline/warnline`。
- `jumpToLine()` 改用 `editor.activePane()` 的 `source / metrics / syncScroll / updateActiveLine`。

## 待修改文件

- `src/main/resources/skills-editor/features/editor.js`（核心重构，导出 `toggleSplit`）
- `src/main/resources/skills-editor/features/menubar.js`（右侧分组加分屏按钮 + `onToggleSplit` 接线）
- `src/main/resources/skills-editor/features/diagnostics.js`
- `src/main/resources/skills-editor/features/minimap.js`
- `src/main/resources/skills-editor/core/persistence.js`
- `src/main/resources/skills-editor/main.js`
- `src/main/resources/skills-editor/ui/icons.js`
- `src/main/resources/skills-editor/ui/copy.js`
- `src/main/resources/skills-editor/styles/layout.css`（`.code-pane` 收起/展开、`.pane-divider`、空状态）
- `src/main/resources/skills-editor/styles/components.css`（`aria-pressed` 按下态）

## 实施顺序

1. `ui/icons.js` 加 `split`；`ui/copy.js` 加两条 tooltip。
2. `core/persistence.js` 加 `split` 键；`main.js` 加默认值。
3. `features/editor.js`：抽 `createCodePane`，editor 层改双 pane + `render/onInput/run/build` + 全局监听 + `toggleSplit`，收敛返回值。
4. `features/menubar.js`：右侧分组加 `splitButton`（在 `themeButton` 左侧）与 `onToggleSplit`；`main.js` 接线。
5. `features/diagnostics.js` 遍历 `editor.panes`。
6. `features/minimap.js` 加 `sourceRevision` 触发 `clone()`。
7. `styles/layout.css` 与 `styles/components.css` 加规则。
8. 重新构建并手测。

## 验证

先构建（`./gradlew processResources` 或直接 `./gradlew run`）——**前端无构建步骤，但服务器读 `build/resources/main/skills-editor/`，不重建看不到改动**；硬刷新绕过缓存。

1. 打开 `HelloSkill.kt`，点菜单栏右侧（主题按钮左边）的分屏按钮：出现左右两栏、1px 分隔线、按钮按下态、tooltip 变为 unsplit。
2. 左栏输入：右栏同步更新，右栏自己的滚动位置与选区不被重置。
3. 只滚动右栏：左栏不动；右栏拖选，选区带只出现在右栏。
4. 分别聚焦左右：状态栏 `line:column` 跟随聚焦 pane。
5. 两栏各自按 Tab 缩进、Cmd/Ctrl+Enter 运行，均作用于同一缓冲。
6. 增删行后 Build：squiggle 与 `errline/warnline` 在两栏都出现；Problems 行点击跳到聚焦 pane 的对应行。
7. minimap：克隆与文本一致；右栏输入时克隆也刷新；点击 minimap 滚动左 pane。
8. 刷新页面：分栏状态恢复、无入场动画、两栏同缓冲；`localStorage["gradum.skillsEditor.layout"]` 含 `"split":true`。
9. 焦点在右 pane 时关闭分栏：焦点回到左 pane。
10. 回归：标签页新建/关闭、空状态（关闭全部标签后两 pane 隐藏、按钮 disabled）、rail 折叠、底部面板切换、主题切换、状态计数、工具栏 Run/Build。
