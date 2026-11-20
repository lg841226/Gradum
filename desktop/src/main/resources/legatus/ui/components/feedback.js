export function createEmptyState({title, hint, tone = "neutral"} = {}) {
  const state = document.createElement("div");
  state.className = "empty-state";
  state.dataset.tone = tone;

  const titleNode = document.createElement("span");
  titleNode.className = "empty-state-title";
  titleNode.textContent = title;
  state.appendChild(titleNode);

  if (hint) {
    const hintNode = document.createElement("span");
    hintNode.className = "empty-state-hint";
    hintNode.textContent = hint;
    state.appendChild(hintNode);
  }
  return state;
}
