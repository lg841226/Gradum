import {HttpError, parseJson, readErrorDetail, requestJson} from "./http.js";

export async function fetchTree() {
  const payload = await requestJson("/workspace/tree");
  return Array.isArray(payload?.roots) ? payload.roots : [];
}

export async function fetchDirectory(projectRoot, path = "") {
  const params = new URLSearchParams({root: projectRoot});
  if (path) {
    params.set("path", path);
  }
  const payload = await requestJson(`/workspace/tree?${params}`);
  return Array.isArray(payload?.entries) ? payload.entries : [];
}

export function filePaths(roots) {
  const paths = [];
  const walk = (nodes) => {
    for (const node of nodes || []) {
      if (node?.directory) {
        walk(node.children);
      } else if (typeof node?.path === "string") {
        paths.push(node.path);
      }
    }
  };
  walk(roots);
  return paths;
}

export async function readFile(path) {
  const url = `/workspace/file?path=${encodeURIComponent(path)}`;
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
    throw new Error(`Malformed workspace response for ${path}`);
  }
  return typeof payload?.content === "string" ? payload.content : "";
}

async function postJson(url, path, content) {
  const response = await fetch(url, {
    method: "POST",
    headers: {"Content-Type": "application/json"},
    body: JSON.stringify({path, content}),
  });
  const raw = await response.text();
  const body = parseJson(raw);
  return {ok: response.ok, status: response.status, body, raw};
}

export function writeFile(path, content) {
  return postJson("/workspace/file", path, content);
}

export function deploySkill(path, content) {
  return postJson("/workspace/deploy", path, content);
}
