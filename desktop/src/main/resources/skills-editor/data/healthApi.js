import {requestJson} from "./http.js";

export function fetchHealth() {
  return requestJson("/health");
}
