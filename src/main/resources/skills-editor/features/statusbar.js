import {pair} from "../data/skillsApi.js";
import {createIcon} from "../ui/components/icon.js";
import {createUnlock} from "../ui/components/unlock.js";
import {copy} from "../ui/copy.js";

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

export function mountStatusBar(store, {canvas, getActiveDoc}) {
  const bar = document.createElement("footer");
  bar.className = "statusbar";
  bar.setAttribute("aria-label", copy.statusBar.label);

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
  rootInput.hidden = true;
  rootInput.addEventListener("change", () => {
    store.setState({projectRoot: expand(rootInput.value.trim())});
  });

  const left = document.createElement("div");
  left.className = "statusbar-left";
  left.append(errors.item, warnings.item, rootInput);

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
  lock.className = "statusbar-item statusbar-lock";
  right.appendChild(lock);

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

  const renderLock = (state) => {
    const locked = Boolean(state.reader);
    const tip = locked ? copy.statusBar.lockedTip : copy.statusBar.unlockedTip;
    if (lock.dataset.tooltip !== tip) {
      lock.dataset.tooltip = tip;
      lock.setAttribute("aria-label", tip);
      lock.replaceChildren(createIcon({name: locked ? "locked" : "unlocked"}));
    }
  };

  lock.addEventListener("click", () => {
    const state = store.getState();
    if (!state.authed) {
      unlock.show();
      return;
    }
    store.setState({reader: !state.reader});
  });

  store.subscribe(renderLock);
  renderLock(store.getState());

  if (document.documentElement.getAttribute("data-reader") === "invalid") {
    unlock.show(copy.unlock.invalid, "token");
  }

  const render = (state) => {
    const doc = getActiveDoc(state);
    const chat = state.chatOpen === true;

    // The agent mode owns the strip: the counts + the editor-facing values
    // describe the developer workspace, so they leave with it and only the
    // project root stays. The strip reads the mode from the store, so nothing
    // outside this render couples the two features.
    left.hidden = !doc && !chat;
    errors.item.hidden = chat;
    warnings.item.hidden = chat;
    rootInput.hidden = !chat;
    position.hidden = chat;
    indent.hidden = chat;
    lineEnding.hidden = chat;
    encoding.hidden = chat;
    if (rootInput.value !== shorten(state.projectRoot) && document.activeElement !== rootInput) {
      rootInput.value = shorten(state.projectRoot);
    }

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
  return {container: bar};
}
