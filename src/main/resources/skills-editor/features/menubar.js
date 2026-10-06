import {createButton, createIconButton} from "../ui/components/button.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";
import {createTheme} from "../core/theme.js";

// The view menu: the three views as plain text in a bar across the top, the way
// a desktop window lists its menus. The menu owns the selected view; Problems
// and Output also drive the bottom tool window, while Explorer toggles the rail
// the way the platform toggles a tool window from its own bar button. The run
// widget sits in the middle and names the skill it would run.
export function mountMenubar(store, {canvas, onRun}) {
  const bar = document.createElement("nav");
  bar.className = "menubar";

  const views = [
    {
      name: "explorer",
      label: copy.control.explorer,
      select: (state) => ({view: "explorer", railCollapsed: !state.railCollapsed}),
    },
    {
      name: "problems",
      label: copy.control.problems,
      select: () => ({view: "problems", panel: "problems", collapsed: false}),
    },
    {
      name: "output",
      label: copy.control.output,
      select: () => ({view: "output", panel: "output", collapsed: false}),
    },
    // Two layout presets close the list: the editor alone, and everything back.
    {
      name: "editor",
      label: copy.control.editor,
      select: () => ({view: "editor", railCollapsed: true, collapsed: true}),
    },
    {
      name: "all",
      label: copy.control.all,
      select: () => ({view: "all", railCollapsed: false, collapsed: false}),
    },
  ];

  const viewsGroup = document.createElement("div");
  viewsGroup.className = "menubar-group";

  // The brand leads the bar, the way a desktop window titles itself before
  // listing its menus.
  const brand = document.createElement("div");
  brand.className = "menubar-brand";
  const logo = document.createElement("img");
  logo.className = "menubar-logo";
  logo.src = "/skills/editor/assets/color_logo.svg";
  logo.alt = "";
  logo.setAttribute("aria-hidden", "true");
  const brandName = document.createElement("span");
  brandName.className = "menubar-brand-name";
  brandName.textContent = copy.brand.name;
  brand.append(logo, brandName);

  const items = views.map((view) => {
    const button = createButton({
      label: view.label,
      variant: "menu",
      onClick: () => store.setState(view.select(store.getState())),
    });
    underlineMnemonic(button);
    viewsGroup.appendChild(button);
    return {name: view.name, button};
  });

  const widget = document.createElement("div");
  widget.className = "run-widget";

  const widgetName = document.createElement("span");
  widgetName.className = "run-widget-name";

  const runButton = createIconButton({
    icon: "run",
    tooltip: copy.tooltip.run,
    onClick: () => onRun?.(),
  });

  widget.append(createIcon({name: "kotlin"}), widgetName, runButton);

  const leftGroup = document.createElement("div");
  leftGroup.className = "menubar-group menubar-left";
  leftGroup.append(brand, viewsGroup);

  // The theme toggle closes the bar on the right, the way the platform parks
  // view options at the far edge of a tool window bar. It cycles Light -> Dark
  // and shows the preference it is currently on.
  const theme = createTheme();
  const themeIcons = {light: "theme-light", dark: "theme-dark"};
  const themeNames = {light: copy.theme.light, dark: copy.theme.dark};

  const themeButton = createIconButton({
    icon: themeIcons[theme.preference()],
    tooltip: copy.theme.tooltip(themeNames[theme.preference()]),
    onClick: () => theme.cycle(),
  });

  const rightGroup = document.createElement("div");
  rightGroup.className = "menubar-group menubar-right";
  rightGroup.append(themeButton);

  bar.append(leftGroup, widget, rightGroup);
  canvas.appendChild(bar);

  let busy = null;

  // The bar folds its view entries away as the room shrinks, rather than at
  // fixed breakpoints: it measures once how much space everything else needs,
  // then keeps the longest run of entries from the left that still fits. The
  // rightmost entries drop first, so the menu reads the same at any width, and
  // each drop is a transition instead of a jump.
  let measured = false;
  let entryWidths = [];
  let entryGap = 0;
  let reserved = 0;

  // Read with every entry still unfolded, so the numbers are their natural
  // size. The entries never shrink — the stylesheet holds them at their content
  // width — so nothing is squeezed while this runs.
  function measure() {
    bar.classList.add("is-measuring");
    entryWidths = items.map((item) => item.button.offsetWidth);
    bar.classList.remove("is-measuring");
    // Gaps are read rather than assumed, so a change to the spacing tokens needs
    // no matching change here.
    entryGap = parseFloat(getComputedStyle(viewsGroup).columnGap) || 0;
    const leftGap = parseFloat(getComputedStyle(leftGroup).columnGap) || 0;
    const barGap = parseFloat(getComputedStyle(bar).columnGap) || 0;
    // Everything the bar carries besides the entries: the brand, the gap after
    // it, the theme toggle, and the bar's own gap on each side of the run
    // widget. Only the widget changes size later — the skill it names follows
    // the open document — so it is the one term read again on every layout.
    reserved = brand.offsetWidth + leftGap + themeButton.offsetWidth + barGap * 2;
    items.forEach((item, index) => {
      item.button.style.setProperty("--menu-entry-width", `${entryWidths[index]}px`);
    });
    measured = true;
  }

  function layout() {
    // Read before writing: toggling a class part way through would force a
    // layout pass per entry.
    const room = bar.clientWidth - reserved - widget.offsetWidth;
    let used = 0;
    let folding = false;
    const folded = items.map((item, index) => {
      const cost = entryWidths[index] + (index > 0 ? entryGap : 0);
      // Once an entry no longer fits, the ones after it fold with it, so the
      // visible entries stay a single run from the left. The first entry is the
      // anchor and keeps its place even when the room runs out.
      if (!folding && index > 0 && used + cost > room) folding = true;
      if (!folding) used += cost;
      return folding;
    });
    items.forEach((item, index) => item.button.classList.toggle("is-folded", folded[index]));
  }

  // The menu tracks its own selected view, so selecting Explorer moves the
  // highlight without touching the bottom tool window. The widget names the
  // skill it would run, the way the platform run widget names its configuration.
  const render = (state) => {
    // The size-driven override folds the rail on a narrow window without
    // clearing the user's own setting, so widening the window brings it back.
    canvas.classList.toggle("rail-collapsed", state.railCollapsed || state.autoRail);
    for (const item of items) {
      item.button.setAttribute("aria-pressed", item.name === state.view ? "true" : "false");
    }

    const activeName = state.docs.find((doc) => doc.id === state.activeId)?.name ?? "";
    const label = activeName ? displayName(activeName) : copy.status.noSkill;
    if (widgetName.textContent !== label) widgetName.textContent = label;

    if (state.busy !== busy) {
      busy = state.busy;
      runButton.classList.toggle("is-busy", state.busy);
      runButton.replaceChildren(createIcon({name: state.busy ? "spinner" : "run"}));
    }

    // Nothing to run until a skill is open, and nothing to run while a run is
    // already in flight, so the action greys out the way the platform does.
    const runnable = Boolean(activeName) && !state.busy;
    runButton.disabled = !runnable;

    // Measured after the selection has been marked, so the semibold weight the
    // selected entry takes is the one the widths are read at.
    if (!measured) measure();
    layout();
  };

  store.subscribe(render);
  render(store.getState());

  // A window resize changes how much room the bar has but not how wide the
  // entries are, so only the fold decision is redone.
  new ResizeObserver(layout).observe(bar);

  // The button mirrors the preference the theme reports, so a switch repaints
  // the icon and the label without any local bookkeeping.
  theme.subscribe(({preference}) => {
    const tooltip = copy.theme.tooltip(themeNames[preference]);
    themeButton.replaceChildren(createIcon({name: themeIcons[preference]}));
    themeButton.title = tooltip;
    themeButton.setAttribute("aria-label", tooltip);
  });
  theme.start();

  return {bar};
}

// The label's first letter carries the underline, the way a desktop menu marks
// its access key. The character stays plain text inside the span, so the width
// the fold measures does not change.
function underlineMnemonic(button) {
  const span = button.querySelector("span");
  const text = span.textContent;
  const mark = document.createElement("span");
  mark.className = "mnemonic";
  mark.textContent = text.slice(0, 1);
  span.replaceChildren(mark, text.slice(1));
}

// "HelloSkill.kt" reads as "Hello" in the widget: the file icon already says it
// is a skill file, so the tag and the extension are noise. The tag is stripped
// wherever it ends a word, so "NewSkill1" becomes "New1" and any casing counts,
// while a name like "Skillful" is left alone.
const SKILL_TAG = /skill(?=\d|\W|$)/gi;

function displayName(name) {
  return name.replace(/\.[^.]+$/, "").replace(SKILL_TAG, "");
}
