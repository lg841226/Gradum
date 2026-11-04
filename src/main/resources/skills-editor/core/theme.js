// The preference is either "light" or "dark" and is persisted under
// STORAGE_KEYS.theme. The resolved value is written to the editor root's
// `data-theme`, so tokens.css only switches between the two palettes inside the
// mounted container and never touches the host page.
//
// A host may inject a mode + token overrides at mount, and may call setTheme
// again later. A missing token is left to the stylesheet's default table rather
// than written as an empty value, so an incomplete palette stays readable.
//
// index.html repeats the storage key in its pre-paint script so the first frame
// is already themed; that is the one literal outside core/storageKeys.js.

import {STORAGE_KEYS} from "./storageKeys.js";
import {readStorage, writeStorage} from "./storage.js";

const PREFERENCES = ["light", "dark"];

function readPreference() {
  const stored = readStorage(STORAGE_KEYS.theme, "dark");
  return PREFERENCES.includes(stored) ? stored : "dark";
}

function tokenEntries(tokens) {
  if (!tokens || typeof tokens !== "object") {
    return [];
  }
  return Object.entries(tokens).filter(
    ([name, value]) => name && value != null && value !== "",
  );
}

export function createTheme(root, {mode, tokens} = {}) {
  const listeners = new Set();
  let preference = PREFERENCES.includes(mode) ? mode : readPreference();
  let overrides = tokenEntries(tokens);
  let applied = [];

  // Re-entrant: every apply first removes the overrides it wrote last time, so
  // a later call with a smaller token set cannot leave a stale value behind.
  const paint = () => {
    for (const name of applied) {
      root.style.removeProperty(`--${name}`);
    }
    applied = [];
    for (const [name, value] of overrides) {
      root.style.setProperty(`--${name}`, String(value));
      applied.push(name);
    }
    root.dataset.theme = preference;
  };

  const notify = () => {
    const value = {preference, resolved: preference};
    for (const listener of listeners) {
      listener(value);
    }
  };

  paint();

  const setTheme = (next = {}) => {
    if (PREFERENCES.includes(next.mode)) {
      preference = next.mode;
    }
    if (next.tokens !== undefined) {
      overrides = tokenEntries(next.tokens);
    }
    paint();
    notify();
  };

  return {
    preference: () => preference,
    resolved: () => preference,
    setTheme,
    cycle() {
      const next = PREFERENCES[(PREFERENCES.indexOf(preference) + 1) % PREFERENCES.length];
      writeStorage(STORAGE_KEYS.theme, next);
      setTheme({mode: next});
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    start() {
      paint();
      notify();
    },
  };
}
