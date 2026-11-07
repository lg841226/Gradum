// The one HTTP error shape + the one place a response body is read.
//
// Callers branch on `status`: 401/403 opens the unlock flow, 404 means the
// resource is absent, anything else is a server failure. Collapsing those into
// "no content" is what let an unauthenticated read look like an empty file.

export class HttpError extends Error {
  constructor(status, detail, url = "") {
    super(detail || `HTTP ${status}`);
    this.name = "HttpError";
    this.status = status;
    this.detail = detail || `HTTP ${status}`;
    this.url = url;
  }
}

export async function readErrorDetail(response) {
  let detail = `HTTP ${response.status}`;
  try {
    const body = await response.json();
    if (body && typeof body.error === "string") {
      detail = body.error;
    }
  } catch (_error) {
  }
  return detail;
}

export async function ensureOk(response, url = "") {
  if (!response.ok) {
    throw new HttpError(response.status, await readErrorDetail(response), url);
  }
  return response;
}

let reachable = true;
const watchers = new Set();

function publish(next) {
  if (reachable === next) {
    return;
  }
  reachable = next;
  for (const listener of watchers) {
    listener(next);
  }
}

export function watchConnection(listener) {
  watchers.add(listener);
  listener(reachable);
  return () => {
    watchers.delete(listener);
  };
}

export function markUnreachable() {
  publish(false);
}

export async function reachableFetch(url, init) {
  let response;
  try {
    response = await fetch(url, init);
  } catch (error) {
    publish(false);
    throw error;
  }
  if (response.ok) {
    publish(true);
  }
  return response;
}

export async function requestJson(url, init) {
  const response = await reachableFetch(url, init);
  await ensureOk(response, url);
  return response.json();
}

export function parseJson(text) {
  if (!text) {
    return null;
  }
  try {
    return JSON.parse(text);
  } catch (_error) {
    return null;
  }
}
