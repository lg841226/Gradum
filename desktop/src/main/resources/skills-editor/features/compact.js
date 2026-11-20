// The middle slot steps aside when the window can no longer hold it and a
// usable editor at the same time. What counts as too small comes from the
// panel size the state already carries plus the layout tokens, so a bottom
// row dragged taller steps aside sooner rather than waiting for a fixed
// window height to be crossed.
//
// The result is an override, not a choice: the store keeps what the user last
// asked for, and this only mixes in while the window is small, so growing the
// window back restores whichever panels they had open.

const EDITOR_MIN_HEIGHT = 220;

export function mountCompact(store, {canvas}) {
  const evaluate = () => {
    const state = store.getState();
    const root = getComputedStyle(canvas);
    const token = (name) => parseFloat(root.getPropertyValue(name)) || 0;
    const gap = token("--island-gap");
    const menubarHeight = token("--menubar-height");
    const statusbarHeight = token("--statusbar-height");
    const scale = token("--ui-scale") || 1;
    const editorHeight = canvas.clientHeight
      - gap * 5 - menubarHeight - statusbarHeight - state.bottomHeight * scale;

    const autoBottom = editorHeight < EDITOR_MIN_HEIGHT * scale;

    if (autoBottom !== state.autoBottom) {
      store.setState({autoBottom});
    }
  };

  store.subscribe(evaluate);
  new ResizeObserver(evaluate).observe(canvas);
  window.addEventListener("resize", evaluate);
  evaluate();
}
