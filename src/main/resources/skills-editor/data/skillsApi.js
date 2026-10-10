// All server communication lives here so features never touch fetch directly.

export async function listSkills() {
  const response = await fetch("/skills/sources");
  const payload = await response.json();

  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`);
  }

  return (payload && payload.sources) || [];
}

// Returns the source of a skill, or null when the server has no such skill.
// A malformed body is treated the same as a missing skill, so callers only have
// to guard against a network failure, which still rejects.
export async function loadSkill(name) {
  const response = await fetch(`/skills/source?name=${encodeURIComponent(name)}`);
  if (!response.ok) {
    return null;
  }

  try {
    const payload = await response.json();
    if (!payload) {
      return null;
    }
    return payload.source || "";
  } catch (_error) {
    return null;
  }

}

// Both actions post one skill source and read back the same compile result; only
// the endpoint differs, and the server decides what to do with the source.
async function postSkill(path, name, source) {
  const response = await fetch(path, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({name, source}),
  });
  const raw = await response.text();
  let body = null;

  try {
    body = raw ? JSON.parse(raw) : null;
  } catch (_error) {
    body = null;
  }

  return {ok: response.ok, status: response.status, body, raw};
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
