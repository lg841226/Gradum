import {stop as stopRun, streamEvents} from "../../data/legatusApi.js";

const runKey = (sessionId) => `gradum.run.${sessionId}`;

function persistRunKey(sessionId, runId) {
  if (!sessionId || !runId) {
    return;
  }
  sessionStorage.setItem(runKey(sessionId), JSON.stringify({runId}));
}

function clearRunKey(sessionId) {
  if (sessionId) {
    sessionStorage.removeItem(runKey(sessionId));
  }
}

export function readRunId(sessionId) {
  const raw = sessionStorage.getItem(runKey(sessionId));
  if (!raw) {
    return null;
  }
  try {
    const parsed = JSON.parse(raw);
    return typeof parsed?.runId === "string" ? parsed.runId : null;
  } catch (_error) {
    return null;
  }
}

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

  const wireEvents = (session, body, options) => streamEvents(body, {
    signal: session.controller.signal,
    ...options,
    onEvent: (frame) => {
      if (session.generation !== generation) {
        return;
      }
      if (frame.runId && frame.runId !== session.runId) {
        session.runId = frame.runId;
        persistRunKey(session.sessionId, frame.runId);
      }
      onFrame(frame);
    },
  });

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
        sessionId: body.sessionId ?? null,
        generation: ++generation,
      };
      active = session;
      onStart?.();

      wireEvents(session, body)
        .then(() => {
          clearRunKey(session.sessionId);
        })
        .catch((error) => {
          if (session.generation === generation && error.name !== "AbortError") {
            onError?.(error);
          }
        })
        .finally(() => release(session));

      return true;
    },

    resume(runId, sessionId) {
      if (active) {
        return Promise.reject(new Error("a run is already streaming"));
      }

      const session = {
        controller: new AbortController(),
        runId,
        sessionId,
        generation: ++generation,
      };
      active = session;
      onStart?.();

      const stream = wireEvents(session, {}, {runId, since: 0});

      stream
        .then(() => clearRunKey(sessionId))
        .catch((error) => {
          if (error.status === 404) {
            clearRunKey(sessionId);
            return;
          }
          if (session.generation === generation && error.name !== "AbortError") {
            onError?.(error);
          }
        })
        .finally(() => release(session));

      return stream;
    },

    stop() {
      const session = active;
      if (!session) {
        return;
      }
      session.controller.abort();
      clearRunKey(session.sessionId);
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
      clearRunKey(session.sessionId);
      if (session.runId) {
        void stopRun(session.runId).catch(console.error);
      }
      active = null;
      onEnd?.();
    },
  };
}
