import {STORAGE_KEYS} from "./storageKeys.js";
import {readStorage, writeStorage} from "./storage.js";

const SYSTEM = "system";
const PREFERENCES = ["light", "dark", SYSTEM];
const LIGHT_QUERY = "(prefers-color-scheme: light)";

function systemPrefersLight() {
  return window.matchMedia ? window.matchMedia(LIGHT_QUERY).matches : false;
}

function resolve(preference) {
  return preference === SYSTEM ? (systemPrefersLight() ? "light" : "dark") : preference;
}

function readPreference() {
  const stored = readStorage(STORAGE_KEYS.theme, "dark");
  return PREFERENCES.includes(stored) ? stored : "dark";
}

export function createTheme(root, {mode} = {}) {
  const listeners = new Set();
  let preference = PREFERENCES.includes(mode) ? mode : readPreference();

  const paint = () => {
    root.dataset.theme = resolve(preference);
  };

  const notify = () => {
    const value = {preference, resolved: resolve(preference)};
    for (const listener of listeners) {
      listener(value);
    }
  };

  paint();

  const media = window.matchMedia?.(LIGHT_QUERY);
  media?.addEventListener("change", () => {
    if (preference !== SYSTEM) {
      return;
    }
    paint();
    notify();
  });

  const setTheme = (next = {}) => {
    if (PREFERENCES.includes(next.mode)) {
      preference = next.mode;
    }
    paint();
    notify();
  };

  const setMode = (mode) => {
    if (!PREFERENCES.includes(mode)) {
      return;
    }
    writeStorage(STORAGE_KEYS.theme, mode);
    setTheme({mode});
  };

  return {
    preference: () => preference,
    resolved: () => resolve(preference),
    setTheme,
    setMode,
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
