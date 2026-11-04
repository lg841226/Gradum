// The one owner of an in-flight run: AbortController, runId, generation +
// the streaming flag all live here.
//
// Stop is two actions, not a choice: abort the fetch so the browser stops
// rendering, and tell the server to halt the run. A reset (new chat, another
// session) invalidates the generation first, so late frames + a late
// finally cannot touch the turn that replaced them.

import {stop as stopRun, streamEvents} from "../../data/legatusApi.js";

export function createStreamController({onFrame, onStart, onEnd, onError}) {
  let active = null;
  let generation = 0;

  const release = (session) => {
    if (session.generation !== generation) {
      return;
    }
    active = null;
    onEnd?.();
  };

  return {
    get streaming() {
      return active !== null;
    },

    start(body) {
      if (active) {
        return false;
      }

      const session = {
        controller: new AbortController(),
        runId: null,
        generation: ++generation,
      };
      active = session;
      onStart?.();

      streamEvents(body, {
        signal: session.controller.signal,
        onEvent: (frame) => {
          if (session.generation !== generation) {
            return;
          }
          if (frame.runId) {
            session.runId = frame.runId;
          }
          onFrame(frame);
        },
      })
        .catch((error) => {
          if (session.generation === generation && error.name !== "AbortError") {
            onError?.(error);
          }
        })
        .finally(() => release(session));

      return true;
    },

    stop() {
      const session = active;
      if (!session) {
        return;
      }
      session.controller.abort();
      if (session.runId) {
        void stopRun(session.runId).catch(console.error);
      }
    },

    reset() {
      const session = active;
      if (!session) {
        return;
      }
      generation += 1;
      session.controller.abort();
      if (session.runId) {
        void stopRun(session.runId).catch(console.error);
      }
      active = null;
      onEnd?.();
    },
  };
}
