// Projects the chat model's effects onto the message column.
//
// This module owns DOM nodes, not chat facts: the model decides what exists,
// the view builds the matching element once and then updates it in place. A
// node reference that goes stale is replaced by the next `*:start` effect, so
// a reset can never leave the renderer writing into a detached bubble.

import {renderMarkdown} from "../../ui/markdown.js";
import {copy} from "../../ui/copy.js";
import {createIconButton} from "../../ui/components/button.js";
import {createIcon} from "../../ui/components/icon.js";
import {renderAskCard} from "./askCard.js";
import {createProcessGroup, createToolCapsule} from "./processGroup.js";
import {createThinkingBlock} from "./thinking.js";

function fileName(path) {
  const trimmed = path.replace(/[\\/]+$/, "");
  const cut = Math.max(trimmed.lastIndexOf("/"), trimmed.lastIndexOf("\\"));
  return cut >= 0 ? trimmed.slice(cut + 1) : trimmed;
}

function copyText(node) {
  if (node.nodeType === Node.TEXT_NODE) {
    return node.nodeValue ?? "";
  }
  if (node.nodeType === Node.ELEMENT_NODE && node.classList.contains("bubble-path")) {
    return node.dataset.path ? `@${node.dataset.path}` : "";
  }
  return Array.from(node.childNodes, copyText).join("");
}

function createPathChip(path, onOpenFile) {
  const chip = document.createElement("span");
  chip.className = "path-chip bubble-path";
  chip.dataset.path = path;
  chip.dataset.tooltip = path;

  const label = document.createElement("span");
  label.className = "path-chip-label";
  label.textContent = fileName(path);
  chip.append(createIcon({name: "attach"}), label);

  if (onOpenFile) {
    chip.setAttribute("role", "button");
    chip.tabIndex = 0;
    chip.addEventListener("click", () => onOpenFile(path));
    chip.addEventListener("keydown", (event) => {
      if (event.key === "Enter" || event.key === " ") {
        event.preventDefault();
        onOpenFile(path);
      }
    });
  }
  return chip;
}

function renderUserText(bubble, text, onOpenFile) {
  const pattern = /(^|\s)@(\S+)/g;
  let cursor = 0;
  let match;
  bubble.replaceChildren();
  while ((match = pattern.exec(text)) !== null) {
    const at = match.index + match[1].length;
    if (at > cursor) {
      bubble.append(text.slice(cursor, at));
    }
    bubble.append(createPathChip(match[2], onOpenFile));
    cursor = at + 1 + match[2].length;
  }
  if (cursor < text.length) {
    bubble.append(text.slice(cursor));
  }
}

export function createChatView({messages, onGroupToggle, onRetry, onOpenFile}) {
  let pendingEl = null;
  let agentEl = null;
  let thinking = null;
  let group = null;
  let turnRoot = null;
  let retryButton = null;

  // Entry state lives on the column, not on the block element: the parser
  // replaces every block it cannot reuse, so a per-target cursor is the only
  // marker that survives a rewrite. The class is dropped on animationend.
  const entries = new WeakMap();

  messages.addEventListener("animationend", (event) => {
    if (event.animationName === "chat-enter") {
      event.target.classList.remove("chat-enter");
    }
  });

  const entryState = (target) => {
    let state = entries.get(target);
    if (!state) {
      state = {primed: false, cursor: 0};
      entries.set(target, state);
    }
    return state;
  };

  const reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");

  const enterBubble = (element, silent) => {
    if (silent || reducedMotion.matches) {
      return;
    }
    if (document.body.classList.contains("is-dragging") || messages.getClientRects().length === 0) {
      return;
    }
    element.classList.add("chat-enter");
  };

  const renderBlocks = (target, text, silent) => {
    const state = entryState(target);
    const first = !state.primed;
    let seen = false;

    renderMarkdown(target, text, (element, index) => {
      seen = true;
      const eligible = first ? index === 0 : index >= state.cursor;
      state.cursor = Math.max(state.cursor, index + 1);
      if (!eligible || silent) {
        return;
      }
      if (element.dataset.type === "hr") {
        return;
      }
      if (document.body.classList.contains("is-dragging") || target.getClientRects().length === 0) {
        return;
      }
      element.classList.add("chat-enter");
    });

    if (seen) {
      state.primed = true;
    }
  };

  const scrollBottom = () => {
    messages.scrollTop = messages.scrollHeight;
  };

  const append = (element) => {
    messages.append(element);
    scrollBottom();
  };

  const clearPending = () => {
    pendingEl?.remove();
    pendingEl = null;
  };

  const ensureGroup = () => {
    if (!group) {
      group = createProcessGroup({onToggle: onGroupToggle});
      append(group.element);
    }
    return group;
  };

  const retireRetry = () => {
    retryButton?.remove();
    retryButton = null;
  };

  const failureTitle = (effect) => {
    const friendly = effect.code ? copy.chat.errors[effect.code] : "";
    return friendly || effect.message || copy.chat.errorUnknown;
  };

  const failureDetail = (effect) => {
    const lines = [];
    if (effect.code) {
      lines.push(`Code: ${effect.code}`);
    }
    if (effect.tool) {
      lines.push(`Tool: ${effect.tool}`);
    }
    if (effect.detail && effect.detail !== effect.message) {
      lines.push(effect.detail);
    }
    if (effect.message) {
      lines.push(effect.message);
    }
    return lines.join("\n");
  };

  const createFailureBanner = (effect) => {
    const banner = document.createElement("div");
    banner.className = "bubble bubble-failure";
    banner.setAttribute("role", "alert");

    const detail = failureDetail(effect);
    const row = document.createElement("div");
    row.className = "failure-row";

    const head = document.createElement(detail ? "button" : "div");
    head.className = "failure-head";
    head.append(createIcon({name: "error"}));

    const title = document.createElement("span");
    title.className = "failure-title";
    title.textContent = failureTitle(effect);
    head.append(title);

    const detailEl = document.createElement("div");
    detailEl.className = "failure-detail";
    detailEl.textContent = detail;
    detailEl.hidden = true;

    if (detail) {
      head.type = "button";
      head.dataset.expandable = "true";
      head.dataset.tooltip = copy.chat.showRawError;
      head.setAttribute("aria-expanded", "false");
      head.addEventListener("click", () => {
        const open = detailEl.hidden;
        detailEl.hidden = !open;
        head.setAttribute("aria-expanded", String(open));
        scrollBottom();
      });
    }

    const actions = document.createElement("div");
    actions.className = "failure-actions";

    if (detail) {
      const copyButton = createIconButton({
        icon: "copy",
        tooltip: copy.chat.copyDetails,
        onClick: () => {
          const clipboard = navigator.clipboard;
          if (!clipboard) {
            return;
          }
          clipboard.writeText(detail).then(() => {
            copyButton.dataset.tooltip = copy.chat.copied;
            copyButton.setAttribute("aria-label", copy.chat.copied);
            setTimeout(() => {
              copyButton.dataset.tooltip = copy.chat.copyDetails;
              copyButton.setAttribute("aria-label", copy.chat.copyDetails);
            }, 2000);
          }).catch(() => {
          });
        },
      });
      actions.append(copyButton);
    }

    retireRetry();
    if (onRetry) {
      retryButton = createIconButton({
        icon: "rerun",
        tooltip: copy.chat.retry,
        onClick: () => onRetry(),
      });
      actions.append(retryButton);
    }

    row.append(head);
    if (actions.childElementCount > 0) {
      row.append(actions);
    }
    banner.append(row, detailEl);
    return banner;
  };

  const applyEffect = (effect, silent) => {
    switch (effect.type) {
      case "user": {
        const bubble = document.createElement("div");
        bubble.className = "bubble bubble-user";
        renderUserText(bubble, effect.text, onOpenFile);
        bubble.addEventListener("copy", (event) => {
          const selection = window.getSelection();
          if (!selection || selection.rangeCount === 0 || selection.isCollapsed) {
            return;
          }
          const range = selection.getRangeAt(0);
          if (!bubble.contains(range.startContainer) || !bubble.contains(range.endContainer)) {
            return;
          }
          if (!bubble.querySelector(".bubble-path")) {
            return;
          }
          event.preventDefault();
          event.clipboardData.setData("text/plain", copyText(range.cloneContents()));
        });
        retireRetry();
        turnRoot = bubble;
        append(bubble);
        enterBubble(bubble, silent);
        break;
      }

      case "pending:show": {
        clearPending();
        pendingEl = document.createElement("div");
        pendingEl.className = "bubble bubble-agent";
        const row = document.createElement("span");
        row.className = "pending-status sweep-text";
        row.textContent = copy.chat.thinking;
        pendingEl.append(row);
        append(pendingEl);
        break;
      }

      case "pending:clear":
        clearPending();
        break;

      case "agent:start":
        agentEl = document.createElement("div");
        agentEl.className = "bubble bubble-agent";
        append(agentEl);
        break;

      case "agent:update":
        if (agentEl) {
          renderBlocks(agentEl, effect.text, silent);
          scrollBottom();
        }
        break;

      case "agent:close":
        agentEl = null;
        break;

      case "thinking:start":
        thinking = createThinkingBlock({startedAt: effect.startedAt});
        ensureGroup().body.append(thinking.element);
        scrollBottom();
        break;

      case "thinking:update":
        if (thinking) {
          thinking.setCapped(effect.capped);
          renderBlocks(thinking.body, effect.text, silent);
          scrollBottom();
        }
        break;

      case "thinking:finish":
        thinking?.finish(effect.elapsed);
        break;

      case "group:start":
        group = createProcessGroup({onToggle: onGroupToggle});
        append(group.element);
        break;

      case "group:settle":
        group?.settle(effect.collapse);
        break;

      case "tool:add":
        ensureGroup().body.append(createToolCapsule(effect.data));
        scrollBottom();
        break;

      case "ask":
        renderAskCard({append, frame: effect.frame});
        break;

      case "error": {
        if (effect.code === "INTERRUPTED") {
          break;
        }
        append(createFailureBanner(effect));
        break;
      }

      case "notice": {
        const bubble = document.createElement("div");
        bubble.className = "bubble bubble-error";
        bubble.textContent = effect.text;
        append(bubble);
        break;
      }

      case "reset":
        thinking?.stop();
        thinking = null;
        group = null;
        agentEl = null;
        pendingEl = null;
        turnRoot = null;
        retryButton = null;
        messages.replaceChildren();
        break;

      default:
        break;
    }
  };

  return {
    apply(effects, {silent = false} = {}) {
      for (const effect of effects) {
        applyEffect(effect, silent);
      }
    },
    reset() {
      applyEffect({type: "reset"});
    },
    dropTurn() {
      let node = turnRoot;
      while (node) {
        const next = node.nextElementSibling;
        node.remove();
        node = next;
      }
      turnRoot = null;
      retryButton = null;
      thinking?.stop();
      thinking = null;
      group = null;
      agentEl = null;
      pendingEl = null;
    },
  };
}
