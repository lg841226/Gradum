// Ask-interaction cards: the agent's questions surfaced as buttons or a
// free-input row, answered through POST /events/respond.

import {respond} from "../data/legatusApi.js";

// Friendly labels for the stable semantic codes; the server sends
// semantics, never display copy, so the mapping lives client-side.
const SEMANTIC_LABELS = {
  allow_once: "Allow once",
  allow_always: "Allow always",
  reject: "Reject",
};

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

export function renderAskCard({append, frame, title, details, input}) {
  const card = document.createElement("div");
  card.className = "ask-card";

  const heading = document.createElement("div");
  heading.className = "ask-title";
  heading.textContent = title || "Gradum needs your input";
  card.append(heading);

  if (details) {
    const detailsLine = document.createElement("div");
    detailsLine.className = "ask-details";
    detailsLine.textContent = details;
    card.append(detailsLine);
  }

  const sessionId = frame.data.sessionId ?? frame.sessionId;
  const requestId = frame.data.requestId;

  const answer = async (payload, note) => {
    card.replaceChildren(
      Object.assign(document.createElement("p"), {
        className: "ask-answered",
        textContent: note,
      }),
    );
    try {
      await respond(sessionId, requestId, payload);
    } catch (error) {
      console.error("respond failed", error);
    }
  };

  if (input) {
    const row = document.createElement("div");
    row.className = "ask-input";
    const field = document.createElement("input");
    field.placeholder = input.placeholder ? l10nText(input.placeholder) : "";
    field.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        event.preventDefault();
        void answer({text: field.value}, `Answered: ${field.value}`);
      }
    });
    const send = document.createElement("button");
    send.type = "button";
    send.className = "ask-button";
    send.textContent = "Send";
    send.addEventListener("click", () => void answer({text: field.value}, `Answered: ${field.value}`));
    row.append(field, send);
    card.append(row);
    field.focus();
  } else {
    const actions = document.createElement("div");
    actions.className = "ask-actions";
    for (const choice of frame.data.choices ?? []) {
      const semantics = choice.semantics;
      actions.append(
        Object.assign(document.createElement("button"), {
          type: "button",
          className: "ask-button",
          dataset: {semantics},
          textContent: SEMANTIC_LABELS[semantics] ?? semantics,
          onclick: () => void answer({choice: semantics}, `Answered: ${SEMANTIC_LABELS[semantics] ?? semantics}`),
        }),
      );
    }
    card.append(actions);
  }

  append(card);
}
