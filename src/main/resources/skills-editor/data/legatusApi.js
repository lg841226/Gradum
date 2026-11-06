import {ensureOk, HttpError, requestJson} from "./http.js";
import {createNdjsonParser} from "./ndjson.js";

export async function listSessions(projectRoot) {
  return requestJson(`/sessions?projectRoot=${encodeURIComponent(projectRoot)}`);
}

export async function sessionState(projectRoot, sessionId) {
  return requestJson(
    `/session/state?projectRoot=${encodeURIComponent(projectRoot)}` +
    `&sessionId=${encodeURIComponent(sessionId)}`,
  );
}

export async function listModels() {
  const body = await requestJson("/models");
  return body?.models ?? [];
}

export async function respond(sessionId, requestId, payload) {
  return requestJson("/events/respond", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({sessionId, requestId, ...payload}),
  });
}

export async function stop(runId) {
  return requestJson("/stop", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({runId}),
  });
}

export async function streamEvents(body, {onEvent, signal, onParseError}) {
  const response = await fetch("/events", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok || !response.body) {
    await ensureOk(response, "/events");
    throw new HttpError(0, "The event stream has no body", "/events");
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  const parser = createNdjsonParser({
    onFrame: onEvent,
    onParseError: onParseError
      ?? ((error, line) => console.error("Undecodable event frame", line, error)),
  });

  for (; ;) {
    const {done, value} = await reader.read();
    if (done) {
      break;
    }
    parser.push(decoder.decode(value, {stream: true}));
  }
  parser.push(decoder.decode());
  parser.flush();
}
