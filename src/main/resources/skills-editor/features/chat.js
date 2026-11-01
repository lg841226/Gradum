// Renders the NDJSON event stream into the message column.
//
// One run = one streaming turn. `response` events arrive repeatedly as the
// server flushes its text buffer, so the agent bubble is reused and grown
// until the next distinct block (thinking, tool, ask) starts; that keeps
// the transcript in wire order without reflowing the whole column.

import {respond} from "../data/legatusApi.js";
import {l10nText, renderAskCard} from "./ask.js";

export function createChat({messages, status, stopButton, onEmptyChange}) {
  let agentBubble = null;
  let thinking = null;
  const toolCards = new Map();

  const setTone = (tone, text) => {
    status.dataset.tone = tone;
    status.textContent = text;
  };

  const scrollBottom = () => {
    messages.scrollTop = messages.scrollHeight;
  };

  const append = (element) => {
    if (onEmptyChange) onEmptyChange(false);
    messages.append(element);
    scrollBottom();
  };

  const closeAgentBubble = () => {
    agentBubble = null;
  };

  const agentText = (text) => {
    if (!agentBubble) {
      agentBubble = document.createElement("div");
      agentBubble.className = "bubble bubble-agent";
      agentBubble.textContent = "";
      append(agentBubble);
    }
    agentBubble.textContent += text;
    scrollBottom();
  };

  const newThinking = () => {
    closeAgentBubble();
    thinking = document.createElement("details");
    thinking.className = "block";
    const summary = document.createElement("summary");
    summary.textContent = "Thinking";
    const body = document.createElement("div");
    body.className = "block-body";
    thinking.append(summary, body);
    append(thinking);
    return body;
  };

  const newToolCard = (callId, alias, tool) => {
    closeAgentBubble();
    const card = document.createElement("details");
    card.className = "block";
    const summary = document.createElement("summary");
    summary.textContent = `${alias || tool} — running\u2026`;
    const body = document.createElement("div");
    body.className = "block-body";
    card.append(summary, body);
    card._summary = summary;
    append(card);
    if (callId) toolCards.set(callId, card);
    return card;
  };

  const renderChoiceButton = (card, frame, semantics, label) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "ask-button";
    button.dataset.semantics = semantics;
    button.textContent = label;
    button.addEventListener("click", async () => {
      card.replaceChildren(
        Object.assign(document.createElement("p"), {
          className: "ask-answered",
          textContent: `Answered: ${label}`,
        }),
      );
      try {
        await respond(frame.data.sessionId, frame.data.requestId, {choice: semantics});
      } catch (error) {
        console.error("respond failed", error);
      }
    });
    return button;
  };

  const handleEvent = (frame) => {
    const data = frame.data ?? {};

    switch (frame.type) {
      case "response": {
        const content = typeof data.content === "string" ? data.content : "";
        if (content) agentText(content);
        break;
      }

      case "thinking": {
        const body = thinking?.isConnected ? thinking.querySelector(".block-body") : newThinking();
        body.textContent += data.content ?? "";
        scrollBottom();
        break;
      }

      case "tool_call_start":
        newToolCard(data.toolCallId, data.alias, data.tool);
        break;

      case "tool_call": {
        closeAgentBubble();
        const card = toolCards.get(data.toolCallId) ?? newToolCard(data.toolCallId, data.alias, data.tool);
        const success = data.success === true;
        if (success) delete card.dataset.failed;
        else card.dataset.failed = "true";
        card._summary.textContent = `${data.alias || data.tool} — ${success ? "done" : "failed"}`;
        card.querySelector(".block-body").textContent =
          JSON.stringify(data.result, null, 2);
        scrollBottom();
        break;
      }

      case "ask_interaction": {
        closeAgentBubble();
        renderAskCard({
          append,
          frame,
          title: l10nText(data.title),
          details: data.details ? l10nText(data.details) : null,
          input: data.input ?? null,
        });
        break;
      }

      case "error": {
        closeAgentBubble();
        const turnFatal = data.scope === "turn";
        if (turnFatal) {
          const bubble = document.createElement("div");
          bubble.className = "bubble bubble-error";
          bubble.textContent = data.message ?? JSON.stringify(data);
          append(bubble);
          setTone("error", "Error");
        } else {
          // Tool-scoped failures are already on the tool card; keep the stream.
        }
        break;
      }

      case "session_end":
        closeAgentBubble();
        break;

      case "playback_start":
        messages.replaceChildren();
        if (onEmptyChange) onEmptyChange(messages.children.length > 0);
        break;

      default:
        // sub_agent:*, guardrail, background_result, mission_revoked —
        // surfaced in a later revision; the wire keeps them ordered so
        // nothing is lost by skipping them visually.
        break;
    }
  };

  // A visible system notice inside the message column, for setup problems
  // (missing project root) that would otherwise vanish in the status line.
  const notice = (text) => {
    const bubble = document.createElement("div");
    bubble.className = "bubble bubble-error";
    bubble.textContent = text;
    append(bubble);
  };

  const beginUserTurn = (text) => {
    thinking = null;
    toolCards.clear();
    stopButton.hidden = false;

    const bubble = document.createElement("div");
    bubble.className = "bubble bubble-user";
    bubble.textContent = text;
    append(bubble);
  };

  const finishTurn = () => {
    closeAgentBubble();
    stopButton.hidden = true;
    setTone("idle", "Idle");
  };

  // Stop wiring lives in the caller: only it knows the AbortController and
  // the moment the first frame names the runId.

  return {handleEvent, beginUserTurn, finishTurn, setTone, notice};
}
