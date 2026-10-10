import {createButton, createIconButton} from "../ui/components/button.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";
import {createTheme} from "../core/theme.js";

// The view menu: the three views as plain text in a bar across the top, the way
// a desktop window lists its menus. The menu owns the selected view; Problems
// and Console also drive the bottom tool window, while Explorer toggles the rail
// the way the platform toggles a tool window from its own bar button. The run
// widget sits in the middle and names the skill it would run.
export function mountMenubar(store, {canvas, onRun, onBuild, onToggleSplit}) {
  const bar = document.createElement("nav");
  bar.className = "menubar";

  const views = [
    {
      name: "explorer",
      label: copy.control.explorer,
      mnemonic: 0,
      select: (state) => ({view: "explorer", railCollapsed: !state.railCollapsed}),
    },
    {
      name: "problems",
      label: copy.control.problems,
      mnemonic: 0,
      select: () => ({view: "problems", panel: "problems", collapsed: false}),
    },
    {
      name: "output",
      label: copy.control.output,
      mnemonic: 0,
      select: () => ({view: "output", panel: "output", collapsed: false}),
    },
    // Two layout presets close the list: the editor alone, and everything back.
    // Editor skips its first letter: the E already belongs to Explorer, so its
    // access key falls to the next letter, the way a desktop menu resolves a
    // duplicate.
    {
      name: "editor",
      label: copy.control.editor,
      mnemonic: 1,
      select: () => ({view: "editor", railCollapsed: true, collapsed: true}),
    },
    {
      name: "all",
      label: copy.control.all,
      mnemonic: 0,
      select: () => ({view: "all", railCollapsed: false, collapsed: false}),
    },
  ];

  const viewsGroup = document.createElement("div");
  viewsGroup.className = "menubar-group";

  // The brand leads the bar, the way a desktop window titles itself before
  // listing its menus.
  const brand = document.createElement("div");
  const logo = document.createElement("img");
  const brandName = document.createElement("span");

  logo.alt = "";
  logo.setAttribute("aria-hidden", "true");
  logo.src = "/skills/editor/assets/color_logo.svg";

  logo.className = "menubar-logo";
  brand.className = "menubar-brand";
  brandName.className = "menubar-brand-name";
  brandName.textContent = copy.brand.name;
  brand.append(logo, brandName);

  const items = views.map((view) => {
    const button = createButton({
      label: view.label,
      variant: "menu",
      onClick: () => store.setState(view.select(store.getState())),
    });
    underlineMnemonic(button, view.mnemonic);
    viewsGroup.appendChild(button);
    return {name: view.name, button};
  });

  const widget = document.createElement("div");
  widget.className = "run-widget";

  const widgetName = document.createElement("span");
  widgetName.className = "run-widget-name";

  // The widget carries both actions in the order the platform lists them: build
  // compiles the buffer without installing it, run compiles and installs. They
  // sit in fixed-size icon buttons, so swapping an icon for the spinner while one
  // of them works never shifts the other.
  const buildButton = createIconButton({
    icon: "build",
    tooltip: copy.tooltip.build,
    onClick: () => onBuild?.(),
  });

  const runButton = createIconButton({
    icon: "run",
    tooltip: copy.tooltip.run,
    onClick: () => onRun?.(),
  });

  // The actions are their own zone rather than loose siblings of the label, so
  // the stylesheet can split them from the name with a rule and a wider gap.
  const actions = document.createElement("div");
  actions.className = "run-widget-actions";
  actions.append(buildButton, runButton);

  widget.append(createIcon({name: "kotlin"}), widgetName, actions);

  const leftGroup = document.createElement("div");
  leftGroup.className = "menubar-group menubar-left";
  leftGroup.append(brand, viewsGroup);

  const theme = createTheme();
  const themeIcons = {light: "theme-light", dark: "theme-dark"};

  const themeButton = createIconButton({
    icon: themeIcons[theme.preference()],
    tooltip: copy.theme.tooltip,
    onClick: () => theme.cycle(),
  });

  // The split toggles sit beside the theme toggle: one opens a second view to the
  // right, the other stacks it below. They share the one split the editor keeps,
  // so the pressed one is always the direction in force. The minimap toggle
  // leads them: it folds the overview strip away and grows it back, and it is
  // pressed while the strip is in view.
  const minimapButton = createIconButton({
    icon: "minimap",
    tooltip: copy.tooltip.hideMinimap,
    onClick: () => store.setState({minimapHidden: !store.getState().minimapHidden}),
  });

  const splitButton = createIconButton({
    icon: "split",
    tooltip: copy.tooltip.split,
    onClick: () => onToggleSplit?.("right"),
  });

  const splitDownButton = createIconButton({
    icon: "split-down",
    tooltip: copy.tooltip.splitDown,
    onClick: () => onToggleSplit?.("down"),
  });

  const rightGroup = document.createElement("div");
  rightGroup.className = "menubar-group menubar-right";
  rightGroup.append(minimapButton, splitButton, splitDownButton, themeButton);

  bar.append(leftGroup, widget, rightGroup);
  canvas.appendChild(bar);

  let busyKey = null;

  // The bar folds its view entries away as the room shrinks, rather than at
  // fixed breakpoints: it measures once how much space everything else needs,
  // then keeps the longest run of entries from the left that still fits. The
  // rightmost entries drop first, so the menu reads the same at any width, and
  // each drop is a transition instead of a jump.
  let measured = false;
  let entryWidths = [];
  let entryGap = 0;
  let leftGap = 0;

  // Read with every entry still unfolded, so the numbers are their natural
  // size. The entries never shrink — the stylesheet holds them at their content
  // width: so nothing is squeezed while this runs.
  function measure() {
    bar.classList.add("is-measuring");
    entryWidths = items.map((item) => item.button.offsetWidth);
    bar.classList.remove("is-measuring");

    // Gaps are read rather than assumed, so a change to the spacing tokens needs
    // no matching change here.
    entryGap = parseFloat(getComputedStyle(viewsGroup).columnGap) || 0;
    leftGap = parseFloat(getComputedStyle(leftGroup).columnGap) || 0;
    items.forEach((item, index) => {
      item.button.style.setProperty("--menu-entry-width", `${entryWidths[index]}px`);
    });
    measured = true;
  }

  function layout() {
    // The entries' room is whatever the left track got, less the brand that leads
    // it. The track is an equal share of the space either side of the widget: it
    // does not depend on the entries' own widths: so this is stable and the fold
    // cannot oscillate.
    const room = leftGroup.clientWidth - brand.offsetWidth - leftGap;
    let used = 0;
    let folding = false;

    const folded = items.map((item, index) => {
      const cost = entryWidths[index] + (index > 0 ? entryGap : 0);
      const shouldFold = !folding && index > 0 && used + cost > room;
      if (shouldFold) {
        folding = true;
      }
      if (!folding) {
        used += cost;
      }
      return folding;
    });
    items.forEach((item, index) => item.button.classList.toggle("is-folded", folded[index]));
  }

  // The menu tracks its own selected view, so selecting Explorer moves the
  // highlight without touching the bottom tool window. The widget names the
  // skill it would run, the way the platform run widget names its configuration.
  const render = (state) => {
    canvas.classList.toggle("rail-collapsed", state.railCollapsed || state.autoRail);
    for (const item of items) {
      item.button.setAttribute("aria-pressed", item.name === state.view ? "true" : "false");
    }

    const activeName = state.docs.find((doc) => doc.id === state.activeId)?.name ?? "";
    const label = activeName ? displayName(activeName) : copy.status.noSkill;
    if (widgetName.textContent !== label) {
      widgetName.textContent = label;
    }

    const activeTask = state.busy ? state.busyTask : null;
    if (activeTask !== busyKey) {
      busyKey = activeTask;
      buildButton.classList.toggle("is-busy", activeTask === "build");
      runButton.classList.toggle("is-busy", activeTask === "run");

      const buildIcon = activeTask === "build" ? "spinner" : "build";
      const runIcon = activeTask === "run" ? "spinner" : "run";
      buildButton.replaceChildren(createIcon({name: buildIcon}));
      runButton.replaceChildren(createIcon({name: runIcon}));
    }

    const actionable = Boolean(activeName) && !state.busy && !state.reader;
    buildButton.disabled = !actionable;
    runButton.disabled = !actionable;

    // The split toggles follow the open document, not the busy state: a compile
    // does not forbid looking at the buffer twice. Each button is pressed when
    // its own direction is the one in force, and its tooltip turns into the way
    // out while pressed.
    const direction = state.splitDirection === "down" ? "down" : "right";
    const splitOn = Boolean(state.split) && Boolean(activeName);
    const splitRightOn = splitOn && direction === "right";
    const splitDownOn = splitOn && direction === "down";

    splitButton.disabled = !activeName;
    splitDownButton.disabled = !activeName;
    setToggle(splitButton, splitRightOn, splitRightOn ? copy.tooltip.unsplit : copy.tooltip.split);
    setToggle(
      splitDownButton,
      splitDownOn,
      splitDownOn ? copy.tooltip.unsplit : copy.tooltip.splitDown,
    );

    // The minimap follows the open document the way the splits do, and its
    // pressed look is the strip in view: the tooltip names the way out while
    // it shows and the way back once it is gone.
    minimapButton.disabled = !activeName;
    setToggle(
      minimapButton,
      !state.minimapHidden,
      state.minimapHidden ? copy.tooltip.showMinimap : copy.tooltip.hideMinimap,
    );

    if (!measured) {
      measure();
    }
    layout();
  };

  store.subscribe(render);
  render(store.getState());

  new ResizeObserver(layout).observe(bar);

  // The button mirrors the preference the theme reports, so a switch repaints
  // the icon and the label without any local bookkeeping.
  theme.subscribe(({preference}) => {
    const tooltip = copy.theme.tooltip;

    themeButton.dataset.tooltip = tooltip;
    themeButton.setAttribute("aria-label", tooltip);
    themeButton.replaceChildren(createIcon({name: themeIcons[preference]}));
  });
  theme.start();

  return {bar};
}

// A toggle button's pressed look and its tooltip move together, and the tooltip
// is only rewritten when it actually changes, so the pointer sitting on the
// button is not disturbed by a render that did not touch it.
function setToggle(button, pressed, tooltip) {
  button.setAttribute("aria-pressed", pressed ? "true" : "false");
  if (button.dataset.tooltip !== tooltip) {
    button.dataset.tooltip = tooltip;
    button.setAttribute("aria-label", tooltip);
  }
}

// The label's mnemonic letter carries the underline, the way a desktop menu
// marks its access key. The character stays plain text inside the span, so the
// width the fold measures does not change.
function underlineMnemonic(button, index) {
  const span = button.querySelector("span");
  const text = span.textContent;
  const mark = document.createElement("span");

  mark.className = "mnemonic";
  mark.textContent = text.slice(index, index + 1);
  span.replaceChildren(text.slice(0, index), mark, text.slice(index + 1));
}

// "HelloSkill.kt" reads as "Hello" in the widget: the file icon already says it
// is a skill file, so the tag and the extension are noise. The tag is stripped
// wherever it ends a word, so "NewSkill1" becomes "New1" and any casing counts,
// while a name like "Skillful" is left alone.
const SKILL_TAG = /skill(?=\d|\W|$)/gi;

function displayName(name) {
  return name.replace(/\.[^.]+$/, "").replace(SKILL_TAG, "");
}
