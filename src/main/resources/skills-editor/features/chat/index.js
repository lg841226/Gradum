// Connects the chat model to its view. This is the
// only object the assistant island talks to: it owns no streaming state + no
// DOM references of its own.

import {createChatModel} from "../../domain/chat/model.js";
import {createChatView} from "./view.js";

export function createChat({messages, onRetry, onOpenFile}) {
  const model = createChatModel();
  const view = createChatView({
    messages,
    onGroupToggle: () => model.toggleGroup(),
    onRetry,
    onOpenFile,
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

    fail(error) {
      view.apply(model.fail(error));
    },

    discardTurn() {
      view.apply(model.finishTurn(Date.now()));
      view.dropTurn();
    },

    loadHistory(bubbles) {
      view.apply(model.loadHistory(bubbles), {silent: true});
    },
  };
}
