// All server communication lives here so features never touch fetch directly.

export async function listSkills() {
  const response = await fetch("/skills/sources");
  if (!response.ok) throw new Error(`HTTP ${response.status}`);
  const payload = await response.json();
  return (payload && payload.sources) || [];
}

// Returns the source of a skill, or null when the server has no such skill.
// A malformed body is treated the same as a missing skill, so callers only have
// to guard against a network failure, which still rejects.
export async function loadSkill(name) {
  const response = await fetch(`/skills/source?name=${encodeURIComponent(name)}`);
  if (!response.ok) return null;
  try {
    const payload = await response.json();
    return payload ? payload.source || "" : null;
  } catch (_error) {
    return null;
  }
}

export async function deploySkill(name, source) {
  const response = await fetch("/skills/deploy", {
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
