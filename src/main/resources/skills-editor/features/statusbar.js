import {pair} from "../data/skillsApi.js";
import {createIcon} from "../ui/components/icon.js";
import {createUnlock} from "../ui/components/unlock.js";
import {copy} from "../ui/copy.js";

const mobileItems = [];
const stripChips = new Map();

let strip = null;

export function registerStatusItem(entry) {
  mobileItems.push(entry);
  mobileItems.sort((a, b) => a.order - b.order);
  refreshStatusMobile();
}

export function refreshStatusMobile() {
  if (!strip) {
    return;
  }
  for (const item of mobileItems) {
    const compact = item.compact ? item.compact() : null;
    let chip = stripChips.get(item.id);
    if (!chip) {
      const text = document.createElement("span");
      const root = document.createElement(item.onSelect ? "button" : "span");
      if (item.onSelect) {
        root.type = "button";
        root.addEventListener("click", () => item.onSelect());
      }
      root.className = "statusbar-strip-item";
      chip = {root, text, icon: null};
      stripChips.set(item.id, chip);
      strip.appendChild(root);
    }
    if (compact) {
      if (chip.icon !== compact.icon) {
        chip.icon = compact.icon;
        chip.root.replaceChildren(createIcon({name: compact.icon}), chip.text);
      }
      const label = compact.text ?? "";
      if (chip.text.textContent !== label) {
        chip.text.textContent = label;
      }
    }
    chip.root.hidden = compact === null;
  }
}

// A problem counter: a hollow severity icon beside its count. Both marks are
// drawn in the strip's own gray rather than a severity color, so the counts
// stay quiet and the shape alone tells the two apart.
function createCount({tone, icon}) {
  const item = document.createElement("span");
  item.className = "status-count";
  item.dataset.tone = tone;

  const value = document.createElement("span");
  value.className = "status-count-value";

  item.append(createIcon({name: icon}), value);
  return {item, value};
}

// Counts only change on a compile, so the DOM is left alone unless the number
// actually differs.
function updateCount(count, value, label) {
  const text = String(value);
  if (count.value.textContent === text) {
    return;
  }
  count.value.textContent = text;
  count.item.setAttribute("aria-label", label);
}

// The root arrives as one string, so the split has to keep the shape of the
// path: an absolute prefix becomes its own first segment because no arrow goes
// before it; repeated and trailing separators collapse; `.`/`..`, drive
// letters and names with spaces or CJK survive untouched.
function splitPath(path) {
  if (!path) {
    return [];
  }
  const parts = path.startsWith("/") ? ["/"] : [];
  for (const part of path.split(/[\\/]+/)) {
    if (part) {
      parts.push(part);
    }
  }
  return parts;
}

export function mountStatusBar(store, {canvas, getActiveDoc, scope = null, sessionRemaining = null}) {
  const bar = document.createElement("footer");
  bar.className = "statusbar";
  bar.setAttribute("aria-label", copy.statusBar.label);

  strip = document.createElement("div");
  strip.className = "statusbar-strip";
  bar.appendChild(strip);

  const errors = createCount({tone: "error", icon: "error-outline"});
  const warnings = createCount({tone: "warning", icon: "warning-outline"});

  const home = document.documentElement.dataset.home || "";
  const shorten = (path) =>
    home && path.startsWith(home) ? `~${path.slice(home.length)}` : path;
  const expand = (path) =>
    path === "~" || path.startsWith("~/")
      ? home + path.slice(1)
      : path;

  const rootInput = document.createElement("input");
  rootInput.className = "text-field statusbar-root";
  rootInput.spellcheck = false;

  rootInput.placeholder = copy.statusBar.projectRoot;
  rootInput.addEventListener("change", () => {
    store.setState({projectRoot: expand(rootInput.value.trim())});
  });

  const pathDisplay = document.createElement("span");
  pathDisplay.className = "statusbar-path";
  pathDisplay.setAttribute("aria-hidden", "true");

  const rootField = document.createElement("div");
  rootField.className = "statusbar-path-field";
  rootField.append(rootInput, pathDisplay);

  let pathShown = null;

  const renderPath = (path) => {
    if (pathShown === path) {
      return;
    }
    pathShown = path;
    const nodes = [];
    for (const [index, part] of splitPath(path).entries()) {
      if (index > 0) {
        nodes.push(createIcon({name: "chevron-right", size: 14}));
      }
      const segment = document.createElement("span");
      segment.className = "statusbar-path-segment";
      segment.textContent = part;
      nodes.push(segment);
    }
    pathDisplay.replaceChildren(...nodes);
  };

  const left = document.createElement("div");
  left.className = "statusbar-left";
  left.append(errors.item, warnings.item, rootField);

  const position = document.createElement("span");
  position.className = "statusbar-item";

  const indent = document.createElement("span");
  indent.className = "statusbar-item";

  const lineEnding = document.createElement("span");
  lineEnding.className = "statusbar-item";
  lineEnding.dataset.tooltip = copy.statusBar.lineEndingTip;
  const encoding = document.createElement("span");
  encoding.className = "statusbar-item";
  encoding.dataset.tooltip = copy.statusBar.encodingTip;

  const right = document.createElement("div");
  right.className = "statusbar-right";
  right.append(position, indent, lineEnding, encoding);

  bar.append(left, right);
  canvas.appendChild(bar);

  const lock = document.createElement("button");
  lock.type = "button";
  lock.className = "statusbar-item statusbar-session";
  left.prepend(lock);

  const unlock = createUnlock({
    container: canvas,
    onToken: (token) => {
      location.assign(`/skills/editor?token=${encodeURIComponent(token)}`);
    },
    onCode: (code) => {
      pair(code).then((ok) => {
        if (ok) {
          location.assign(location.pathname);
          return;
        }
        unlock.show(copy.unlock.codeInvalid, "code");
      }).catch(() => {
        unlock.show(copy.unlock.codeInvalid, "code");
      });
    },
  });

  const sessionLabel = document.createElement("span");
  lock.replaceChildren(
    createIcon({name: scope === "remote" ? "webServer" : "desktop"}),
    sessionLabel,
  );

  const deadline = sessionRemaining === null
    ? null : Date.now() + sessionRemaining * 1000;
  let shownLabel = null;
  let sessionShown = null;
  let ended = false;

  const secondsLeft = () =>
    deadline === null ? null : Math.max(0, Math.ceil((deadline - Date.now()) / 1000));

  const formatLeft = (seconds) => {
    const pad = (value) => String(value).padStart(2, "0");
    const hours = Math.floor(seconds / 3600);
    const minutes = Math.floor((seconds % 3600) / 60);
    return hours > 0 ? `${hours}:${pad(minutes)}:${pad(seconds % 60)}` : `${minutes}:${pad(seconds % 60)}`;
  };

  const renderSession = (state) => {
    const seconds = secondsLeft();
    const label = !state.authed
      ? copy.statusBar.locked
      : seconds === null ? "" : formatLeft(seconds);
    if (shownLabel !== label) {
      shownLabel = label;
      sessionLabel.textContent = label;
    }
    const signature = `${state.authed}:${label}`;
    if (sessionShown !== signature) {
      sessionShown = signature;
      refreshStatusMobile();
    }
    const tip = !state.authed
      ? copy.statusBar.sessionLockedTip
      : seconds !== null
        ? copy.statusBar.sessionTip(formatLeft(seconds))
        : copy.statusBar.sessionOpenTip;
    if (lock.dataset.tooltip !== tip) {
      lock.dataset.tooltip = tip;
      lock.setAttribute("aria-label", tip);
    }
    if (state.authed && seconds === 0 && !ended) {
      ended = true;
      if (document.documentElement.dataset.offline !== "true") {
        location.reload();
      }
    }
  };

  lock.addEventListener("click", () => {
    if (!store.getState().authed) {
      unlock.show();
    }
  });

  store.subscribe(renderSession);
  renderSession(store.getState());

  registerStatusItem({
    id: "session",
    order: 1,
    compact: () => {
      const state = store.getState();
      const seconds = secondsLeft();
      if (state.authed && seconds === null) {
        return null;
      }
      return {
        icon: scope === "remote" ? "webServer" : "desktop",
        text: !state.authed ? copy.statusBar.locked : formatLeft(seconds),
      };
    },
    onSelect: () => {
      if (!store.getState().authed) {
        unlock.show();
      }
    },
  });

  if (deadline !== null) {
    setInterval(() => renderSession(store.getState()), 1000);
    document.addEventListener("visibilitychange", () => {
      if (!document.hidden) {
        renderSession(store.getState());
      }
    });
  }

  if (document.documentElement.getAttribute("data-reader") === "invalid") {
    unlock.show(copy.unlock.invalid, "token");
  }

  const fileLock = document.createElement("button");
  fileLock.type = "button";
  fileLock.className = "statusbar-item statusbar-lock";
  right.appendChild(fileLock);

  let fileLockShown = null;

  const renderFileLock = (state) => {
    fileLock.hidden = !state.authed;
    const locked = Boolean(state.reader);
    const signature = `${state.authed}:${locked}`;
    if (fileLockShown !== signature) {
      fileLockShown = signature;
      refreshStatusMobile();
    }
    const tip = locked ? copy.statusBar.lockedTip : copy.statusBar.unlockedTip;
    if (fileLock.dataset.tooltip !== tip) {
      fileLock.dataset.tooltip = tip;
      fileLock.setAttribute("aria-label", tip);
      fileLock.replaceChildren(createIcon({name: locked ? "locked" : "unlocked"}));
    }
  };

  fileLock.addEventListener("click", () => {
    const state = store.getState();
    if (state.authed) {
      store.setState({reader: !state.reader});
    }
  });

  store.subscribe(renderFileLock);
  renderFileLock(store.getState());

  registerStatusItem({
    id: "lock",
    order: 2,
    compact: () => {
      const state = store.getState();
      if (!state.authed) {
        return null;
      }
      return {icon: state.reader ? "locked" : "unlocked"};
    },
    onSelect: () => {
      const state = store.getState();
      if (state.authed) {
        store.setState({reader: !state.reader});
      }
    },
  });

  const render = (state) => {
    const doc = getActiveDoc(state);
    const chat = state.chatOpen === true;

    errors.item.hidden = chat || !doc;
    warnings.item.hidden = chat || !doc;
    rootField.hidden = !chat || !doc;
    position.hidden = chat;
    indent.hidden = chat;
    lineEnding.hidden = chat;
    encoding.hidden = chat;
    if (rootInput.value !== shorten(state.projectRoot) && document.activeElement !== rootInput) {
      rootInput.value = shorten(state.projectRoot);
    }
    renderPath(shorten(state.projectRoot));

    if (!doc) {
      position.textContent = "";
      position.removeAttribute("aria-label");
      position.removeAttribute("data-tooltip");
      indent.textContent = "";
      indent.removeAttribute("aria-label");
      indent.removeAttribute("data-tooltip");
      lineEnding.textContent = "";
      encoding.textContent = "";
      return;
    }
    const diagnostics = doc.diagnostics || [];
    const errorTotal = diagnostics.filter((item) => item.severity === "error").length;
    const warningTotal = diagnostics.filter((item) => item.severity === "warning").length;
    updateCount(errors, errorTotal, copy.statusBar.errors(errorTotal));
    updateCount(warnings, warningTotal, copy.statusBar.warnings(warningTotal));

    lineEnding.textContent = doc ? copy.statusBar.lineEnding : "";
    encoding.textContent = doc ? copy.statusBar.encoding : "";
    if (!doc) {
      position.textContent = "";
      position.removeAttribute("aria-label");
      position.removeAttribute("data-tooltip");
      indent.textContent = "";
      indent.removeAttribute("aria-label");
      indent.removeAttribute("data-tooltip");
      return;
    }

    indent.textContent = copy.statusBar.indent(state.indent);
    const indentLabel = copy.statusBar.indentLabel(state.indent);
    indent.setAttribute("aria-label", indentLabel);
    indent.dataset.tooltip = indentLabel;

    const {line, column} = state.cursor;
    const {selection} = state;
    const summary = selection
      ? ` (${copy.statusBar.selection(selection.characters, selection.newlines)})`
      : "";
    const multiple = state.cursorCount > 1;
    const lead = multiple
      ? copy.statusBar.cursors(state.cursorCount)
      : copy.statusBar.position(line, column);
    position.textContent = lead + summary;
    position.setAttribute(
      "aria-label",
      (multiple ? lead : copy.statusBar.positionLabel(line, column)) + summary,
    );

    position.dataset.tooltip =
      (multiple ? lead : copy.statusBar.positionTip(line, column)) + summary;
  };

  store.subscribe(render);
  render(store.getState());
  return {container: bar, right};
}
