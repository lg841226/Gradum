// Projects the chat model's effects onto the message column.
//
// This module owns DOM nodes, not chat facts: the model decides what exists,
// the view builds the matching element once and then updates it in place. A
// node reference that goes stale is replaced by the next `*:start` effect, so
// a reset can never leave the renderer writing into a detached bubble.

import {renderMarkdown} from "../../ui/markdown.js";
import {copy} from "../../ui/copy.js";
import {renderAskCard} from "./askCard.js";
import {createProcessGroup, createToolCapsule} from "./processGroup.js";
import {createThinkingBlock} from "./thinking.js";

export function createChatView({messages, onGroupToggle}) {
  let pendingEl = null;
  let agentEl = null;
  let thinking = null;
  let group = null;

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

  // The user bubble enters once, on the node that the `user` effect creates.
  // That node is never rebuilt, so the class cannot be re-added later; the
  // gates mirror the prose blocks and the class comes off on animationend.
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

  // The leading block of a response animates once, on the first render that
  // has content. Streaming appends into the block it already created instead
  // of building a new one, so a per-created-block rule alone never fires for
  // a single-paragraph answer. Blocks created later still animate, and the
  // cursor keeps a rebuilt block from replaying.
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

  const applyEffect = (effect, silent) => {
    switch (effect.type) {
      case "user": {
        const bubble = document.createElement("div");
        bubble.className = "bubble bubble-user";
        bubble.textContent = effect.text;
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

      case "error":
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
  };
}
