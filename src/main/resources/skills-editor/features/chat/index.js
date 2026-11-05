// Connects the chat model to its view. This is the
// only object the assistant island talks to: it owns no streaming state + no
// DOM references of its own.

import {createChatModel} from "../../domain/chat/model.js";
import {createChatView} from "./view.js";

export function createChat({messages}) {
  const model = createChatModel();
  const view = createChatView({
    messages,
    onGroupToggle: () => model.toggleGroup(),
  });

  return {
    beginUserTurn(text) {
      view.apply(model.beginTurn(text, Date.now()));
    },

    handleEvent(frame) {
      view.apply(model.apply(frame, Date.now()));
    },

    finishTurn() {
      view.apply(model.finishTurn(Date.now()));
    },

    reset() {
      view.apply(model.reset());
    },

    notice(text) {
      view.apply(model.notice(text));
    },

    loadHistory(bubbles) {
      view.apply(model.loadHistory(bubbles), {silent: true});
    },
  };
}
