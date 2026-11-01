// Single source of truth for the editor's color theme.
//
// The preference is either "light" or "dark" and is persisted under
// STORAGE_KEYS.theme. The resolved value is written to <HTML data-theme> so the
// CSS only switches on the two concrete palettes in tokens.css.
//
// index.html repeats the key in its pre-paint script so the first frame is
// already themed; that is the one literal outside core/storageKeys.js.

import {STORAGE_KEYS} from "./storageKeys.js";
import {readStorage, writeStorage} from "./storage.js";

const PREFERENCES = ["light", "dark"];

function readPreference() {
  const stored = readStorage(STORAGE_KEYS.theme, "dark");
  return PREFERENCES.includes(stored) ? stored : "dark";
}

export function createTheme() {
  const listeners = new Set();
  let preference = readPreference();

  const apply = () => {
    document.documentElement.dataset.theme = preference;
  };

  const notify = () => {
    const value = {preference, resolved: preference};
    for (const listener of listeners) {
      listener(value);
    }
  };

  return {
    preference: () => preference,
    resolved: () => preference,
    cycle() {
      preference = PREFERENCES[(PREFERENCES.indexOf(preference) + 1) % PREFERENCES.length];
      writeStorage(STORAGE_KEYS.theme, preference);
      apply();
      notify();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
    start() {
      apply();
      notify();
    },
  };
}
