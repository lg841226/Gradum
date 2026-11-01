// All server communication lives here so features never touch fetch directly.

import {HttpError, parseJson, readErrorDetail, requestJson} from "./http.js";

export async function listSkills() {
  const payload = await requestJson("/skills/sources");
  return payload?.sources ?? [];
}

// Returns the source of a skill, or null when the server has no such skill.
// Every other failure rejects: a 401 or a 500 must never look like an empty
// file, because the editor would then offer to deploy that emptiness over the
// real skill on disk.
export async function loadSkill(name) {
  const url = `/skills/source?name=${encodeURIComponent(name)}`;
  const response = await fetch(url);
  if (response.status === 404) {
    return null;
  }
  if (!response.ok) {
    throw new HttpError(response.status, await readErrorDetail(response), url);
  }

  let payload;
  try {
    payload = await response.json();
  } catch (_error) {
    throw new Error(`Malformed skill response for ${name}`);
  }
  return payload?.source ?? "";
}

// Both actions post one skill source and read back the same compile result; only
// the endpoint differs, and the server decides what to do with the source.
// The body is returned even on failure: the compile diagnostic is the payload.
async function postSkill(path, name, source) {
  const response = await fetch(path, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({name, source}),
  });
  const raw = await response.text();
  return {ok: response.ok, status: response.status, body: parseJson(raw), raw};
}

// Writes the source into the skills directory and reloads the registry, so the
// agent can call the skill as soon as the response comes back.
export function deploySkill(name, source) {
  return postSkill("/skills/deploy", name, source);
}

// Compiles the source and reports diagnostics without writing it anywhere or
// loading it, so the editor can check the buffer without changing the installed
// skill.
export function buildSkill(name, source) {
  return postSkill("/skills/build", name, source);
}

// Trades a pairing code for the auth cookie: how a device on another machine
// leaves reader mode without ever seeing the long server token. The code is
// shown on the host's console at startup and resets on every restart.
export async function pair(code) {
  const response = await fetch("/skills/pair", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({code}),
  });
  return response.ok;
}
