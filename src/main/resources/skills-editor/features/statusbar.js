import {activeDoc} from "../core/store.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";

// A problem counter: a hollow severity icon beside its count. Both marks are
// drawn in the strip's own gray rather than a severity color, so the counts
// stay quiet and the shape alone tells the two apart.
function createCount({tone, icon}) {
  const item = document.createElement("span");
  item.className = "status-count";
  item.dataset.tone = tone;

  const value = document.createElement("span");
  value.className = "status-count-value";

  item.append(createIcon({name: icon}), value);
  return {item, value};
}

// Counts only change on a compile, so the DOM is left alone unless the number
// actually differs.
function updateCount(count, value, label) {
  const text = String(value);
  if (count.value.textContent === text) {
    return;
  }
  count.value.textContent = text;
  count.item.setAttribute("aria-label", label);
}

// The strip across the window bottom: what the open document reports. The
// problem counts lead it at the far left, and the caret, line ending and
// encoding trail it at the right. The values come from the store, so this
// feature reads state without reaching into the editor.
export function mountStatusBar(store, {canvas}) {
  const bar = document.createElement("footer");
  bar.className = "statusbar";
  bar.setAttribute("aria-label", copy.statusBar.label);

  // The active skill's errors and warnings, reported before anything else.
  const errors = createCount({tone: "error", icon: "error-outline"});
  const warnings = createCount({tone: "warning", icon: "warning-outline"});

  const left = document.createElement("div");
  left.className = "statusbar-left";
  left.append(errors.item, warnings.item);

  const position = document.createElement("span");
  position.className = "statusbar-item";

  // The file's indent unit, in spaces: a property of the whole buffer — how
  // wide one level is written — read from the editor and held steady while
  // the caret moves, with the default four for a file that indents nothing.
  const indent = document.createElement("span");
  indent.className = "statusbar-item";

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
  right.append(position, indent, lineEnding, encoding);

  bar.append(left, right);
  canvas.appendChild(bar);

  const render = (state) => {
    const doc = activeDoc(state);

    // With no document open there is nothing to count, so the pair goes with it
    // rather than reporting a brace of zeroes for a file that is not there.
    left.hidden = !doc;
    const diagnostics = (doc && doc.diagnostics) || [];
    const errorTotal = diagnostics.filter((item) => item.severity === "error").length;
    const warningTotal = diagnostics.filter((item) => item.severity === "warning").length;
    updateCount(errors, errorTotal, copy.statusBar.errors(errorTotal));
    updateCount(warnings, warningTotal, copy.statusBar.warnings(warningTotal));

    // The picks describe the open document, so they clear with it, the way the
    // caret readout does.
    lineEnding.textContent = doc ? copy.statusBar.lineEnding : "";
    encoding.textContent = doc ? copy.statusBar.encoding : "";
    if (!doc) {
      position.textContent = "";
      position.removeAttribute("aria-label");
      position.removeAttribute("data-tooltip");
      indent.textContent = "";
      indent.removeAttribute("aria-label");
      indent.removeAttribute("data-tooltip");
      return;
    }

    const indentText = copy.statusBar.indent(state.indent);
    indent.textContent = indentText;
    const indentLabel = copy.statusBar.indentLabel(state.indent);
    indent.setAttribute("aria-label", indentLabel);
    indent.dataset.tooltip = indentLabel;

    const {line, column} = state.cursor;
    // The strip shows the bare "line:column" a code window uses — until the
    // buffer holds several carets, where no single one of them is *the*
    // position, so the whole readout becomes the headcount instead. The
    // selection's size in parentheses trails either lead. The label spells
    // the readout out for screen readers, which the shorthand does not
    // convey, and carries the counts beside it the same way the strip does.
    const {selection} = state;
    const summary = selection
      ? ` (${copy.statusBar.selection(selection.characters, selection.newlines)})`
      : "";
    const multiple = state.cursorCount > 1;
    const lead = multiple
      ? copy.statusBar.cursors(state.cursorCount)
      : copy.statusBar.position(line, column);
    position.textContent = lead + summary;
    position.setAttribute(
      "aria-label",
      (multiple ? lead : copy.statusBar.positionLabel(line, column)) + summary,
    );
    // The tip follows the caret, so it always spells out the readout the
    // strip currently shows.
    position.dataset.tooltip =
      (multiple ? lead : copy.statusBar.positionTip(line, column)) + summary;
  };

  store.subscribe(render);
  render(store.getState());
  return {container: bar};
}
