import {activeDoc} from "../core/store.js";
import {copy} from "../ui/copy.js";

// The strip across the window bottom: what the open document reports, read
// right to left as caret, line ending, encoding. The values come from the
// store, so this feature reads state without reaching into the editor.
export function mountStatusBar(store, {canvas}) {
  const bar = document.createElement("footer");
  bar.className = "statusbar";
  bar.setAttribute("aria-label", copy.statusBar.label);

  const position = document.createElement("span");
  position.className = "statusbar-item";

  // Line ending and encoding. Both hold fixed text for now because nothing on
  // the document carries either one yet, and neither is wired to a click: this
  // is the space and the hit area they will take. The hover tips come from the
  // copy, read by the document-wide [data-tooltip] bubble.
  const lineEnding = document.createElement("span");
  lineEnding.className = "statusbar-item";
  lineEnding.dataset.tooltip = copy.statusBar.lineEndingTip;
  const encoding = document.createElement("span");
  encoding.className = "statusbar-item";
  encoding.dataset.tooltip = copy.statusBar.encodingTip;

  // Every reported value sits at the right end, leaving the left one for counts
  // and connection state.
  const right = document.createElement("div");
  right.className = "statusbar-right";
  right.append(position, lineEnding, encoding);

  bar.append(right);
  canvas.appendChild(bar);

  const render = (state) => {
    const doc = activeDoc(state);
    // The picks describe the open document, so they clear with it, the way the
    // caret readout does.
    lineEnding.textContent = doc ? copy.statusBar.lineEnding : "";
    encoding.textContent = doc ? copy.statusBar.encoding : "";
    if (!doc) {
      position.textContent = "";
      position.removeAttribute("aria-label");
      position.removeAttribute("data-tooltip");
      return;
    }

    const {line, column} = state.cursor;
    // The strip shows the bare "line:column" a code window uses; the label
    // spells it out for screen readers, which the shorthand does not convey.
    position.textContent = copy.statusBar.position(line, column);
    position.setAttribute("aria-label", copy.statusBar.positionLabel(line, column));
    // The tip follows the caret, so it always spells out the position the
    // strip currently shows.
    position.dataset.tooltip = copy.statusBar.positionTip(line, column);
  };

  store.subscribe(render);
  render(store.getState());
  return {container: bar};
}
