// The share one pane keeps when the divider is dragged. Held between a fifth
// and four fifths, so neither pane can be dragged shut: closing one outright
// is what the split toggle is for.

export const MIN_SHARE = 0.2;
export const MAX_SHARE = 0.8;

export function clampShare(value) {
  return Math.min(Math.max(value, MIN_SHARE), MAX_SHARE);
}
