import {copy} from "./ui/copy.js";
import {formatTime} from "./core/dom.js";
import {activeDoc, createStore} from "./core/store.js";
import {mountRail} from "./features/rail.js";
import {HttpError} from "./data/http.js";
import {buildSkill, deploySkill, listSkills, loadSkill} from "./data/skillsApi.js";
import {createEditor} from "./createEditor.js";
import {mountPanels} from "./features/panels.js";
import {mountCompact} from "./features/compact.js";
import {mountConsole} from "./features/console.js";
import {mountMenubar} from "./features/menubar.js";
import {mountMinimap} from "./features/minimap.js";
import {mountSplitters} from "./features/splitter.js";
import {mountStatusBar} from "./features/statusbar.js";
import {mountFloatPanels} from "./features/floatPanels.js";
import {mountAssistant} from "./features/assistant/index.js";
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

// The host half of the editor: where a buffer comes from, what a new file starts
// as + how a typed name becomes a real file. These are the only places this
// shell touches the skill endpoints; the editor below only sees callbacks.
const starter = document.getElementById("starterTemplate")?.textContent ?? "";

const documents = {
  async open(name) {
    try {
      const source = await loadSkill(name);
      return {source: source === null ? starter : source};
    } catch (error) {
      if (error instanceof HttpError) {
        const text = error.status === 401 || error.status === 403
          ? copy.status.unauthorized
          : copy.status.loadFailed(name, error.detail);
        return {error: text};
      }
      return {error: copy.status.unreachable};
    }
  },
  create({files, names}) {
    let index = 1;
    while (files.includes(`NewSkill${index}.kt`) || names.includes(`NewSkill${index}.kt`)) {
      index++;
    }
    return {name: `NewSkill${index}.kt`, source: starter};
  },
  rename(rawName) {
    const bare = String(rawName).trim().replace(/\.kt$/, "");
    return /^[A-Za-z_][A-Za-z0-9_]*$/.test(bare) ? `${bare}.kt` : null;
  },
  save: (name, source) => deploySkill(name, source),
  build: (name, source) => buildSkill(name, source),
  list: listSkills,
};

const getActiveDoc = activeDoc;

// Host commands. The editor container owns the listener; these entries say what
// a key means + when it applies. Edit keys the code surface consumed never
// reach here, and the first enabled entry for a key wins.
let assistant = null;

const hasDocument = ({getActiveDoc: resolve, state}) => !state.reader && Boolean(resolve(state));

const commands = [
  {
    id: "new-chat",
    keys: ["Mod+N"],
    enabled: ({state, isTextEntry}) => state.chatOpen && !isTextEntry,
    run: () => assistant.newChat(),
  },
  {
    id: "new-document",
    keys: ["Mod+N"],
    enabled: ({state, isTextEntry}) => !state.chatOpen && !state.reader && !isTextEntry,
    run: ({tabs}) => tabs.create(),
  },
  {
    id: "close-document",
    keys: ["Mod+W"],
    enabled: (context) => !context.isTextEntry && hasDocument(context),
    run: ({tabs}) => tabs.closeActive(),
  },
  {
    id: "show-problems",
    keys: ["Mod+1"],
    run: ({showPanel}) => showPanel?.("problems"),
  },
  {
    id: "show-output",
    keys: ["Mod+2"],
    run: ({showPanel}) => showPanel?.("output"),
  },
  {
    id: "run",
    keys: ["Mod+Enter"],
    enabled: (context) => !context.isTextEntry && hasDocument(context),
    run: ({editor}) => void editor.run(),
  },
  {
    id: "build",
    keys: [],
    enabled: (context) => !context.isTextEntry && hasDocument(context),
    run: ({editor}) => void editor.build(),
  },
  {
    id: "refresh-files",
    keys: [],
    run: () => {
      documents.list()
        .then((files) => store.setState({files}))
        .catch(() => {
          // The rail keeps the names it already knows while the server is down.
        });
    },
  },
];

// Features are wired here so they never import each other. The editor island
// exposes its tab strip + code-surface API; the panels island exposes the two
// body elements that diagnostics + the console render into.
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
  const editor = createEditor(store, {
    canvas,
    showPanel: (name) => panels.show(name),
    documents,
    getActiveDoc,
    commands,
  });
  const tabs = editor.tabs;

  assistant = mountAssistant(store, {canvas});
  const rail = mountRail(store, {
    canvas,
    onOpen: (name) => void tabs.open(name),
    onCreate: () => (store.getState().chatOpen ? assistant.newChat() : tabs.create()),
    onOpenSession: (id) => assistant.openSession(id),
  });
  mountFloatPanels(store, {canvas});

  mountDiagnostics(store, {body: panels.problemsBody, editor, getActiveDoc});
  mountConsole(store, {body: panels.outputBody});
  for (const pane of editor.panes) {
    mountMinimap(store, {
      container: pane.main,
      source: pane.source,
      highlightCode: pane.highlightCode,
      getActiveDoc,
    });
  }
  mountMenubar(store, {
    canvas,
    onRun: () => editor.commands.run("run"),
    onBuild: () => editor.commands.run("build"),
    onToggleSplit: (direction) => editor.toggleSplit(direction),
    theme: editor.theme,
  });
  mountSplitters(store, {canvas, rail: rail.panel, bottom: panels.panel});
  mountStatusBar(store, {canvas, getActiveDoc});
  mountTooltip({container: canvas});

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

  editor.commands.run("refresh-files");

  void tabs.open(store.getState().openFile || DEFAULT_SKILL);

  requestAnimationFrame(() => {
    requestAnimationFrame(() => canvas.classList.remove("is-booting"));
  });
} catch (error) {
  console.error(error);
  document.body.replaceChildren();
  document.body.style.color = "#000000";
  document.body.style.background = "#ffffff";
  document.documentElement.style.background = "#ffffff";
  document.body.textContent = error.message || String(error);
}
