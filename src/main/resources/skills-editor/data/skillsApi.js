import {HttpError, parseJson, readErrorDetail, requestJson} from "./http.js";

export async function listSkills() {
  const payload = await requestJson("/skills/sources");
  return payload?.sources ?? [];
}

// Only 404 is null; any other failure must reject.
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

async function postSkill(path, name, source) {
  const response = await fetch(path, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({name, source}),
  });
  const raw = await response.text();
  return {ok: response.ok, status: response.status, body: parseJson(raw), raw};
}

export function deploySkill(name, source) {
  return postSkill("/skills/deploy", name, source);
}

export function buildSkill(name, source) {
  return postSkill("/skills/build", name, source);
}

export async function pair(code) {
  const response = await fetch("/skills/pair", {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({code}),
  });
  return response.ok;
}
