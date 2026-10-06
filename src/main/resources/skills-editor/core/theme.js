// Single source of truth for the editor's color theme.
//
// The preference is either "light" or "dark" and is persisted under
// [STORAGE_KEY]. The resolved value is written to <HTML data-theme> so the CSS
// only switches on the two concrete palettes in tokens.css.
//
// index.html repeats [STORAGE_KEY] in its pre-paint script so the first frame
// is already themed; keep the two literals in sync.

export const STORAGE_KEY = "gradum.skillsEditor.theme";

const PREFERENCES = ["light", "dark"];

function readPreference() {
  try {
    const stored = window.localStorage.getItem(STORAGE_KEY);
    return PREFERENCES.includes(stored) ? stored : "dark";
  } catch (storageError) {
    return "dark";
  }
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
      try {
        window.localStorage.setItem(STORAGE_KEY, preference);
      } catch (storageError) {
        // A failed write only costs persistence, not the in-session switch.
      }
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
