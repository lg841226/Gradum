// The assistant island: the Gradum chat occupying the editor's grid cell
// while it is open. It owns no rail of its own: the session list renders in
// the shared rail (from the store's `sessions`) and the project root lives in
// the status bar (`projectRoot`); this feature reacts to both.
//
// Chats open as tabs, the way the editor lists its documents. Every tab owns
// a full stack of its own - a message column, a chat model and view, and a
// stream controller - so a background tab keeps receiving its run and the
// transcript is already there when the user switches back. The composer, the
// pickers and the tab strip are shared; only the active tab's stream drives
// them. The wiring is deferred to the first open, so merely loading the page
// posts nothing to the chat endpoints.

import {listModels, listSessions, sessionState} from "../../data/legatusApi.js";
import {STORAGE_KEYS} from "../../core/storageKeys.js";
import {readStorage, writeStorage} from "../../core/storage.js";
import {copy} from "../../ui/copy.js";
import {createIconButton} from "../../ui/components/button.js";
import {createTabStrip, renderTabStrip} from "../../ui/components/tab.js";
import {createChat} from "../chat/index.js";
import {createComposer} from "./composer.js";
import {createModelPicker, createPermissionPicker} from "./pickers.js";
import {createStreamController} from "./stream.js";
import {isFloatLayout} from "../floatPanels.js";
import {dragPointer} from "../splitter.js";

// The share of the body the first pane keeps when the divider is dragged. Held
// between a fifth and four fifths, so neither pane can be dragged shut: closing
// one outright is what the split toggle is for. The editor holds the same bounds.
const MIN_SHARE = 0.2;
const MAX_SHARE = 0.8;

// A fresh session id per chat, minted here so new chats are durable even
// before the server has seen them. crypto.randomUUID only exists in secure
// contexts; this page is often opened over plain http on the LAN, where it
// is undefined.
function newSessionId() {
  if (typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

export function mountAssistant(store, {canvas}) {
  const island = document.createElement("section");
  island.className = "assistant panel";
  island.hidden = true;

  // The chat splits the way the editor does: a fixed pair of panes over the
  // island, the right one mounted from the start and folded away by CSS until
  // the split is switched on. Each pane carries its own tab strip at its top
  // and its own composer at its foot, so a split pane is a full duplicate of
  // the chat surface.
  const makePane = (name) => {
    const pane = document.createElement("div");
    pane.className = "chat-pane";
    pane.dataset.pane = name;
    const strip = createTabStrip();
    pane.append(strip);
    return {pane, strip};
  };
  const left = makePane("left");
  const right = makePane("right");
  const strips = [left.strip, right.strip];

  const columns = document.createElement("div");
  columns.className = "chat-columns";

  // The divider is a handle, not just a hairline: pressing it re-shares the
  // body between the two panes along whichever axis the split is on, exactly
  // as the editor's divider does over the same store state.
  const divider = document.createElement("div");
  divider.className = "pane-divider";
  divider.setAttribute("role", "separator");
  divider.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const bounds = columns.getBoundingClientRect();
    const down = store.getState().splitDirection === "down";
    const span = down ? bounds.height : bounds.width;
    const start = down ? bounds.top : bounds.left;
    if (!span) {
      return;
    }
    dragPointer(down ? "row-resize" : "col-resize", (moveEvent) => {
      const offset = (down ? moveEvent.clientY : moveEvent.clientX) - start;
      const share = Math.min(Math.max(offset / span, MIN_SHARE), MAX_SHARE);
      store.setState({splitRatio: share});
    });
  });

  // Inactive tabs' message columns park here, out of both panes, until their
  // tab is hosted again.
  const vault = document.createElement("div");
  vault.hidden = true;

  columns.append(left.pane, divider, right.pane, vault);

  // A pane is a full duplicate of the chat surface: a message column and a
  // composer with its own model and permission pickers. Split - driven by the
  // menubar's existing split toggles through the store's `split` state, the
  // same one the editor answers to - shows two panes side by side, and each
  // composer sends into the tab its pane hosts.
  const makeUi = () => {
    const ui = {};
    let modelPicker;
    let permissionPicker;
    // Both menus are mutually exclusive; opening one closes the other. The
    // pickers are assigned before any menu can be opened, and the optional
    // call keeps the closure safe if a picker failed to mount.
    const closeOther = () => {
      modelPicker?.close();
      permissionPicker?.close();
    };
    modelPicker = createModelPicker({onOtherOpen: closeOther});
    permissionPicker = createPermissionPicker({onOtherOpen: closeOther});
    ui.modelPicker = modelPicker;
    ui.permissionPicker = permissionPicker;
    ui.composer = createComposer({
      chips: [modelPicker.chip, permissionPicker.chip],
      menus: [modelPicker.menu, permissionPicker.menu],
      onSend: () => send(ui),
      onStop: () => tabs.get(paneTabId(ui))?.stream.stop(),
    });
    return ui;
  };

  let wired = false;
  // The open chats, in tab order. A tab is `fresh` while it is a client-minted
  // session the server has never seen; fresh tabs stay out of storage, so a
  // reload always lands on real sessions.
  const tabs = new Map();
  let lastRoot = readStorage(STORAGE_KEYS.projectRoot, "");
  let sessionRequest = 0;

  const ui = makeUi();
  const splitUi = makeUi();
  const composer = ui.composer;

  // Each pane hosts its own tab, the way each editor group holds its own
  // document: a pane's strip picks what that pane shows, independently of the
  // other. The focused pane's tab is the island's active one and drives the
  // rail highlight and the menubar widget.
  const hosted = {left: null, right: null};
  let focusedSide = "left";

  function activeId() {
    return hosted[focusedSide] ?? hosted.left;
  }

  function sideOf(forUi) {
    return forUi === splitUi ? "right" : "left";
  }

  function paneTabId(forUi) {
    return hosted[sideOf(forUi)];
  }

  // The server stamps its own working directory on the page; the root must
  // never start empty because the browser cannot guess a path. `lastRoot` is
  // advanced before the state write so the root listener does not schedule a
  // second session fetch behind the one wire() already starts.
  function seedRoot() {
    const {projectRoot} = store.getState();
    if (projectRoot) {
      return;
    }
    const defaultRoot = document.documentElement.dataset.defaultRoot || "";
    if (!defaultRoot) {
      return;
    }
    lastRoot = defaultRoot;
    writeStorage(STORAGE_KEYS.projectRoot, defaultRoot);
    store.setState({projectRoot: defaultRoot});
  }

  async function refreshSessions() {
    const {projectRoot} = store.getState();
    const request = ++sessionRequest;
    const sessions = projectRoot
      ? await listSessions(projectRoot).catch(() => [])
      : [];
    // A root edit while the first request was in flight owns the list; a late
    // answer from the old root must not replace it.
    if (request === sessionRequest) {
      store.setState({sessions});
    }
  }

  // Each visible pane's chrome mirrors the tab it hosts, whatever the other
  // panes are doing; the busy flag is the island's, any tab generating.
  function syncChrome() {
    const busy = [...tabs.values()].some((tab) => tab.stream.streaming);
    for (const paneUi of [ui, splitUi]) {
      const tab = tabs.get(paneTabId(paneUi));
      paneUi.composer.setStreaming(tab?.stream.streaming ?? false);
      paneUi.composer.stopButton.hidden = !(tab?.stream.streaming ?? false);
    }
    store.setState({chatBusy: busy});
  }

  function persistTabs() {
    const ids = [...tabs.values()].filter((tab) => !tab.fresh).map((tab) => tab.id);
    writeStorage(STORAGE_KEYS.chatTabs, {tabs: ids, active: activeId()});
  }

  // The split state is the editor's: the menubar's split toggles write it and
  // both surfaces read it. The chat only splits with two or more tabs, and
  // never afloat, where there is no width worth splitting. The classes and the
  // ratio variable land on the pane body, exactly where the editor's land on
  // its own, and the same stylesheet language reads them.
  function layout() {
    const state = store.getState();
    const splitOn = Boolean(state.split) && !isFloatLayout() && tabs.size >= 2;
    columns.classList.toggle("is-split", splitOn);
    columns.classList.toggle("is-split-down", splitOn && state.splitDirection === "down");
    columns.style.setProperty("--split-ratio", String(state.splitRatio ?? 0.5));
    syncPanes();
  }

  // The left pane always hosts its tab; the right one shows its own only while
  // split. Every unhosted tab's column parks in the vault. Each hosted pane
  // ends with its own composer, so both sides are complete chat views.
  function syncPanes() {
    const splitOn = columns.classList.contains("is-split");
    const leftTab = tabs.get(hosted.left);
    const rightTab = splitOn ? tabs.get(hosted.right) : null;
    for (const tab of tabs.values()) {
      if (tab !== leftTab && tab !== rightTab) {
        vault.append(tab.messages);
      }
    }
    if (leftTab) {
      left.pane.append(leftTab.messages, ui.composer.root);
    }
    if (rightTab) {
      right.pane.append(rightTab.messages, splitUi.composer.root);
    }
  }

  // Hosting a tab in a pane is what a tab click in that pane's strip does; the
  // active-tab API (new chat, open session) hosts into the focused pane.
  function hostTab(side, id) {
    if (!tabs.has(id)) {
      return;
    }
    hosted[side] = id;
    layout();
    const tab = tabs.get(id);
    if (side === focusedSide) {
      store.setState({activeSession: tab.fresh ? null : id});
    }
    syncChrome();
    renderTabs();
    persistTabs();
    (side === "left" ? ui : splitUi).composer.focus();
  }

  function setActive(id) {
    hostTab(focusedSide, id);
  }

  function labelFor(tab) {
    if (tab.fresh) {
      return copy.agent.untitled;
    }
    const session = store.getState().sessions.find((item) => item.id === tab.id);
    return session?.title ?? tab.id;
  }

  let tabsSignature = null;

  function renderTabs() {
    const next = JSON.stringify({
      focusedSide,
      hosted,
      tabs: [...tabs.values()].map((tab) => [
        tab.id,
        tab.stream.streaming,
        labelFor(tab),
      ]),
    });
    if (next === tabsSignature) {
      return;
    }
    tabsSignature = next;

    // Each pane lists the open chats at its own top and marks the tab it
    // hosts, the way a split editor group keeps its own tab bar. A tab click
    // in a strip opens that chat in that strip's pane.
    const items = [...tabs.values()].map((tab) => ({
      id: tab.id,
      label: labelFor(tab),
      icon: "session",
      variant: "file",
      dirty: tab.stream.streaming,
      tooltip: labelFor(tab),
    }));
    for (const [side, strip] of [["left", left.strip], ["right", right.strip]]) {
      strip.hidden = tabs.size < 2;
      renderTabStrip(strip, items.map((item) => ({
        ...item,
        active: item.id === hosted[side],
      })), {
        onActivate: (node) => hostTab(side, node.dataset.id),
        onClose: (node) => closeTab(node.dataset.id),
        closeTooltip: copy.tooltip.closeTab,
      });
    }
  }

  function createTab({id = newSessionId(), fresh = true} = {}) {
    const messages = document.createElement("div");
    messages.className = "messages";

    const tab = {id, fresh, messages, started: false};
    tab.chat = createChat({
      messages,
      stopButton: composer.stopButton,
    });
    tab.stream = createStreamController({
      onStart: () => {
        syncChrome();
        renderTabs();
      },
      onFrame: (frame) => tab.chat.handleEvent(frame),
      onEnd: () => {
        tab.chat.finishTurn();
        syncChrome();
        renderTabs();
        void refreshSessions();
      },
      onError: (error) => {
        tab.chat.notice(copy.chat.sendFailed(error.message));
        syncChrome();
      },
    });

    tabs.set(id, tab);
    vault.append(messages);
    return tab;
  }

  function loadHistory(tab) {
    const {projectRoot} = store.getState();
    if (!projectRoot) {
      return;
    }
    sessionState(projectRoot, tab.id)
      .then((state) => {
        if (tabs.get(tab.id) === tab) {
          tab.chat.loadHistory(state?.messages ?? []);
        }
      })
      .catch((error) => {
        if (tabs.get(tab.id) === tab && !tab.fresh) {
          tab.chat.notice(copy.chat.loadFailed(error.message));
        }
      });
  }

  function wire() {
    if (wired) {
      return;
    }
    wired = true;

    seedRoot();
    void refreshSessions();

    // The stored tabs come back in their saved order; the saved active tab
    // leads. A reload mid-run reconnects as a fresh view: the server holds
    // the transcript, so the history load rebuilds what was on screen.
    const saved = readStorage(STORAGE_KEYS.chatTabs, {tabs: [], active: null});
    for (const id of saved.tabs ?? []) {
      const tab = createTab({id, fresh: false});
      loadHistory(tab);
    }
    if (tabs.size === 0) {
      createTab();
    }
    setActive(saved.active && tabs.has(saved.active) ? saved.active : tabs.keys().next().value);
    hosted.right = [...tabs.keys()].find((id) => id !== hosted.left) ?? null;
    layout();

    // An empty model selection means "let the server pick its default"; a
    // failed discovery leaves the runtime default as the only path. Both
    // panes' pickers take the same list.
    listModels()
      .then((models) => {
        ui.modelPicker.applyModels(models);
        splitUi.modelPicker.applyModels(models);
      })
      .catch(() => {
        // Discovery failed (no local host up); the server default still works.
      });
  }

  // The composer that sent owns the turn: its own input, model and permission
  // pickers are read and cleared, and its tab takes the run.
  function send(forUi = ui) {
    const text = forUi.composer.input.value.trim();
    const tab = tabs.get(paneTabId(forUi));
    if (!text || !tab || tab.stream.streaming) {
      return;
    }
    const {projectRoot} = store.getState();
    if (!projectRoot) {
      tab.chat.notice(copy.chat.rootMissing);
      return;
    }

    const started = tab.stream.start({
      message: text,
      projectRoot,
      sessionId: tab.id,
      loadContext: true,
      model: forUi.modelPicker.value() || null,
      toolMode: forUi.permissionPicker.value(),
    });
    if (!started) {
      return;
    }

    forUi.composer.clear();
    tab.started = true;
    if (tab.fresh) {
      tab.fresh = false;
      if (tab.id === activeId()) {
        store.setState({activeSession: tab.id});
      }
    }
    tab.chat.beginUserTurn(text);
    syncChrome();
    forUi.composer.focus();
  }

  // A new chat opens another tab rather than replacing anything; an untouched
  // active tab is reused instead of piling up empty ones.
  function newChat() {
    const active = tabs.get(activeId);
    if (active && active.fresh && !active.started) {
      composer.focus();
      return;
    }
    const tab = createTab();
    setActive(tab.id);
  }

  // Opening a past session reuses its tab when one is already open, and the
  // stored transcript is cold-loaded into that tab so the click visibly opens
  // the session.
  function openSession(sessionId) {
    const existing = tabs.get(sessionId);
    if (existing) {
      setActive(sessionId);
      return;
    }
    const tab = createTab({id: sessionId, fresh: false});
    tab.started = true;
    setActive(sessionId);
    loadHistory(tab);
  }

  function closeTab(id) {
    const tab = tabs.get(id);
    if (!tab) {
      return;
    }

    tab.stream.reset();
    tab.messages.remove();
    tabs.delete(id);

    for (const side of ["left", "right"]) {
      if (hosted[side] === id) {
        hosted[side] = tabs.keys().next().value ?? null;
      }
    }

    if (tabs.size === 0) {
      const fresh = createTab();
      focusedSide = "left";
      hosted.left = fresh.id;
      hosted.right = null;
      setActive(fresh.id);
      return;
    }
    layout();
    syncChrome();
    renderTabs();
    persistTabs();
    store.setState({activeSession: tabs.get(activeId())?.fresh ? null : activeId()});
  }

  store.subscribe((state) => {
    const root = state.projectRoot;
    if (root === lastRoot) {
      return;
    }
    lastRoot = root;
    writeStorage(STORAGE_KEYS.projectRoot, root);
    if (wired) {
      void refreshSessions();
    }
  });

  island.append(columns);
  canvas.appendChild(island);

  // Whichever pane the user last touched is the active one: its tab drives
  // the rail highlight and the menubar widget, as the editor's focused pane
  // owns the caret. Pointer and focus both count; capture catches clicks on
  // bubbles that never take focus themselves.
  const noteFocus = (event) => {
    const side = event.target.closest?.(".chat-pane")?.dataset.pane;
    if ((side === "left" || side === "right") && side !== focusedSide) {
      focusedSide = side;
      const tab = tabs.get(activeId());
      store.setState({activeSession: tab?.fresh ? null : activeId()});
      syncChrome();
      renderTabs();
      persistTabs();
    }
  };
  island.addEventListener("pointerdown", noteFocus, {capture: true});
  island.addEventListener("focusin", noteFocus, {capture: true});

  // Scroll-to-bottom: a circular button anchored to the composer's top-right
  // corner, visible only while the user has scrolled away from the newest
  // content. The active tab's column drives scrollTop; this listens to the
  // result of any of them.
  const scrollButton = createIconButton({
    icon: "scroll-down",
    tooltip: "Scroll to Bottom",
    onClick: () => {
      const tab = tabs.get(activeId());
      tab?.messages.scrollTo({
        top: tab.messages.scrollHeight,
        behavior: "smooth",
      });
      composer.focus();
    },
  });
  scrollButton.classList.add("composer-scroll-bottom");
  scrollButton.hidden = true;
  composer.root.append(scrollButton);

  const nearBottom = (el) =>
    el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  const updateScrollButton = () => {
    const tab = tabs.get(activeId());
    scrollButton.hidden = !tab || nearBottom(tab.messages);
  };
  island.addEventListener("scroll", updateScrollButton, {passive: true, capture: true});

  // The composer floats above the message column; the column reserves the
  // composer's live height through the --composer-h variable so the newest
  // bubble scrolls clear of it instead of parking underneath.
  new ResizeObserver(([entry]) => {
    const height = entry.borderBoxSize?.[0]?.blockSize ?? entry.contentRect.height;
    island.style.setProperty("--composer-h", `${Math.ceil(height)}px`);
  }).observe(composer.root);

  // Session titles arrive with the list; the strip labels follow them.
  store.subscribe(() => renderTabs());

  // The menubar's split toggles and the divider drag both write the shared
  // split state; the island follows the moment either moves. The ratio is
  // written straight onto the pane body - the variable the two panes' grow
  // factors read - while the shape (on/off, axis) only re-lays the panes out
  // when it actually changes.
  let splitSignature = null;
  store.subscribe((state) => {
    const ratio = String(state.splitRatio ?? 0.5);
    const next = `${state.split}/${state.splitDirection}/${ratio}`;
    if (next === splitSignature) {
      return;
    }
    const [wasSplit, wasDirection] = splitSignature?.split("/") ?? [];
    splitSignature = next;
    if (!wired) {
      return;
    }
    columns.style.setProperty("--split-ratio", ratio);
    if (wasSplit !== String(state.split) || wasDirection !== state.splitDirection) {
      layout();
    }
  });

  let signature = null;
  const render = (state) => {
    const next = String(state.chatOpen === true);
    if (next === signature) {
      return;
    }
    signature = next;

    island.hidden = !state.chatOpen;
    canvas.classList.toggle("chat-open", state.chatOpen === true);
    if (state.chatOpen) {
      wire();
    }
  };

  store.subscribe(render);
  render(store.getState());

  return {newChat, openSession};
}
