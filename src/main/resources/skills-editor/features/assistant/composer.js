// The composer row: textarea, the picker pills + menus, and
// the send/stop pair. It owns only its own controls; whether a run is active is
// pushed in through `setStreaming`.

import {createIcon} from "../../ui/components/icon.js";
import {copy} from "../../ui/copy.js";

export function createComposer({chips = [], menus = [], onSend, onStop} = {}) {
  const root = document.createElement("div");
  root.className = "composer";

  const input = document.createElement("textarea");
  input.className = "composer-input";
  input.rows = 1;
  input.placeholder = copy.chat.placeholder;
  input.spellcheck = false;

  const foot = document.createElement("div");
  foot.className = "composer-foot";

  const chipGroup = document.createElement("div");
  chipGroup.className = "chip-group";
  chipGroup.append(...chips);

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

  foot.append(chipGroup, ...menus, stopButton, sendButton);
  root.append(input, foot);

  let streaming = false;

  const sync = () => {
    sendButton.disabled = streaming || !input.value.trim();
    sendButton.hidden = streaming;
    stopButton.hidden = !streaming;
  };

  const resize = () => {
    input.style.height = "auto";
    input.style.height = `${input.scrollHeight}px`;
  };

  input.addEventListener("input", () => {
    resize();
    sync();
  });

  input.addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      if (!streaming) {
        onSend?.();
      }
    }
  });

  sendButton.addEventListener("click", () => onSend?.());
  stopButton.addEventListener("click", () => onStop?.());

  sync();

  return {
    root,
    input,
    stopButton,
    sendButton,
    setStreaming(next) {
      streaming = Boolean(next);
      sync();
    },
    clear() {
      input.value = "";
      input.style.height = "auto";
      sync();
    },
    focus() {
      input.focus();
    },
  };
}
