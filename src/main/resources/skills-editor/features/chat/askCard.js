// Ask-interaction cards: the agent's questions surfaced as buttons or a
// free-input row, answered through POST /events/respond.
//
// The card only acknowledges an answer after the server accepted it. A failed
// POST leaves the controls live and explains why, so the question is never
// silently marked answered when the deferred is still waiting.

import {respond} from "../../data/legatusApi.js";
import {copy} from "../../ui/copy.js";

/**
 * Flattens an L10nText wire value into plain text: `raw` carries the copy,
 * a `key` can only be resolved by the plugin, so its bundle key stands in.
 */
export function l10nText(value) {
  if (typeof value === "string") return value;
  if (!value) return "";
  if (value.kind === "raw") return value.text ?? "";
  return value.key ?? "";
}

export function renderAskCard({append, frame}) {
  const data = frame.data ?? {};
  const card = document.createElement("div");
  card.className = "ask-card";

  const heading = document.createElement("div");
  heading.className = "ask-title";
  heading.textContent = l10nText(data.title) || copy.chat.askTitle;
  card.append(heading);

  if (data.details) {
    const detailsLine = document.createElement("div");
    detailsLine.className = "ask-details";
    detailsLine.textContent = l10nText(data.details);
    card.append(detailsLine);
  }

  const sessionId = data.sessionId ?? frame.sessionId;
  const requestId = data.requestId;
  let busy = false;

  const controls = () => [...card.querySelectorAll("button, input")];

  const answer = async (payload, note) => {
    if (busy) {
      return;
    }
    busy = true;
    for (const control of controls()) {
      control.disabled = true;
    }
    try {
      await respond(sessionId, requestId, payload);
      card.replaceChildren(
        Object.assign(document.createElement("p"), {
          className: "ask-answered",
          textContent: note,
        }),
      );
    } catch (error) {
      console.error("respond failed", error);
      busy = false;
      for (const control of controls()) {
        control.disabled = false;
      }
      if (!card.querySelector(".ask-error")) {
        const failure = document.createElement("div");
        failure.className = "ask-error";
        failure.textContent = copy.chat.answerFailed(error.message);
        card.append(failure);
      }
    }
  };

  if (data.input) {
    const row = document.createElement("div");
    row.className = "ask-input";
    const field = document.createElement("input");
    field.placeholder = data.input.placeholder
      ? l10nText(data.input.placeholder)
      : copy.chat.answerPlaceholder;
    field.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        void answer({text: field.value}, copy.chat.answered(field.value));
      }
    });
    const send = document.createElement("button");
    send.type = "button";
    send.className = "ask-button";
    send.textContent = copy.chat.send;
    send.addEventListener("click", () => {
      void answer({text: field.value}, copy.chat.answered(field.value));
    });
    row.append(field, send);
    card.append(row);
    field.focus();
  } else {
    const actions = document.createElement("div");
    actions.className = "ask-actions";
    for (const choice of data.choices ?? []) {
      const semantics = choice.semantics;
      const label = copy.chat.choices[semantics] ?? semantics;
      const button = document.createElement("button");
      button.type = "button";
      button.className = "ask-button";
      button.dataset.semantics = semantics;
      button.textContent = label;
      button.addEventListener("click", () => {
        void answer({choice: semantics}, copy.chat.answered(label));
      });
      actions.append(button);
    }
    card.append(actions);
  }

  append(card);
}
