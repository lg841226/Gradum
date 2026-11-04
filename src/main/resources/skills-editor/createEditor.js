import {mountCommands} from "./features/commands.js";
import {mountEditor} from "./features/editor.js";
import {mountTabs} from "./features/tabs.js";
import {createTheme} from "./core/theme.js";
import {copy} from "./ui/copy.js";

// The editor's assembly entry. A host builds one of these with the pieces the
// core cannot know: how documents are opened + saved, how the active document
// is resolved, which commands it wants on the container + the theme it wants
// painted. Everything below this file is the editor itself; main.js stays a
// shell that mounts the panels around it.
export function createEditor(store, {
  canvas,
  showPanel,
  documents,
  getActiveDoc,
  commands = [],
  theme = {},
}) {
  let tabs = null;
  let editor = null;

  // The tokens + the layout rules both key off `.canvas`, so the mount root
  // carries that class whether the page supplied it or not.
  canvas.classList.add("canvas");

  const themeControl = createTheme(canvas, theme);
  themeControl.start();

  const commandRegistry = mountCommands(canvas, commands, {
    store,
    getActiveDoc,
    getContext: () => ({editor, tabs, showPanel}),
  });

  editor = mountEditor(store, {
    canvas,
    showPanel,
    documents,
    getActiveDoc,
    runCommand: (id) => commandRegistry.run(id),
  });

  tabs = mountTabs(store, {
    strips: [
      {strip: editor.panes[0].tabStrip},
      {
        strip: editor.panes[1].tabStrip,
        closeTooltip: copy.tooltip.unsplit,
        onClose: () => editor.unsplit(),
      },
    ],
    documents,
  });

  return {
    ...editor,
    tabs,
    commands: commandRegistry,
    theme: themeControl,
  };
}
