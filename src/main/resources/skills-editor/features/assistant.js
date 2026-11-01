import {listModels, listSessions, stop, streamEvents} from "../data/legatusApi.js";
import {createChat} from "./chat.js";
import {createIcon} from "../ui/components/icon.js";
import {createListItem} from "../ui/components/list.js";

const MODEL_KEY = "legatus.model";
const ROOT_KEY = "legatus.projectRoot";
const PERM_KEY = "legatus.permission";

// The tool-surface modes the server's /events accepts as `toolMode`
// (Routes.kt). Same wire values the plugin's permission selector sends;
// its debug entry is plugin-only, so the web picker leaves it out. Labels
// and descriptions are GradumBundle.properties verbatim (gradum.read etc.).
const PERMISSION_MODES = [
  {value: "read_only", label: "Read Permissions", info: "Read, explore and run diagnostics"},
  {value: "edit", label: "Edit Permissions", info: "Edit files and code by hand"},
  {value: "agent", label: "Full Agent Mode", info: "Reads, writes, executes complex tasks"},
  {value: "yolo", label: "Full Access", info: "Skips all approvals, runs edits and commands directly"},
];

// A fresh session id per chat, minted here so new chats are durable even
// before the server has seen them. crypto.randomUUID only exists in secure
// contexts; this page is often opened over plain http on the LAN, where it
// is undefined.
function newSessionId() {
  return crypto.randomUUID
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}

// The assistant island: the Gradum chat occupying the editor's grid cell
// while it is open. It owns no rail of its own — the session list renders in
// the shared rail (from the store's `sessions`) and the project root lives in
// the status bar (`projectRoot`); this feature just reacts to both.
//
// The heavy wiring (model list, sessions, composer) is deferred to the first
// open, so merely loading the page posts nothing to the chat endpoints.
export function mountAssistant(store, {canvas}) {
  const island = document.createElement("section");
  island.className = "assistant panel";
  island.hidden = true;

  const messages = document.createElement("div");
  messages.className = "messages";

  // The brand mark is the bundled colour logo; its gradient id is namespaced
  // so it cannot collide with any other inline svg on the page.
  const emptyState = document.createElement("div");
  emptyState.className = "empty-state";
  const emptyLogo = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  emptyLogo.setAttribute("viewBox", "0 0 20 20");
  emptyLogo.setAttribute("aria-hidden", "true");
  emptyLogo.setAttribute("class", "empty-logo");
  emptyLogo.innerHTML =
    '<defs><linearGradient id="legatus-logo-grad" x1="100%" y1="0%" x2="0%" y2="100%">'
    + '<stop offset="0%" stop-color="#165DFF"/><stop offset="100%" stop-color="#7CB3FF"/>'
    + "</linearGradient></defs>"
    + '<path d="M6.76993 8.03602C7.9384 6.87658 8.765 5.42306 9.37509 3.61672C9.98519 5.42307'
    + " 10.8118 6.87658 11.9803 8.03602C13.1472 9.19393 14.6128 10.0165 16.4395 10.6249"
    + "C14.6128 11.2334 13.1472 12.0559 11.9803 13.2139C10.8118 14.3733 9.98522 15.8268"
    + " 9.37514 17.6331C8.76504 15.8268 7.93842 14.3733 6.76996 13.2139C5.60303 12.0559"
    + ' 4.13748 11.2334 2.31077 10.6249C4.13747 10.0165 5.60301 9.19393 6.76993 8.03602Z"'
    + ' fill="url(#legatus-logo-grad)" stroke="url(#legatus-logo-grad)" stroke-width="1.5"/>'
    + '<path d="M16.2411 6.13383C17.556 6.13383 18.6219 5.06793 18.6219 3.75307C18.6219'
    + " 2.43822 17.556 1.37231 16.2411 1.37231C14.9263 1.37231 13.8604 2.43822 13.8604"
    + ' 3.75307C13.8604 5.06793 14.9263 6.13383 16.2411 6.13383Z" fill="url(#legatus-logo-grad)"/>';
  const emptyTitle = document.createElement("p");
  emptyTitle.className = "empty-title";
  emptyTitle.textContent = "Gradum";
  const emptyHint = document.createElement("p");
  emptyHint.className = "empty-hint";
  emptyHint.textContent = "Ask a question, or hand off a task for this project.";
  emptyState.append(emptyLogo, emptyTitle, emptyHint);

  const composer = document.createElement("div");
  composer.className = "composer";
  const composerInput = document.createElement("textarea");
  composerInput.className = "composer-input";
  composerInput.rows = 1;
  composerInput.placeholder = "Ask Gradum anything";
  composerInput.spellcheck = false;
  const composerFoot = document.createElement("div");
  composerFoot.className = "composer-foot";

  // The model picker: a pill chip and its own popup menu — no native select,
  // the platform never draws one here.
  let modelValue = "";
  const modelChip = document.createElement("button");
  modelChip.type = "button";
  modelChip.className = "btn model-chip";
  modelChip.dataset.tooltip = "Model";
  const modelChipLabel = document.createElement("span");
  modelChipLabel.className = "model-chip-label";
  modelChipLabel.textContent = "Runtime Default";
  modelChip.append(modelChipLabel, createIcon({name: "chevron-down"}));

  const modelMenu = document.createElement("div");
  modelMenu.className = "model-menu";
  modelMenu.hidden = true;
  const modelMenuList = document.createElement("div");
  modelMenuList.className = "model-menu-list";
  modelMenu.appendChild(modelMenuList);

  // The chip's chevron flips while its menu is up; the data-open attribute
  // drives the CSS rotation.
  const syncChipOpen = (chip, menu) => {
    if (menu.hidden) chip.removeAttribute("data-open");
    else chip.dataset.open = "true";
  };
  const closeModelMenu = () => {
    modelMenu.hidden = true;
    syncChipOpen(modelChip, modelMenu);
  };
  modelChip.addEventListener("click", (event) => {
    event.stopPropagation();
    modelMenu.hidden = !modelMenu.hidden;
    syncChipOpen(modelChip, modelMenu);
  });
  document.addEventListener("click", (event) => {
    if (!modelMenu.hidden && !modelMenu.contains(event.target)) closeModelMenu();
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeModelMenu();
  });

  // The permission picker: the plugin's read_only / edit / agent / yolo
  // selector, drawn as a second pill chip beside the model one. It stores
  // the wire value and travels with every send as `toolMode`.
  let permissionValue = localStorage.getItem(PERM_KEY) || "read_only";
  if (!PERMISSION_MODES.some((mode) => mode.value === permissionValue)) {
    permissionValue = "read_only";
  }
  const permissionChip = document.createElement("button");
  permissionChip.type = "button";
  permissionChip.className = "btn perm-chip";
  permissionChip.dataset.tooltip = "Permissions";
  const permissionChipLabel = document.createElement("span");
  permissionChipLabel.className = "perm-chip-label";
  permissionChip.append(permissionChipLabel, createIcon({name: "chevron-down"}));

  const permissionMenu = document.createElement("div");
  permissionMenu.className = "perm-menu";
  permissionMenu.hidden = true;
  const permissionMenuList = document.createElement("div");
  permissionMenuList.className = "model-menu-list";
  permissionMenu.appendChild(permissionMenuList);

  const closePermissionMenu = () => {
    permissionMenu.hidden = true;
    syncChipOpen(permissionChip, permissionMenu);
  };
  permissionChip.addEventListener("click", (event) => {
    event.stopPropagation();
    permissionMenu.hidden = !permissionMenu.hidden;
    syncChipOpen(permissionChip, permissionMenu);
  });
  document.addEventListener("click", (event) => {
    if (!permissionMenu.hidden && !permissionMenu.contains(event.target)) closePermissionMenu();
  });
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closePermissionMenu();
  });

  const choosePermission = (value) => {
    permissionValue = value;
    permissionChipLabel.textContent =
      PERMISSION_MODES.find((mode) => mode.value === value)?.label ?? value;
    localStorage.setItem(PERM_KEY, value);
    for (const row of permissionMenuList.children) {
      row.setAttribute("aria-selected", row.dataset.value === value ? "true" : "false");
    }
    closePermissionMenu();
  };
  permissionMenuList.replaceChildren(
    ...PERMISSION_MODES.map((mode) => {
      // The plugin's menu rows read as two lines: the mode's name over its
      // info sentence, so the picker explains the surface it switches to.
      const row = document.createElement("div");
      row.className = "list-item perm-item";
      row.setAttribute("role", "treeitem");
      row.setAttribute("tabindex", "0");
      row.setAttribute("aria-selected", mode.value === permissionValue ? "true" : "false");
      row.dataset.value = mode.value;
      const name = document.createElement("span");
      name.className = "list-item-label";
      name.textContent = mode.label;
      const info = document.createElement("span");
      info.className = "perm-item-info";
      info.textContent = mode.info;
      row.append(name, info);
      row.addEventListener("click", () => choosePermission(mode.value));
      return row;
    }),
  );
  permissionChipLabel.textContent =
    PERMISSION_MODES.find((mode) => mode.value === permissionValue)?.label
    ?? PERMISSION_MODES[0].label;

  const status = document.createElement("span");
  status.className = "chat-status";
  status.dataset.tone = "idle";
  status.textContent = "Idle";

  // One round run button: the paper plane at rest, the halt square while a
  // run is in flight. The stop half keeps its own element so chat.js can
  // reveal it at the turn's start and retire it at the end.
  const stopButton = document.createElement("button");
  stopButton.type = "button";
  stopButton.className = "composer-send";
  stopButton.dataset.tooltip = "Stop";
  stopButton.hidden = true;
  stopButton.appendChild(createIcon({name: "stop", size: 16}));

  const composerSend = document.createElement("button");
  composerSend.type = "button";
  composerSend.className = "composer-send";
  composerSend.dataset.tooltip = "Send";
  composerSend.appendChild(createIcon({name: "send", size: 16}));

  // The chip pair: both pickers live in one left group with its own tight
  // gap; the group's auto margin pushes the run controls to the right edge.
  const chipGroup = document.createElement("div");
  chipGroup.className = "chip-group";
  chipGroup.append(modelChip, permissionChip);

  composerFoot.append(chipGroup, modelMenu, permissionMenu, status, stopButton, composerSend);
  composer.append(composerInput, composerFoot);

  island.append(messages, emptyState, composer);
  island.classList.add("is-empty");
  canvas.appendChild(island);

  // Empty-state layout is driven by an island class rather than an attribute
  // selector, so the centred welcome never depends on :has() support.
  const setEmpty = (visible) => {
    emptyState.hidden = !visible;
    island.classList.toggle("is-empty", visible);
  };

  let wired = false;
  let currentSessionId = null;
  let streaming = false;
  let chat = null;
  let lastRoot = null;

  // The server stamps its own working directory on the page; the root must
  // never start empty because the browser cannot guess a path.
  const seedRoot = () => {
    const state = store.getState();
    if (state.projectRoot) {
      return;
    }
    const defaultRoot = document.documentElement.dataset.defaultRoot || "";
    if (defaultRoot) {
      store.setState({projectRoot: defaultRoot});
    }
  };

  const refreshSessions = async () => {
    const root = store.getState().projectRoot;
    const sessions = root ? await listSessions(root).catch(() => []) : [];
    store.setState({sessions});
  };

  const wire = () => {
    if (wired) return;
    wired = true;
    currentSessionId = newSessionId();

    chat = createChat({messages, status, stopButton, onEmptyChange: setEmpty});

    seedRoot();
    void refreshSessions();

    // Model picker: the server's discovery list, remembered across visits.
    // An empty selection means "let the server pick its default".
    listModels()
      .then((models) => {
        const usable = models.filter((model) => model.available);
        if (usable.length === 0) {
          // Nothing the server discovered is usable: the picker stands down,
          // greyed with the fact on its face, and the runtime default stays
          // the only working path.
          modelChip.disabled = true;
          modelChipLabel.textContent = "No models available";
          return;
        }
        const saved = localStorage.getItem(MODEL_KEY);
        if (usable.some((model) => model.name === saved)) modelValue = saved;
        const choose = (value, label) => {
          modelValue = value;
          modelChipLabel.textContent = label;
          localStorage.setItem(MODEL_KEY, value);
          for (const row of modelMenuList.children) {
            row.setAttribute("aria-selected", row.dataset.value === value ? "true" : "false");
          }
          closeModelMenu();
        };
        modelMenuList.replaceChildren(
          createListItem({
            label: "Runtime Default",
            active: modelValue === "",
            onClick: () => choose("", "Runtime Default"),
          }),
          ...usable.map((model) => {
            const row = createListItem({
              label: `${model.name} (${model.provider})`,
              active: model.name === modelValue,
              onClick: () => choose(model.name, `${model.name} (${model.provider})`),
            });
            row.dataset.value = model.name;
            return row;
          }),
        );
        if (modelValue) {
          const current = usable.find((model) => model.name === modelValue);
          modelChipLabel.textContent = `${current.name} (${current.provider})`;
        }
      })
      .catch(() => {
        // Discovery failed (no local host up); the server default still works.
      });

    // The send key earns its ink only when there is something to send and no
    // run is in flight, when the same seat flips to the stop square.
    const syncSend = () => {
      composerSend.disabled = streaming || !composerInput.value.trim();
      composerSend.hidden = streaming;
    };

    const send = async () => {
      const text = composerInput.value.trim();
      const projectRoot = store.getState().projectRoot;
      if (!text || streaming) return;
      if (!projectRoot) {
        chat.setTone("error", "Project root missing");
        chat.notice(
          "Set the project root in the status bar first — the absolute path of the " +
          "directory Gradum should work in. Sessions and file tools are rooted there.",
        );
        return;
      }

      streaming = true;
      syncSend();
      composerInput.value = "";
      composerInput.style.height = "auto";
      chat.beginUserTurn(text);

      const abort = new AbortController();
      // Before the first frame names the run, Stop can only cut the stream;
      // afterwards it also tells the server to halt the run itself.
      stopButton.onclick = () => abort.abort();
      stopButton.hidden = false;

      try {
        await streamEvents(
          {
            message: text,
            projectRoot,
            sessionId: currentSessionId,
            loadContext: true,
            model: modelValue || null,
            toolMode: permissionValue,
          },
          {
            onEvent: (frame) => {
              chat.handleEvent(frame);
              // The first frame names the run; wire the Stop button to it.
              if (frame.runId) {
                stopButton.onclick = () => void stop(frame.runId).catch(console.error);
              }
            },
            signal: abort.signal,
          },
        );
        chat.finishTurn();
        void refreshSessions();
      } catch (error) {
        chat.finishTurn();
        if (error.name !== "AbortError") {
          chat.setTone("error", error.message);
        }
      } finally {
        streaming = false;
        syncSend();
        stopButton.hidden = true;
      }
    };

    composerSend.addEventListener("click", () => void send());

    composerInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter" && !event.shiftKey) {
        event.preventDefault();
        void send();
      }
    });

    // Grow the textarea with its content, capped by CSS max-height.
    composerInput.addEventListener("input", () => {
      composerInput.style.height = "auto";
      composerInput.style.height = `${composerInput.scrollHeight}px`;
      syncSend();
    });
    syncSend();

    composerInput.focus();
  };

  // A project-root edit (status bar) or a first seed re-reads the session
  // list and remembers the choice.
  store.subscribe((state) => {
    const root = state.projectRoot;
    if (root === lastRoot) {
      return;
    }
    const first = lastRoot === null;
    lastRoot = root;
    try {
      localStorage.setItem(ROOT_KEY, root);
    } catch (_error) {
      // Storage may be unavailable; the field keeps working this session.
    }
    if (!first || wired) {
      void refreshSessions();
    }
  });

  // Actions the rail and the view menu drive.
  const newChat = () => {
    currentSessionId = newSessionId();
    messages.replaceChildren();
    setEmpty(true);
    store.setState({activeSession: null});
    composerInput.focus();
  };

  // Switching onto a past session starts a fresh run in the same durable
  // session; the server reloads its context.
  const openSession = (sessionId) => {
    currentSessionId = sessionId;
    messages.replaceChildren();
    setEmpty(true);
    store.setState({activeSession: sessionId});
    composerInput.focus();
  };

  let signature = null;
  const render = (state) => {
    const next = String(state.chatOpen === true);
    if (next === signature) return;
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
