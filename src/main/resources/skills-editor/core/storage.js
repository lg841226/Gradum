// Storage access that never throws. A browser may deny localStorage in private
// mode or when the quota is full; callers get the fallback and keep working for
// the rest of the session.

export function readStorage(key, fallback = null) {
  try {
    const value = window.localStorage.getItem(key);
    return value === null ? fallback : value;
  } catch (_error) {
    return fallback;
  }
}

export function writeStorage(key, value) {
  try {
    window.localStorage.setItem(key, String(value));
    return true;
  } catch (_error) {
    return false;
  }
}

export function readJsonStorage(key, fallback) {
  const raw = readStorage(key, null);
  if (raw === null) {
    return fallback;
  }
  try {
    const parsed = JSON.parse(raw);
    return parsed ?? fallback;
  } catch (_error) {
    return fallback;
  }
}
