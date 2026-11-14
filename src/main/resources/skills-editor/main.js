import {copy} from "./ui/copy.js";
import {formatTime} from "./core/dom.js";
import {activeDoc, createStore} from "./core/store.js";
import {HttpError, watchConnection} from "./data/http.js";
import {buildSkill} from "./data/skillsApi.js";
import {deploySkill, fetchDirectory, fetchTree, filePaths, readFile, writeFile} from "./data/workspaceApi.js";
import {createEditor} from "./createEditor.js";
import {mountCompact} from "./features/compact.js";
import {mountConsole} from "./features/console.js";
import {mountMenubar} from "./features/menubar.js";
import {mountMinimap} from "./features/minimap.js";
import {mountMonitor} from "./features/monitor.js";
import {mountSettings} from "./features/settings.js";
import {mountStatusBar} from "./features/statusbar.js";
import {createPanelRegistry, mountPanelRegistry} from "./features/panelRegistry.js";
import {mountTooltip} from "./ui/components/tooltip.js";
import {mountDiagnostics} from "./features/diagnostics.js";
import {createLayoutWriter, loadLayout} from "./core/persistence.js";
import {createInitialState} from "./core/schema.js";
import {applyCodeFontFamily, remeasureAfterFonts} from "./core/fonts.js";
import {STORAGE_KEYS} from "./core/storageKeys.js";
import {readStorage} from "./core/storage.js";

const DEFAULT_SKILL = "skills/HelloSkill.kt";

const userStyles = new Map();

// ui/ stylesheets are plain files, so a reload is just re-reading them into the
// style nodes they already own.
async function reloadUserStyles(paths, prune = false) {
  const cssPaths = paths.filter((path) => path.startsWith("ui/") && path.toLowerCase().endsWith(".css"));
  if (prune) {
    for (const [path, node] of userStyles) {
      if (!cssPaths.includes(path)) {
        node.remove();
        userStyles.delete(path);
      }
    }
  }
  await Promise.all(cssPaths.map(async (path) => {
    let node = userStyles.get(path);
    if (!node) {
      node = document.createElement("style");
      node.dataset.workspaceStyle = path;
      document.head.appendChild(node);
      userStyles.set(path, node);
    }
    try {
      const source = await readFile(path);
      if (source !== null) {
        node.textContent = source;
      }
    } catch (_error) {
    }
  }));
}

const canvas = document.getElementById("canvas");

const readerMark = document.documentElement.getAttribute("data-reader");

const layout = loadLayout();
const startupMode = layout.startupView === "developer"
  ? {chatOpen: false}
  : layout.startupView === "agent"
    ? {chatOpen: true}
    : {};

const store = createStore({
  ...createInitialState(),
  projectRoot: readStorage(STORAGE_KEYS.projectRoot, ""),
  ...layout,
  ...startupMode,
  authed: readerMark === null,
  reader: readerMark !== null,
});

watchConnection((reachable) => store.setState({offline: !reachable}));

store.subscribe(createLayoutWriter());

applyCodeFontFamily(canvas, store.getState());

// The host half of the editor: where a buffer comes from, what a new file starts
// as and how a typed name becomes a real file. These are the only places this
// shell touches the skill endpoints; the editor below only sees callbacks.
const starter = document.getElementById("starterTemplate")?.textContent ?? "";

const documents = {
  async open(path) {
    try {
      const source = await readFile(path);
      return {source: source === null ? starter : source};
    } catch (error) {
      if (error instanceof HttpError) {
        const text = error.status === 401 || error.status === 403
          ? copy.status.unauthorized
          : copy.status.loadFailed(path, error.detail);
        return {error: text};
      }
      return {error: copy.status.unreachable};
    }
  },
  create({files, names}) {
    let index = 1;
    while (files.includes(`skills/NewSkill${index}.kt`) || names.includes(`skills/NewSkill${index}.kt`)) {
      index++;
    }
    return {name: `skills/NewSkill${index}.kt`, source: starter};
  },
  rename(rawName, currentPath) {
    const raw = String(rawName).trim();
    if (!raw) {
      return null;
    }
    const currentRoot = String(currentPath || DEFAULT_SKILL).split("/")[0];
    const slash = raw.indexOf("/");
    const root = slash >= 0 ? raw.slice(0, slash) : currentRoot;
    const relative = (slash >= 0 ? raw.slice(slash + 1) : raw).trim();
    if (root === "skills") {
      const bare = relative.replace(/\.kt$/, "");
      return /^[A-Za-z_][A-Za-z0-9_]*$/.test(bare) ? `skills/${bare}.kt` : null;
    }
    if (root === "ui") {
      const parts = relative.split("/");
      if (!relative || relative.includes("\\")
        || parts.some((part) => !part || part === "." || part === "..")) {
        return null;
      }
      return `ui/${relative}`;
    }
    return null;
  },
  async save(path, source) {
    const root = String(path).split("/")[0];
    const result = root === "skills"
      ? await deploySkill(path, source)
      : await writeFile(path, source);
    if (root !== "skills" && result.ok && path.toLowerCase().endsWith(".css")) {
      await reloadUserStyles([path]);
    }
    return result;
  },
  build: (path, source) => buildSkill(path.slice("skills/".length), source),
  list: fetchTree,
};

const getActiveDoc = activeDoc;

// Host commands. The editor container owns the listener; these entries say what
// a key means and when it applies. Edit keys the code surface consumed never
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
        .then((tree) => {
          const files = filePaths(tree);
          store.setState({tree, files});
          return reloadUserStyles(files, true);
        })
        .catch(() => {
        });
    },
  },
];

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
  let tabs = null;
  const panels = mountPanelRegistry(store, {
    canvas,
    panels: createPanelRegistry({
      store,
      onOpen: (name) => void tabs?.open(name),
      onCreate: () => (store.getState().chatOpen ? assistant?.newChat() : tabs?.create()),
      onOpenSession: (id) => assistant?.openSession(id),
      listDirectory: (path) => fetchDirectory(store.getState().projectRoot, path),
      onPick: (path) => assistant?.insertPath(path),
      onOpenFile: (path) => {
        if (path.startsWith("skills/") || path.startsWith("ui/")) {
          void tabs?.open(path);
        }
      },
    }),
  });
  const editor = createEditor(store, {
    canvas,
    showPanel: (name) => panels.api.bottom.show(name),
    documents,
    getActiveDoc,
    commands,
  });
  tabs = editor.tabs;
  assistant = panels.api.assistant;

  mountDiagnostics(store, {body: panels.api.bottom.problemsBody, editor, getActiveDoc});
  mountConsole(store, {body: panels.api.bottom.outputBody});
  for (const pane of editor.panes) {
    mountMinimap(store, {
      container: pane.main,
      source: pane.source,
      highlightCode: pane.highlightCode,
      getActiveDoc,
    });
  }
  const settings = mountSettings(store, {canvas, theme: editor.theme});
  mountMenubar(store, {
    canvas,
    panelMenus: panels.menus,
    onRun: () => editor.commands.run("run"),
    onBuild: () => editor.commands.run("build"),
    onToggleSplit: (direction) => {
      const state = store.getState();
      if (state.chatOpen === true) {
        const next = direction === "down" ? "down" : "right";
        assistant.setSplit(
          !(Boolean(state.chatSplit) && state.chatSplitDirection === next),
          next,
        );
        return;
      }
      editor.toggleSplit(direction);
    },
    onToggleSettings: () => settings.toggle(),
  });
  const statusBar = mountStatusBar(store, {canvas, getActiveDoc});
  mountMonitor({host: statusBar.right});
  mountTooltip({container: canvas});

  remeasureAfterFonts();

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

  if (store.getState().restoreFile !== false) {
    void tabs.open(store.getState().openFile || DEFAULT_SKILL);
  }

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
