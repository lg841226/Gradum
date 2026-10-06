import {formatTime} from "./core/dom.js";
import {createStore} from "./core/store.js";
import {listSkills} from "./data/skillsApi.js";
import {mountCompact} from "./features/compact.js";
import {mountConsole} from "./features/console.js";
import {mountDiagnostics} from "./features/diagnostics.js";
import {mountEditor} from "./features/editor.js";
import {mountMenubar} from "./features/menubar.js";
import {mountMinimap} from "./features/minimap.js";
import {mountPanels} from "./features/panels.js";
import {mountRail} from "./features/rail.js";
import {mountSplitters} from "./features/splitter.js";
import {mountStatusBar} from "./features/statusbar.js";
import {mountTabs} from "./features/tabs.js";
import {copy} from "./ui/copy.js";

const DEFAULT_SKILL = "HelloSkill.kt";

const canvas = document.getElementById("canvas");

const store = createStore({
  log: [],
  docs: [],
  files: [],
  nextId: 1,
  focusToken: 0,
  sourceRevision: 0,
  cursor: {line: 1, column: 1},
  status: {text: "", tone: "neutral"},
  view: "problems",
  panel: "problems",
  busy: false,
  activeId: null,
  autoRail: false,
  collapsed: false,
  autoBottom: false,
  railCollapsed: false,
  treeCollapsed: false,
  railWidth: 208,
  bottomHeight: 200,
});

// Features are wired here so they never import each other. The editor island
// exposes its tab strip and code-surface API; the panels island exposes the two
// body elements that diagnostics and the console render into.
//
// The compact feature is mounted first so it is the first listener to run: it
// settles the size-driven flags before the panels render from them.
mountCompact(store, {canvas});
const panels = mountPanels(store, {canvas});
const editor = mountEditor(store, {canvas, showPanel: (name) => panels.show(name)});
const tabs = mountTabs(store, {strip: editor.tabStrip});
const rail = mountRail(store, {
  canvas,
  onOpen: (name) => void tabs.open(name),
  onCreate: () => tabs.create(),
});

mountDiagnostics(store, {body: panels.problemsBody, editor});
mountConsole(store, {body: panels.outputBody});
mountMinimap(store, {
  body: editor.body,
  source: editor.source,
  highlightCode: editor.highlightCode,
});
mountMenubar(store, {
  canvas,
  onRun: () => void editor.run(),
});
mountSplitters(store, {canvas, rail: rail.panel, bottom: panels.panel});
mountStatusBar(store, {canvas});

window.addEventListener("keydown", (event) => {
  if (!(event.metaKey || event.ctrlKey)) return;
  if (event.key === "n") {
    event.preventDefault();
    tabs.create();
  } else if (event.key === "w") {
    event.preventDefault();
    tabs.closeActive();
  } else if (event.key === "1") {
    event.preventDefault();
    panels.show("problems");
  } else if (event.key === "2") {
    event.preventDefault();
    panels.show("output");
  }
});

store.setState({
  log: [...store.getState().log, {time: formatTime(), message: copy.log.ready(), tone: null}],
});

listSkills()
  .then((files) => store.setState({files}))
  .catch(() => {
    // The rail stays empty until the server becomes reachable.
  });

void tabs.open(DEFAULT_SKILL);
