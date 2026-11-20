import {parseJson} from "./http.js";

async function postSkill(path, name, source) {
  const response = await fetch(path, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({name, source}),
  });
  const raw = await response.text();
  return {ok: response.ok, status: response.status, body: parseJson(raw), raw};
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
