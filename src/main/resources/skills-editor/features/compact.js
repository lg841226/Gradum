// Panels step aside when the window can no longer hold them and a usable editor
// at the same time: the rail gives up its width, and the bottom tool window its
// height. What counts as too small comes from the panel sizes the state already
// carries plus the layout tokens, so a panel dragged wider steps aside sooner
// rather than waiting for a fixed window width to be crossed.
//
// The result is an override, not a choice: the store keeps what the user last
// asked for, and this only mixes in while the window is small, so growing the
// window back restores whichever panels they had open.

const EDITOR_MIN_WIDTH = 420;
const EDITOR_MIN_HEIGHT = 220;

export function mountCompact(store, {canvas}) {
  // Tokens live on the editor root, not :root, so read them from the mounted
  // container: an embedding page never defines the editor's own values. They
  // are re-read here rather than cached at mount, because the interface scale
  // rewrites them and the break points have to move with it. The saved
  // rail/bottom numbers are design pixels, so they are multiplied by the same
  // factor before comparing.
  const evaluate = () => {
    const state = store.getState();
    const root = getComputedStyle(canvas);
    const token = (name) => parseFloat(root.getPropertyValue(name)) || 0;
    const gap = token("--island-gap");
    const menubarHeight = token("--menubar-height");
    const statusbarHeight = token("--statusbar-height");
    const scale = token("--ui-scale") || 1;
    const editorWidth = canvas.clientWidth - gap * 3 - state.railWidth * scale;
    const editorHeight = canvas.clientHeight
      - gap * 5 - menubarHeight - statusbarHeight - state.bottomHeight * scale;

    const autoRail = editorWidth < EDITOR_MIN_WIDTH * scale;
    const autoBottom = editorHeight < EDITOR_MIN_HEIGHT * scale;

    if (autoRail !== state.autoRail || autoBottom !== state.autoBottom) {
      store.setState({autoRail, autoBottom});
    }
  };

  store.subscribe(evaluate);
  new ResizeObserver(evaluate).observe(canvas);
  // The interface scale changes the metrics this feature reads without
  // changing the canvas box, so the shared resize event is the signal that
  // the break points moved.
  window.addEventListener("resize", evaluate);
  evaluate();
}
