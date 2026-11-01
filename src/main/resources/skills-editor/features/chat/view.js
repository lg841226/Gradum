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

  const applyEffect = (effect) => {
    switch (effect.type) {
      case "user": {
        const bubble = document.createElement("div");
        bubble.className = "bubble bubble-user";
        bubble.textContent = effect.text;
        append(bubble);
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
          renderMarkdown(agentEl, effect.text);
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
          renderMarkdown(thinking.body, effect.text);
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
    apply(effects) {
      for (const effect of effects) {
        applyEffect(effect);
      }
    },
    reset() {
      applyEffect({type: "reset"});
    },
  };
}
