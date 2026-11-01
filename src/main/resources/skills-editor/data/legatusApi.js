// Wire access for the assistant (Gradum Legatus). Every call relies on the
// auth cookie the pairing flow set; none of them handles the token directly.

async function parseError(response) {
  let detail = `HTTP ${response.status}`;
  try {
    const body = await response.json();
    if (body && body.error) detail = body.error;
  } catch {
    // Non-JSON body: the status line is the whole story.
  }
  return detail;
}

export async function pairCode(code) {
  const response = await fetch("/skills/pair", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({code}),
  });
  if (!response.ok) throw new Error(await parseError(response));
  return response.json();
}

export async function listSessions(projectRoot) {
  const response = await fetch(`/sessions?projectRoot=${encodeURIComponent(projectRoot)}`);
  if (!response.ok) throw new Error(await parseError(response));
  return response.json();
}

export async function listModels() {
  const response = await fetch("/models");
  if (!response.ok) throw new Error(await parseError(response));
  const body = await response.json();
  return body.models ?? [];
}

export async function respond(sessionId, requestId, payload) {
  const response = await fetch("/events/respond", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({sessionId, requestId, ...payload}),
  });
  if (!response.ok) throw new Error(await parseError(response));
  return response.json();
}

export async function stop(runId) {
  const response = await fetch("/stop", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({runId}),
  });
  if (!response.ok) throw new Error(await parseError(response));
  return response.json();
}

/**
 * Posts a task and drains the NDJSON reply line by line.
 *
 * Calls [onEvent] with each parsed frame ({type, data, runId, sessionId,
 * seq, ...}); resolves when the stream ends. The abort signal doubles as
 * the Stop button: aborting kills the fetch, and POST /stop separately
 * tells the server to halt the run.
 */
export async function streamEvents(body, {onEvent, signal}) {
  const response = await fetch("/events", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify(body),
    signal,
  });
  if (!response.ok || !response.body) throw new Error(await parseError(response));

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let pending = "";

  for (; ;) {
    const {done, value} = await reader.read();
    if (done) break;
    pending += decoder.decode(value, {stream: true});

    let newlineAt = pending.indexOf("\n");
    while (newlineAt !== -1) {
      const line = pending.slice(0, newlineAt).trim();
      pending = pending.slice(newlineAt + 1);
      if (line) {
        try {
          onEvent(JSON.parse(line));
        } catch (error) {
          console.error("Undecodable event frame", line, error);
        }
      }
      newlineAt = pending.indexOf("\n");
    }
  }
}
