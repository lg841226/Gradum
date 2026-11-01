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
import {renderTabStrip, createTabStrip} from "../../ui/components/tab.js";
import {createChat} from "../chat/index.js";
import {createComposer} from "./composer.js";
import {createModelPicker, createPermissionPicker} from "./pickers.js";
import {createStreamController} from "./stream.js";

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

  const tabStrip = createTabStrip();

  let modelPicker;
  let permissionPicker;

  // Both menus are mutually exclusive; opening one closes the other. The
  // pickers are assigned before any menu can be opened, and the optional call
  // keeps the closure safe if a picker failed to mount.
  const closeOther = () => {
    modelPicker?.close();
    permissionPicker?.close();
  };
  modelPicker = createModelPicker({onOtherOpen: closeOther});
  permissionPicker = createPermissionPicker({onOtherOpen: closeOther});

  let wired = false;
  // The open chats, in tab order. A tab is `fresh` while it is a client-minted
  // session the server has never seen; fresh tabs stay out of storage, so a
  // reload always lands on real sessions.
  const tabs = new Map();
  let activeId = null;
  let lastRoot = readStorage(STORAGE_KEYS.projectRoot, "");
  let sessionRequest = 0;

  const composer = createComposer({
    chips: [modelPicker.chip, permissionPicker.chip],
    menus: [modelPicker.menu, permissionPicker.menu],
    onSend: () => send(),
    onStop: () => tabs.get(activeId)?.stream.stop(),
  });

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

  // The composer, the stop button and the busy flag are shared chrome: they
  // always mirror the active tab, whatever the background tabs are doing.
  function syncChrome() {
    const active = tabs.get(activeId);
    const busy = [...tabs.values()].some((tab) => tab.stream.streaming);
    composer.setStreaming(active?.stream.streaming ?? false);
    composer.stopButton.hidden = !(active?.stream.streaming ?? false);
    store.setState({chatBusy: busy});
  }

  function persistTabs() {
    const ids = [...tabs.values()].filter((tab) => !tab.fresh).map((tab) => tab.id);
    writeStorage(STORAGE_KEYS.chatTabs, {tabs: ids, active: activeId});
  }

  function setActive(id) {
    if (!tabs.has(id)) {
      return;
    }
    for (const tab of tabs.values()) {
      tab.messages.hidden = tab.id !== id;
    }
    activeId = id;
    const tab = tabs.get(id);
    // A fresh tab has no server identity yet; the menubar widget and the rail
    // read `activeSession`, and both render "New session" for null.
    store.setState({activeSession: tab.fresh ? null : id});
    tab.messages.scrollTo({top: tab.messages.scrollHeight});
    syncChrome();
    renderTabs();
    persistTabs();
    composer.focus();
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
    const next = JSON.stringify([
      ...tabs.values()].map((tab) => [
      tab.id,
      tab.stream.streaming,
      labelFor(tab),
      tab.id === activeId,
    ]));
    if (next === tabsSignature) {
      return;
    }
    tabsSignature = next;

    tabStrip.hidden = tabs.size < 2;
    renderTabStrip(tabStrip, [...tabs.values()].map((tab) => ({
      id: tab.id,
      label: labelFor(tab),
      icon: "session",
      variant: "file",
      active: tab.id === activeId,
      // The dirty dot doubles as the streaming indicator, so a generating
      // background tab is visible from the strip.
      dirty: tab.stream.streaming,
      tooltip: labelFor(tab),
    })), {
      onActivate: (node) => setActive(node.dataset.id),
      onClose: (node) => closeTab(node.dataset.id),
      closeTooltip: copy.tooltip.closeTab,
    });
  }

  function createTab({id = newSessionId(), fresh = true} = {}) {
    const messages = document.createElement("div");
    messages.className = "messages";
    messages.hidden = true;

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
    island.insertBefore(messages, composer.root);
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
        // A fresh tab has no transcript to load and its 404 is expected, so
        // only a real session surfaces the failure.
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

    // An empty model selection means "let the server pick its default"; a
    // failed discovery leaves the runtime default as the only path.
    listModels()
      .then((models) => modelPicker.applyModels(models))
      .catch(() => {
        // Discovery failed (no local host up); the server default still works.
      });
  }

  function send() {
    const text = composer.input.value.trim();
    const tab = tabs.get(activeId);
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
      model: modelPicker.value() || null,
      toolMode: permissionPicker.value(),
    });
    if (!started) {
      return;
    }

    composer.clear();
    tab.started = true;
    if (tab.fresh) {
      tab.fresh = false;
      store.setState({activeSession: tab.id});
    }
    tab.chat.beginUserTurn(text);
    composer.focus();
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
    // Closing a generating tab stops the run: the tab is gone, so the frames
    // would have nowhere to land.
    tab.stream.reset();
    tab.messages.remove();
    tabs.delete(id);

    if (tabs.size === 0) {
      // The chat never lands on an empty island; the last close starts a new
      // blank chat instead.
      const fresh = createTab();
      setActive(fresh.id);
      return;
    }
    if (activeId === id) {
      const ids = [...tabs.keys()];
      const index = ids.indexOf(id);
      setActive(ids[index] ?? ids[ids.length - 1]);
      return;
    }
    syncChrome();
    renderTabs();
    persistTabs();
  }

  // A project-root edit (status bar) or a first seed re-reads the session
  // list and remembers the choice. Before the first open only the preference
  // is stored; the deferred wiring does the first fetch.
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

  island.append(tabStrip, composer.root);
  canvas.appendChild(island);

  // Scroll-to-bottom: a circular button anchored to the composer's top-right
  // corner, visible only while the user has scrolled away from the newest
  // content. The active tab's column drives scrollTop; this listens to the
  // result of any of them.
  const scrollButton = createIconButton({
    icon: "scroll-down",
    tooltip: "Scroll to Bottom",
    onClick: () => {
      tabs.get(activeId)?.messages.scrollTo({
        top: tabs.get(activeId).messages.scrollHeight,
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
    const active = tabs.get(activeId);
    scrollButton.hidden = !active || nearBottom(active.messages);
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
