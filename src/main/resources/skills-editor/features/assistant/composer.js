// The composer row: textarea, the picker pills + menus, and
// the send/stop pair. It owns only its own controls; whether a run is active is
// pushed in through `setStreaming`.

import {createIcon, fileTypeIcon} from "../../ui/components/icon.js";
import {copy} from "../../ui/copy.js";

function pathName(path) {
  const trimmed = path.trim().replace(/[\\/]+$/, "");
  const cut = Math.max(trimmed.lastIndexOf("/"), trimmed.lastIndexOf("\\"));
  return cut >= 0 ? trimmed.slice(cut + 1) : trimmed;
}

export function createComposer({chips = [], menus = [], onAttach, onSend, onStop} = {}) {
  const root = document.createElement("div");
  root.className = "composer";

  const flow = document.createElement("div");
  flow.className = "composer-flow";

  const placeholder = document.createElement("span");
  placeholder.className = "composer-placeholder";
  placeholder.textContent = copy.chat.placeholder;

  const parts = [];
  let lastFocused = null;
  let composing = false;
  let focused = false;
  let streaming = false;
  let sync = () => {};

  const measure = document.createElement("canvas").getContext("2d");

  const partIndex = (el) => parts.findIndex((part) => part.kind === "text" && part.el === el);
  const textParts = () => parts.filter((part) => part.kind === "text");

  const text = () => parts
    .map((part) => (part.kind === "path" ? `@${part.path}` : part.el.value))
    .join("");

  const focusAt = (el, position) => {
    el.focus();
    el.setSelectionRange(position, position);
  };

  const fit = (el) => {
    const style = getComputedStyle(el);
    measure.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize} ${style.fontFamily}`;
    let width = 0;
    for (const line of el.value.split("\n")) {
      width = Math.max(width, measure.measureText(line).width);
    }
    el.style.width = `${Math.max(2, Math.ceil(width) + 2)}px`;
    el.style.height = "0px";
    el.style.height = `${el.scrollHeight}px`;
  };

  const place = () => {
    const first = textParts()[0]?.el;
    if (!first) {
      return;
    }
    const style = getComputedStyle(first);
    placeholder.style.left = `${first.offsetLeft + (parseFloat(style.paddingLeft) || 0)}px`;
    placeholder.style.top = `${first.offsetTop + (parseFloat(style.paddingTop) || 0)}px`;
  };

  const refreshPlaceholder = () => {
    if (composing) {
      return;
    }
    const empty = parts.length === 1
      && parts[0].kind === "text"
      && parts[0].el.value.length === 0;
    placeholder.style.visibility = !empty || focused ? "hidden" : "visible";
  };

  const previousText = (index) => {
    for (let position = index - 1; position >= 0; position -= 1) {
      if (parts[position].kind === "text") {
        return parts[position].el;
      }
    }
    return null;
  };

  const nextText = (index) => {
    for (let position = index + 1; position < parts.length; position += 1) {
      if (parts[position].kind === "text") {
        return parts[position].el;
      }
    }
    return null;
  };

  function render() {
    flow.replaceChildren(
      placeholder,
      ...parts.map((part) => (part.kind === "path" ? chipFor(part) : part.el)),
    );
    refreshPlaceholder();
    fitAll();
  }

  function fitAll() {
    for (const part of parts) {
      if (part.kind === "text") {
        fit(part.el);
      }
    }
    place();
  }

  function chipFor(part) {
    const chip = document.createElement("span");
    chip.className = "path-chip composer-path";
    chip.dataset.tooltip = part.path;

    const name = pathName(part.path);
    const dot = name.lastIndexOf(".");
    const stem = dot > 0 ? name.slice(0, dot) : name;
    const ext = dot > 0 ? name.slice(dot) : "";

    const label = document.createElement("span");
    label.className = "path-chip-label";
    label.textContent = stem;

    const suffix = document.createElement("span");
    suffix.className = "path-chip-ext";
    suffix.textContent = ext;

    const nameBox = document.createElement("span");
    nameBox.className = "path-chip-name";
    nameBox.append(label, suffix);

    chip.append(createIcon({name: fileTypeIcon(name)}), nameBox);
    chip.addEventListener("mousedown", (event) => event.preventDefault());
    return chip;
  }

  function joinBackTo(el, index) {
    const left = parts[index - 2];
    if (left?.kind === "text") {
      const caret = left.el.value.length;
      left.el.value += el.value;
      parts.splice(index - 1, 2);
      fit(left.el);
      render();
      focusAt(left.el, caret);
    } else {
      parts.splice(index - 1, 1);
      render();
      focusAt(el, 0);
    }
    sync();
  }

  function eatForward(el, index) {
    const caret = el.value.length;
    const right = parts[index + 2];
    parts.splice(index + 1, right?.kind === "text" ? 2 : 1);
    if (right?.kind === "text") {
      el.value += right.el.value;
      fit(el);
    }
    render();
    focusAt(el, caret);
    sync();
  }

  function insertPath(path) {
    if (parts.some((part) => part.kind === "path" && part.path === path)) {
      return;
    }
    const active = document.activeElement;
    const focused = active && flow.contains(active) && active.classList.contains("composer-segment")
      ? active
      : lastFocused;
    const el = focused && partIndex(focused) >= 0 ? focused : textParts().at(-1)?.el;
    if (!el) {
      const created = createSegment("");
      parts.push({kind: "text", el: created});
      render();
      return insertPath(path);
    }

    const index = partIndex(el);
    const start = el.selectionStart ?? el.value.length;
    const end = el.selectionEnd ?? start;
    const rightEl = createSegment(el.value.slice(end));
    const left = el.value.slice(0, start);
    el.value = left;
    parts.splice(index + 1, 0, {kind: "path", path}, {kind: "text", el: rightEl});
    render();
    focusAt(rightEl, 0);
    sync();
  }

  function handleKey(el, event) {
    if (event.isComposing) {
      return;
    }
    if (event.key === "Enter") {
      if (!event.shiftKey) {
        event.preventDefault();
        if (!streaming) {
          onSend?.();
        }
      }
      return;
    }
    const start = el.selectionStart ?? 0;
    const end = el.selectionEnd ?? start;
    if (start !== end) {
      return;
    }
    const index = partIndex(el);
    const before = parts[index - 1];
    if (event.key === "Backspace" && start === 0 && before?.kind === "path") {
      event.preventDefault();
      joinBackTo(el, index);
      return;
    }
    if (event.key === "Delete" && start === el.value.length) {
      const after = parts[index + 1];
      if (after?.kind === "path") {
        event.preventDefault();
        eatForward(el, index);
      }
      return;
    }
    if (event.key === "ArrowLeft" && start === 0) {
      const previous = previousText(index);
      if (previous) {
        event.preventDefault();
        focusAt(previous, previous.value.length);
      }
      return;
    }
    if (event.key === "ArrowRight" && start === el.value.length) {
      const next = nextText(index);
      if (next) {
        event.preventDefault();
        focusAt(next, 0);
      }
    }
  }

  function createSegment(value = "") {
    const el = document.createElement("textarea");
    el.className = "composer-segment";
    el.rows = 1;
    el.spellcheck = false;
    el.value = value;
    el.addEventListener("focus", () => {
      lastFocused = el;
    });
    el.addEventListener("input", () => {
      fit(el);
      place();
      refreshPlaceholder();
      sync();
    });
    el.addEventListener("compositionstart", () => {
      composing = true;
      placeholder.style.visibility = "hidden";
    });
    el.addEventListener("compositionend", () => {
      composing = false;
      refreshPlaceholder();
    });
    el.addEventListener("keydown", (event) => handleKey(el, event));
    return el;
  }

  const addTextPart = (value = "") => {
    const el = createSegment(value);
    const part = {kind: "text", el};
    parts.push(part);
    return part;
  };

  const foot = document.createElement("div");
  foot.className = "composer-foot";

  const chipGroup = document.createElement("div");
  chipGroup.className = "chip-group";
  chipGroup.append(...chips);

  const attachButton = document.createElement("button");
  attachButton.type = "button";
  attachButton.className = "composer-attach";
  attachButton.dataset.tooltip = copy.chat.attach;
  attachButton.setAttribute("aria-label", copy.chat.attach);
  attachButton.appendChild(createIcon({name: "attach"}));
  attachButton.addEventListener("click", (event) => {
    event.stopPropagation();
    onAttach?.();
  });

  const stopButton = document.createElement("button");
  stopButton.type = "button";
  stopButton.className = "composer-send";
  stopButton.dataset.tooltip = copy.chat.stop;
  stopButton.hidden = true;
  stopButton.appendChild(createIcon({name: "stop", size: 16}));

  const sendButton = document.createElement("button");
  sendButton.type = "button";
  sendButton.className = "composer-send";
  sendButton.dataset.tooltip = copy.chat.send;
  sendButton.appendChild(createIcon({name: "send", size: 16}));

  foot.append(chipGroup, ...menus, attachButton, stopButton, sendButton);
  root.append(flow, foot);

  flow.addEventListener("click", (event) => {
    if (event.target === flow || event.target === placeholder) {
      textParts().at(-1)?.el.focus();
    }
  });

  root.addEventListener("focusin", () => {
    focused = true;
    refreshPlaceholder();
  });

  root.addEventListener("focusout", (event) => {
    if (event.relatedTarget && root.contains(event.relatedTarget)) {
      return;
    }
    focused = false;
    refreshPlaceholder();
  });

  sync = () => {
    sendButton.disabled = streaming || !text().trim();
    sendButton.hidden = streaming;
    stopButton.hidden = !streaming;
  };

  sendButton.addEventListener("click", () => onSend?.());
  stopButton.addEventListener("click", () => onStop?.());

  addTextPart("");
  render();
  sync();
  requestAnimationFrame(fitAll);
  window.addEventListener("resize", fitAll);

  return {
    root,
    stopButton,
    sendButton,
    text,
    setStreaming(next) {
      streaming = Boolean(next);
      sync();
    },
    setAttachOpen(open) {
      if (open) {
        attachButton.dataset.open = "true";
      } else {
        delete attachButton.dataset.open;
      }
    },
    insertPath,
    clear() {
      parts.length = 0;
      addTextPart("");
      render();
      sync();
    },
    focus() {
      const target = textParts().at(-1)?.el;
      if (target) {
        target.focus();
        target.setSelectionRange(target.value.length, target.value.length);
      }
    },
  };
}
