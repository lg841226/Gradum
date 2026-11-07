import {listModels, listSessions, sessionState} from "../../data/legatusApi.js";
import {STORAGE_KEYS} from "../../core/storageKeys.js";
import {readJsonStorage, readStorage, writeStorage} from "../../core/storage.js";
import {copy} from "../../ui/copy.js";
import {createIconButton} from "../../ui/components/button.js";
import {createTabStrip, renderTabStrip} from "../../ui/components/tab.js";
import {createChat} from "../chat/index.js";
import {createComposer} from "./composer.js";
import {createModelPicker, createPermissionPicker} from "./pickers.js";
import {createStreamController} from "./stream.js";
import {isFloatLayout} from "../floatPanels.js";
import {dragPointer, isDragging} from "../splitter.js";
import {clampShare} from "../../core/layout.js";

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
  const columns = document.createElement("div");
  columns.className = "chat-columns";

  const divider = document.createElement("div");
  divider.className = "pane-divider";
  divider.setAttribute("role", "separator");
  divider.addEventListener("pointerdown", (event) => {
    event.preventDefault();
    const bounds = columns.getBoundingClientRect();
    const down = store.getState().chatSplitDirection === "down";
    const span = down ? bounds.height : bounds.width;
    const start = down ? bounds.top : bounds.left;
    if (!span) {
      return;
    }
    let ratio = store.getState().chatSplitRatio ?? 0.5;
    let moved = false;

    dragPointer(down ? "row-resize" : "col-resize", (moveEvent) => {
      const offset = (down ? moveEvent.clientY : moveEvent.clientX) - start;
      ratio = clampShare(offset / span);
      columns.style.setProperty("--split-ratio", String(ratio));
      moved = true;
    }, () => {
      if (moved) {
        store.setState({chatSplitRatio: ratio});
      }
    });
  });


  const vault = document.createElement("div");
  vault.hidden = true;

  columns.append(left.pane, divider, right.pane, vault);

  const makeUi = () => {
    const ui = {};
    let modelPicker;
    let permissionPicker;

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
  const tabs = new Map();
  let lastRoot = readStorage(STORAGE_KEYS.projectRoot, "");
  let sessionRequest = 0;

  const ui = makeUi();
  const splitUi = makeUi();
  const composer = ui.composer;

  const workspace = {left: null, right: null, focused: "left"};
  const SIDES = ["left", "right"];
  const paneScroll = {left: true, right: true};
  let chatBusy = null;

  function activeId() {
    return workspace[workspace.focused] ?? workspace.left;
  }

  function sideOf(forUi) {
    return forUi === splitUi ? "right" : "left";
  }

  function paneTabId(forUi) {
    return workspace[sideOf(forUi)];
  }

  function detach(id) {
    for (const side of SIDES) {
      if (workspace[side] === id) {
        workspace[side] = null;
      }
    }
  }

  function attach(side, id) {
    detach(id);
    workspace[side] = id;
  }

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

    if (!projectRoot) {
      if (request === sessionRequest) {
        store.setState({sessions: []});
      }
      return;
    }

    let sessions;
    try {
      sessions = await listSessions(projectRoot);
    } catch (_error) {
      return;
    }
    if (request !== sessionRequest) {
      return;
    }

    const known = new Map(
      (store.getState().sessions ?? []).map((item) => [item.id, usableTitle(item.title)]),
    );
    store.setState({
      sessions: sessions.map((item) => ({
        ...item,
        title: usableTitle(item.title) ?? known.get(item.id) ?? "",
      })),
    });
  }

  function persistTabs() {
    const ids = [...tabs.values()].filter((tab) => !tab.fresh).map((tab) => tab.id);
    writeStorage(
      STORAGE_KEYS.chatTabs,
      JSON.stringify({
        tabs: ids,
        active: activeId(),
        left: workspace.left,
        right: workspace.right,
        focused: workspace.focused,
      }),
    );
  }

  function splitShown(state) {
    return Boolean(state.chatSplit) && !isFloatLayout() && tabs.size >= 2;
  }

  function restoreOwnership(saved) {
    const payload = saved && typeof saved === "object" ? saved : {};
    const liveId = (value) => typeof value === "string" && tabs.has(value);
    const leftOk = payload.left === null || liveId(payload.left);
    const rightOk = payload.right === null || liveId(payload.right);
    const focusedOk = payload.focused === "left" || payload.focused === "right";
    const distinct = payload.left !== payload.right || payload.left === null;

    if (!leftOk || !rightOk || !focusedOk || !distinct) {
      return;
    }
    workspace.left = payload.left;
    workspace.right = payload.right;
    workspace.focused = payload.focused;
  }

  // Ownership invariants, applied at the start of every render: a stale id is
  // dropped, a tab never belongs to both panes, the left pane takes the first
  // free tab, and the right pane holds a second tab only while split is shown.
  function repairOwnership(splitOn) {
    for (const side of SIDES) {
      if (workspace[side] != null && !tabs.has(workspace[side])) {
        workspace[side] = null;
      }
    }

    const ids = [...tabs.keys()];
    if (ids.length === 0) {
      workspace.left = null;
      workspace.right = null;
      workspace.focused = "left";
      return;
    }

    if (workspace.right === workspace.left) {
      workspace.right = null;
    }
    if (workspace.left == null) {
      workspace.left = ids.find((id) => id !== workspace.right) ?? ids[0];
    }
    if (workspace.right === workspace.left) {
      workspace.right = null;
    }
    if (splitOn && workspace.right == null) {
      workspace.right = ids.find((id) => id !== workspace.left) ?? null;
    }
    if (workspace.focused === "right" && workspace.right == null) {
      workspace.focused = "left";
    }
  }

  // The only writer of the pane DOM. Every entry point edits `workspace` or
  // `tabs` and then calls this once: split classes, pane contents, composer
  // streaming state, tab strips + the stored tab list all derive from that
  // model here, so a missed manual sync can no longer leave the panes +
  // strips disagreeing.
  function render() {
    const state = store.getState();
    const splitOn = splitShown(state);

    repairOwnership(splitOn);

    columns.classList.toggle("is-split", splitOn);
    columns.classList.toggle("is-split-down", splitOn && state.chatSplitDirection === "down");
    if (!isDragging()) {
      columns.style.setProperty("--split-ratio", String(state.chatSplitRatio ?? 0.5));
    }

    placePanes(splitOn);
    refreshScrollState(splitOn);

    ui.composer.setStreaming(tabs.get(workspace.left)?.stream.streaming ?? false);
    const rightTab = splitOn ? tabs.get(workspace.right) : null;
    splitUi.composer.setStreaming(rightTab?.stream.streaming ?? false);

    renderTabs();
    persistTabs();
    applyScrollButtons(splitOn);

    const busy = [...tabs.values()].some((tab) => tab.stream.streaming);
    if (busy !== chatBusy) {
      chatBusy = busy;
      store.setState({chatBusy: busy});
    }
  }

  function placePanes(splitOn) {
    const rightTab = splitOn ? tabs.get(workspace.right) : null;
    const hosts = [
      [left.pane, ui.composer.root, workspace.left],
      [right.pane, splitUi.composer.root, splitOn ? workspace.right : null],
    ];

    for (const [pane, composerRoot, id] of hosts) {
      const tab = tabs.get(id);
      if (tab && tab.messages.parentElement !== pane) {
        pane.append(tab.messages, composerRoot);
      }
    }

    for (const tab of tabs.values()) {
      const parked = tab !== tabs.get(workspace.left) && tab !== rightTab;
      if (parked && tab.messages.parentElement !== vault) {
        vault.append(tab.messages);
      }
    }
  }

  function hostTab(side, id) {
    if (!tabs.has(id)) {
      return;
    }
    attach(side, id);

    const tab = tabs.get(id);
    if (side === workspace.focused) {
      store.setState({activeSession: tab.fresh ? null : id});
    }
    render();
    (side === "left" ? ui : splitUi).composer.focus();
  }

  function setActive(id) {
    hostTab(workspace.focused, id);
  }

  const titleLimit = 512;

  function usableTitle(value) {
    if (typeof value !== "string") {
      return null;
    }
    const title = value.trim();
    if (!title || title.length > titleLimit || title.includes("\uFFFD")) {
      return null;
    }
    return title;
  }

  function firstLineTitle(text) {
    const line = String(text).split("\n").find((part) => part.trim().length > 0);
    const trimmed = line?.trim() ?? "";
    return trimmed ? Array.from(trimmed).slice(0, 48).join("") : null;
  }

  function labelFor(tab) {
    if (tab.fresh) {
      return copy.agent.untitled;
    }
    const local = usableTitle(tab.title);
    if (local && local !== tab.id) {
      return local;
    }
    const session = store.getState().sessions.find((item) => item.id === tab.id);
    const remote = usableTitle(session?.title);
    if (remote && remote !== tab.id) {
      tab.title = remote;
      return remote;
    }
    return tab.id;
  }

  let tabsSignature = null;

  function renderTabs() {
    const offline = store.getState().offline;
    const next = JSON.stringify({
      focused: workspace.focused,
      left: workspace.left,
      right: workspace.right,
      offline,
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

    const items = [...tabs.values()].map((tab) => ({
      id: tab.id,
      label: labelFor(tab),
      icon: "session",
      variant: "file",
      dirty: tab.stream.streaming,
      offline,
      tooltip: labelFor(tab),
    }));

    for (const [side, strip] of [["left", left.strip], ["right", right.strip]]) {
      strip.hidden = tabs.size < 2;
      renderTabStrip(strip, items.map((item) => ({
        ...item,
        active: item.id === workspace[side],
      })), {
        onActivate: (node, id) => hostTab(side, id),
        onClose: (node, id) => closeTab(id),
        closeTooltip: copy.tooltip.closeTab,
      });
    }
  }

  function createTab({id = newSessionId(), fresh = true} = {}) {
    const messages = document.createElement("div");
    messages.className = "messages";

    const tab = {id, fresh, messages, started: false};

    tab.watch = new MutationObserver(() => scheduleScrollSync());
    tab.watch.observe(messages, {childList: true, subtree: true, characterData: true});
    tab.chat = createChat({messages, onRetry: () => retryTab(tab)});
    tab.stream = createStreamController({
      onStart: () => render(),
      onFrame: (frame) => tab.chat.handleEvent(frame),
      onEnd: () => {
        tab.chat.finishTurn();
        render();
        void refreshSessions();
      },
      onError: (error) => {
        tab.chat.fail({message: error.message, code: "CLIENT_ERROR"});
        render();
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

    const saved = readJsonStorage(STORAGE_KEYS.chatTabs, {tabs: [], active: null});
    const savedTabs = Array.isArray(saved?.tabs) ? saved.tabs : [];
    for (const id of savedTabs) {
      const tab = createTab({id, fresh: false});
      loadHistory(tab);
    }

    if (tabs.size === 0) {
      createTab();
    }

    restoreOwnership(saved);
    setActive(saved.active && tabs.has(saved.active) ? saved.active : tabs.keys().next().value);

    listModels()
      .then((models) => {
        ui.modelPicker.applyModels(models);
        splitUi.modelPicker.applyModels(models);
      })
      .catch(() => {
      });
  }

  function sendText(forUi, text, {retry = false} = {}) {
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

    tab.lastUser = text;
    tab.title ??= firstLineTitle(text);
    if (retry) {
      tab.chat.discardTurn();
    } else {
      forUi.composer.clear();
    }
    tab.started = true;
    if (tab.fresh) {
      tab.fresh = false;
      if (tab.id === activeId()) {
        store.setState({activeSession: tab.id});
      }
    }
    tab.chat.beginUserTurn(text);
    render();
    forUi.composer.focus();
  }

  function send(forUi = ui) {
    sendText(forUi, forUi.composer.input.value.trim());
  }

  function retryTab(tab) {
    if (!tab.lastUser || tab.stream.streaming) {
      return;
    }
    const side = workspace.left === tab.id
      ? "left"
      : workspace.right === tab.id ? "right" : null;
    if (!side) {
      return;
    }
    sendText(side === "left" ? ui : splitUi, tab.lastUser, {retry: true});
  }

  function newChat() {
    const active = tabs.get(activeId());
    if (active && active.fresh && !active.started) {
      composer.focus();
      return;
    }
    const tab = createTab();
    setActive(tab.id);
  }

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
    tab.watch?.disconnect();
    tab.messages.remove();
    tabs.delete(id);
    detach(id);

    if (tabs.size === 0) {
      const fresh = createTab();
      workspace.focused = "left";
      workspace.left = fresh.id;
      workspace.right = null;
      setActive(fresh.id);
      return;
    }

    render();
    const active = tabs.get(activeId());
    store.setState({activeSession: active && !active.fresh ? activeId() : null});
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

  const noteFocus = (event) => {
    const side = event.target.closest?.(".chat-pane")?.dataset.pane;
    if ((side === "left" || side === "right") && side !== workspace.focused) {
      workspace.focused = side;
      render();
      const tab = tabs.get(activeId());
      store.setState({activeSession: tab && !tab.fresh ? activeId() : null});
    }
  };
  island.addEventListener("pointerdown", noteFocus, {capture: true});
  island.addEventListener("focusin", noteFocus, {capture: true});

  const BOTTOM_SLACK = 40;

  const isAtBottom = (el) =>
    el.scrollHeight - el.scrollTop - el.clientHeight < BOTTOM_SLACK;

  const makeScrollButton = (side) => {
    const button = createIconButton({
      icon: "scroll-down",
      tooltip: "Scroll to Bottom",
      onClick: () => {
        const tab = tabs.get(workspace[side]);
        if (!tab) {
          return;
        }
        tab.messages.scrollTo({
          top: tab.messages.scrollHeight,
          behavior: "smooth",
        });
        (side === "left" ? ui : splitUi).composer.focus();
      },
    });
    button.classList.add("composer-scroll-bottom");
    button.hidden = true;
    (side === "left" ? ui : splitUi).composer.root.append(button);
    return button;
  };

  const leftScroll = makeScrollButton("left");
  const rightScroll = makeScrollButton("right");

  function refreshScrollState(splitOn) {
    let changed = false;
    for (const side of SIDES) {
      const tab = side === "right" && !splitOn ? null : tabs.get(workspace[side]);
      const atBottom = tab ? isAtBottom(tab.messages) : true;
      if (atBottom !== paneScroll[side]) {
        paneScroll[side] = atBottom;
        changed = true;
      }
    }
    return changed;
  }

  function applyScrollButtons(splitOn) {
    leftScroll.hidden = paneScroll.left;
    rightScroll.hidden = !splitOn || paneScroll.right;
  }

  let scrollFrame = 0;

  function scheduleScrollSync() {
    if (scrollFrame) {
      return;
    }
    scrollFrame = requestAnimationFrame(() => {
      scrollFrame = 0;
      if (refreshScrollState(splitShown(store.getState()))) {
        render();
      }
    });
  }

  for (const pane of [left.pane, right.pane]) {
    pane.addEventListener("scroll", scheduleScrollSync, {passive: true, capture: true});
  }
  window.addEventListener("resize", scheduleScrollSync);

  new ResizeObserver(([entry]) => {
    const height = entry.borderBoxSize?.[0]?.blockSize ?? entry.contentRect.height;
    island.style.setProperty("--composer-h", `${Math.ceil(height)}px`);
  }).observe(composer.root);

  store.subscribe(() => renderTabs());

  const splitState = store.getState();
  let splitSignature = `${Boolean(splitState.chatSplit)}/${splitState.chatSplitDirection ?? ""}`;
  let splitRatio = String(splitState.chatSplitRatio ?? 0.5);
  store.subscribe((state) => {
    const ratio = String(state.chatSplitRatio ?? 0.5);
    const ratioChanged = ratio !== splitRatio;
    splitRatio = ratio;
    if (!isDragging()) {
      columns.style.setProperty("--split-ratio", ratio);
    }

    const mode = `${Boolean(state.chatSplit)}/${state.chatSplitDirection ?? ""}`;
    if (mode !== splitSignature) {
      splitSignature = mode;
      if (wired) {
        render();
      }
      return;
    }
    if (ratioChanged && wired) {
      scheduleScrollSync();
    }
  });

  let openSignature = null;
  const renderOpen = (state) => {
    const next = String(state.chatOpen === true);
    if (next === openSignature) {
      return;
    }
    openSignature = next;

    island.hidden = !state.chatOpen;
    canvas.classList.toggle("chat-open", state.chatOpen === true);
    if (state.chatOpen) {
      wire();
      scheduleScrollSync();
    }
  };

  store.subscribe(renderOpen);
  renderOpen(store.getState());

  function setSplit(on, direction) {
    store.setState({
      chatSplit: on === true,
      chatSplitDirection: direction === "down" ? "down" : "right",
    });
  }

  return {newChat, openSession, setSplit};
}
