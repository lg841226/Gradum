// The notification layer: a fixed stack of action-free cards at the top right,
// fed only through `notify({severity, title, body})`.

import {createIconButton} from "../ui/components/button.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";

const CAP = 3;
const LIFETIME_MS = 5000;

let layer = null;
const live = [];

export function notify({severity = "info", title, body, source = null}) {
  if (!layer?.isConnected) {
    layer = document.createElement("div");
    layer.className = "notifications";
    live.length = 0;
    (document.querySelector(".canvas") ?? document.body).appendChild(layer);
  }
  const card = createCard({severity, title, body, source});
  live.push(card);
  while (live.length > CAP) {
    dismiss(live.shift());
  }
}

function createCard({severity, title, body, source}) {
  const card = document.createElement("div");
  card.className = "notification";
  card.dataset.severity = severity;

  const close = createIconButton({
    icon: "hide",
    tooltip: copy.notification.close,
    onClick: () => dismiss(card),
  });

  close.classList.add("notification-close");

  const head = document.createElement("div");
  head.className = "notification-title";
  head.textContent = title;

  const text = document.createElement("div");
  text.className = "notification-body";
  text.textContent = body;

  const box = document.createElement("div");
  box.className = "notification-text";
  box.append(head, text);

  if (source) {
    const src = document.createElement("div");
    src.className = "notification-source";
    src.textContent = source;
    box.append(src);
  }

  card.append(createIcon({name: severityIcon(severity)}), box, close);
  layer.appendChild(card);

  let timer = setTimeout(() => dismiss(card), LIFETIME_MS);
  card.addEventListener("mouseenter", () => clearTimeout(timer));
  card.addEventListener("mouseleave", () => {
    timer = setTimeout(() => dismiss(card), LIFETIME_MS);
  });
  return card;
}

function severityIcon(severity) {
  return severity === "error"
    ? "error"
    : severity === "warning" ? "warning" : "info-outline";
}

function dismiss(card) {
  const index = live.indexOf(card);
  if (index >= 0) {
    live.splice(index, 1);
  }
  card.addEventListener("transitionend", () => card.remove(), {once: true});
  card.classList.add("is-leaving");
}
