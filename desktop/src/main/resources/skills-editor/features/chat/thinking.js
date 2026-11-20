import {createIcon} from "../../ui/components/icon.js";
import {copy} from "../../ui/copy.js";

export function createThinkingBlock({startedAt}) {
  const element = document.createElement("details");
  element.className = "thinking";
  element.open = true;

  const summary = document.createElement("summary");
  summary.append(createIcon({name: "nodes-related"}));
  const label = document.createElement("span");
  label.className = "thinking-label sweep-text";
  label.textContent = copy.chat.thoughtFor(0);
  summary.append(label, createIcon({name: "chevron-down"}));

  const body = document.createElement("div");
  body.className = "thinking-body";
  element.append(summary, body);

  let timer = null;

  const stop = () => {
    if (timer !== null) {
      clearInterval(timer);
      timer = null;
    }
  };

  const elapsed = () => copy.chat.thoughtFor((Date.now() - startedAt) / 1000);

  timer = setInterval(() => {
    if (!element.isConnected || element.dataset.done === "true") {
      stop();
      return;
    }
    label.textContent = elapsed();
  }, 100);

  return {
    element,
    body,
    setCapped(capped) {
      if (capped) {
        element.dataset.capped = "true";
      }
    },
    finish(milliseconds) {
      if (element.dataset.done === "true") {
        return;
      }
      element.dataset.done = "true";
      stop();
      label.classList.remove("sweep-text");
      label.textContent = milliseconds > 0
        ? copy.chat.thoughtFor(milliseconds / 1000)
        : copy.chat.thoughtNoTime;
    },
    stop,
  };
}
