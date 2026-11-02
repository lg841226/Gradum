import {copy} from "./ui/copy.js";
import {formatTime} from "./core/dom.js";
import {createStore} from "./core/store.js";
import {mountRail} from "./features/rail.js";
import {mountTabs} from "./features/tabs.js";
import {listSkills} from "./data/skillsApi.js";
import {mountEditor} from "./features/editor.js";
import {mountPanels} from "./features/panels.js";
import {mountCompact} from "./features/compact.js";
import {mountConsole} from "./features/console.js";
import {mountMenubar} from "./features/menubar.js";
import {mountMinimap} from "./features/minimap.js";
import {mountSplitters} from "./features/splitter.js";
import {mountStatusBar} from "./features/statusbar.js";
import {mountFloatPanels} from "./features/floatPanels.js";
import {mountAssistant} from "./features/assistant";
import {mountTooltip} from "./ui/components/tooltip.js";
import {mountDiagnostics} from "./features/diagnostics.js";
import {createLayoutWriter, loadLayout} from "./core/persistence.js";
import {STORAGE_KEYS} from "./core/storageKeys.js";
import {readStorage} from "./core/storage.js";

const DEFAULT_SKILL = "HelloSkill.kt";

const canvas = document.getElementById("canvas");

const readerMark = document.documentElement.getAttribute("data-reader");

const store = createStore({
  log: [],
  docs: [],
  files: [],
  sessions: [],
  chatTabs: [],
  nextId: 1,
  indent: 4,
  focusToken: 0,
  cursorCount: 1,
  sourceRevision: 0,
  cursor: {line: 1, column: 1},
  view: "problems",
  panel: "problems",
  status: {text: "", tone: "neutral"},
  busyTask: null,
  activeId: null,
  selection: null,
  activeSession: null,
  busy: false,
  split: false,
  chatOpen: false,
  chatBusy: false,
  autoRail: false,
  collapsed: false,
  autoBottom: false,
  minimapHidden: false,
  railCollapsed: false,
  treeCollapsed: false,
  railWidth: 208,
  splitRatio: 0.5,
  bottomHeight: 200,
  splitDirection: "right",
  projectRoot: readStorage(STORAGE_KEYS.projectRoot, ""),
  ...loadLayout(),
  authed: readerMark === null,
  reader: readerMark !== null,
});

store.subscribe(createLayoutWriter());

// Features are wired here so they never import each other. The editor island
// exposes its tab strip and code-surface API; the panels island exposes the two
// body elements that diagnostics and the console render into.
//
// The compact feature is mounted first so it is the first listener to run: it
// settles the size-driven flags before the panels render from them.
//
// The whole sequence sits in one try block. The mounts append as they run, so
// an exception halfway would otherwise leave a plausible half of the interface
// on screen with nothing saying it is broken. One failure wipes the page down
// to a single line, black on white: a broken build never passes for a working
// one, and the stack stays in the console.
try {
  mountCompact(store, {canvas});
  const panels = mountPanels(store, {canvas});
  const editor = mountEditor(store, {canvas, showPanel: (name) => panels.show(name)});
  const [primaryPane, copyPane] = editor.panes;
  const tabs = mountTabs(store, {
    strips: [
      {strip: primaryPane.tabStrip},
      {
        strip: copyPane.tabStrip,
        onClose: () => editor.unsplit(),
        closeTooltip: copy.tooltip.unsplit,
      },
    ],
  });

  const assistant = mountAssistant(store, {canvas});
  const rail = mountRail(store, {
    canvas,
    onOpen: (name) => void tabs.open(name),
    onCreate: () => (store.getState().chatOpen ? assistant.newChat() : tabs.create()),
    onOpenSession: (id) => assistant.openSession(id),
  });
  mountFloatPanels(store, {canvas});

  mountDiagnostics(store, {body: panels.problemsBody, editor});
  mountConsole(store, {body: panels.outputBody});
  for (const pane of editor.panes) {
    mountMinimap(store, {
      container: pane.main,
      source: pane.source,
      highlightCode: pane.highlightCode,
    });
  }
  mountMenubar(store, {
    canvas,
    onRun: () => void editor.run(),
    onBuild: () => void editor.build(),
    onToggleSplit: (direction) => editor.toggleSplit(direction),
  });
  mountSplitters(store, {canvas, rail: rail.panel, bottom: panels.panel});
  mountStatusBar(store, {canvas});
  mountTooltip();

  window.addEventListener("keydown", (event) => {
    const command = event.metaKey || event.ctrlKey;
    if (!command) {
      return;
    }

    if (event.key === "n") {
      event.preventDefault();
      if (store.getState().chatOpen) {
        assistant.newChat();
      } else {
        tabs.create();
      }
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
  const pinScroll = () => {
    if (window.scrollY !== 0 || window.scrollX !== 0) {
      window.scrollTo(0, 0);
    }
  };
  window.visualViewport?.addEventListener("resize", pinScroll);
  window.addEventListener("scroll", pinScroll, {passive: true});

  store.setState({
    log: [...store.getState().log, {time: formatTime(), message: copy.log.ready(), tone: null}],
  });

  listSkills()
    .then((files) => store.setState({files}))
    .catch(() => {
      // The rail stays empty until the server becomes reachable.
    });

  void tabs.open(store.getState().openFile || DEFAULT_SKILL);

  requestAnimationFrame(() => {
    requestAnimationFrame(() => document.documentElement.classList.remove("is-booting"));
  });
} catch (error) {
  console.error(error);
  document.body.replaceChildren();
  document.body.style.color = "#000000";
  document.body.style.background = "#ffffff";
  document.documentElement.style.background = "#ffffff";
  document.body.textContent = error.message || String(error);
}
