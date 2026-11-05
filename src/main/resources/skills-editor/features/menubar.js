import {createButton, createIconButton} from "../ui/components/button.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";
import {isFloatLayout} from "./floatPanels.js";

// The view menu: a two-position mode switch leads the bar. Developer is the
// editor workspace, Agent hands the cell to the assistant overlay, and the
// view entries sit to its right, the same menu as before. The run widget sits
// in the middle and names the skill it would run. The theme control is handed
// in by the assembly entry, which owns the editor root it paints.
export function mountMenubar(store, {canvas, onRun, onBuild, onToggleSplit, theme}) {
  const bar = document.createElement("nav");
  bar.className = "menubar";

  const modes = [
    {
      name: "developer",
      label: copy.control.developer,
      select: () => ({chatOpen: false}),
    },
    {
      name: "agent",
      label: copy.control.agent,
      select: () => ({chatOpen: true}),
    },
  ];

  // Each view names the modes it belongs to, so the menu is a per-mode list
  // rather than one shared row: the entries only appear while that mode holds
  // the switch. The agent mode gains its own entries as its features grow.
  const views = [
    {
      name: "explorer",
      label: copy.control.explorer,
      mnemonic: 0,
      modes: ["developer"],
      select: (state) => ({view: "explorer", railCollapsed: !state.railCollapsed}),
    },
    {
      name: "problems",
      label: copy.control.problems,
      mnemonic: 0,
      modes: ["developer"],
      select: () => ({view: "problems", panel: "problems", collapsed: false}),
    },
    {
      name: "output",
      label: copy.control.output,
      mnemonic: 0,
      modes: ["developer"],
      select: () => ({view: "output", panel: "output", collapsed: false}),
    },
    // Two layout presets close the list: the editor alone + everything back.
    // Editor skips its first letter: the E already belongs to Explorer, so its
    // access key falls to the next letter, the way a desktop menu resolves a
    // duplicate.
    {
      name: "editor",
      label: copy.control.editor,
      mnemonic: 1,
      modes: ["developer"],
      select: () => ({view: "editor", railCollapsed: true, collapsed: true}),
    },
    {
      name: "all",
      label: copy.control.all,
      mnemonic: 0,
      modes: ["developer"],
      select: () => ({view: "all", railCollapsed: false, collapsed: false}),
    },
    {
      name: "session",
      label: copy.control.session,
      mnemonic: 0,
      modes: ["agent"],
      select: (state) => ({view: "session", railCollapsed: !state.railCollapsed}),
    },
  ];

  const modesGroup = document.createElement("div");
  modesGroup.className = "menubar-group menubar-modes";

  const modeThumb = document.createElement("span");
  modeThumb.className = "menubar-mode-thumb";
  modeThumb.setAttribute("aria-hidden", "true");
  modesGroup.appendChild(modeThumb);

  const viewsGroup = document.createElement("div");
  viewsGroup.className = "menubar-group menubar-views";

  const modeItems = modes.map((mode) => {
    const button = createButton({
      label: mode.label,
      variant: "menu",
      onClick: () => {
        store.setState(mode.select(store.getState()));
        closeNav();
      },
    });
    modesGroup.appendChild(button);
    return {name: mode.name, button};
  });

  let modeShown = null;
  let thumbReady = false;

  // Read the target button's live box instead of assuming two equal segments:
  // "Develop" and "Agent" measure differently, and a tight window can shrink
  // either one. Only the button's own edges are used, so the capsule padding,
  // gap and radius stay exactly what the buttons already drew; the highlight is
  // pinned to the same box, with the same radius, as the old selected fill.
  const placeModeThumb = (mode) => {
    const target = modeItems.find((item) => item.name === mode)?.button;
    if (!target) {
      return;
    }
    modeShown = mode;
    const group = modesGroup.getBoundingClientRect();
    const rect = target.getBoundingClientRect();
    modeThumb.style.width = `${rect.width}px`;
    modeThumb.style.height = `${rect.height}px`;
    modeThumb.style.transform = `translate(${rect.left - group.left}px, ${rect.top - group.top}px)`;
    // The first placement lands without the transition; every later change is
    // one slide to the newest target, so rapid clicks retarget instead of
    // queueing and never leave a second highlight behind.
    if (!thumbReady) {
      thumbReady = true;
      modeThumb.classList.add("is-ready");
    }
  };

  // A window resize can shrink the segments without a state change, so the
  // highlight follows the live box instead of staying at its last measurement.
  new ResizeObserver(() => {
    if (modeShown) {
      placeModeThumb(modeShown);
    }
  }).observe(modesGroup);

  const items = views.map((view) => {
    const button = createButton({
      label: view.label,
      variant: "menu",
      onClick: () => {
        store.setState(view.select(store.getState()));
        closeNav();
      },
    });
    underlineMnemonic(button, view.mnemonic);
    viewsGroup.appendChild(button);
    return {name: view.name, button, modes: view.modes};
  });

  // Touch devices have no hover + no room for a menu bar, so the entries
  // gather behind a hamburger: tapping it drops the views as one vertical
  // island under the bar, and a selection (or a tap anywhere else) closes it.
  const closeNav = () => bar.classList.remove("nav-open");

  const navToggle = createIconButton({
    icon: "menu",
    tooltip: "Menu",
    onClick: () => bar.classList.toggle("nav-open"),
  });
  navToggle.classList.add("menubar-nav-toggle");
  document.addEventListener("click", (event) => {
    if (bar.classList.contains("nav-open") && !bar.contains(event.target)) closeNav();
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
  // the stylesheet can split them from the name with a rule + a wider gap.
  const actions = document.createElement("div");
  actions.className = "run-widget-actions";
  actions.append(buildButton, runButton);

  widget.append(createIcon({name: "kotlin"}), widgetName, actions);

  const agentWidget = document.createElement("div");
  agentWidget.className = "run-widget agent-widget";

  const agentRepo = document.createElement("span");
  agentRepo.className = "agent-widget-repo";

  const agentSep = document.createElement("span");
  agentSep.className = "agent-widget-sep";
  agentSep.append(createIcon({name: "chevrons"}));

  const agentMark = document.createElement("span");
  agentMark.className = "agent-widget-mark";

  const agentName = document.createElement("span");
  agentName.className = "agent-widget-name";

  agentWidget.append(agentRepo, agentSep, agentMark, agentName);

  const leftGroup = document.createElement("div");
  leftGroup.className = "menubar-group menubar-left";
  leftGroup.append(viewsGroup);

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
  // pressed while the strip is in view. Afloat there is no room worth splitting
  // and no width for an overview strip, so the three stay desktop-only.
  const minimapButton = createIconButton({
    icon: "minimap",
    tooltip: copy.tooltip.hideMinimap,
    onClick: () => store.setState({minimapHidden: !store.getState().minimapHidden}),
  });
  minimapButton.hidden = isFloatLayout();

  const splitButton = createIconButton({
    icon: "split",
    tooltip: copy.tooltip.split,
    onClick: () => onToggleSplit?.("right"),
  });
  splitButton.hidden = isFloatLayout();

  const splitDownButton = createIconButton({
    icon: "split-down",
    tooltip: copy.tooltip.splitDown,
    onClick: () => onToggleSplit?.("down"),
  });
  splitDownButton.hidden = isFloatLayout();

  const rightGroup = document.createElement("div");
  rightGroup.className = "menubar-group menubar-right";
  rightGroup.append(minimapButton, splitButton, splitDownButton, themeButton, modesGroup);

  bar.append(navToggle, leftGroup, widget, agentWidget, rightGroup);
  canvas.appendChild(bar);

  let busyKey = null;
  let agentBusyShown = false;
  let markTimer = null;

  // The bar folds its view entries away as the room shrinks, rather than at
  // fixed breakpoints: it measures once how much space everything else needs,
  // then keeps the longest run of entries from the left that still fits. The
  // rightmost entries drop first, so the menu reads the same at any width, and
  // each drop is a transition instead of a jump.
  let measured = false;
  let entryWidths = [];
  let entryGap = 0;

  // Read with every entry still unfolded, so the numbers are their natural
  // size. The entries never shrink: the stylesheet holds them at their content
  // width, so nothing is squeezed while this runs.
  function measure() {
    bar.classList.add("is-measuring");
    entryWidths = items.map((item) => item.button.hidden ? 0 : item.button.offsetWidth);
    bar.classList.remove("is-measuring");

    // Gaps are read rather than assumed, so a change to the spacing tokens needs
    // no matching change here.
    entryGap = parseFloat(getComputedStyle(viewsGroup).columnGap) || 0;
    items.forEach((item, index) => {
      if (!item.button.hidden) {
        item.button.style.setProperty("--menu-entry-width", `${entryWidths[index]}px`);
      }
    });
    measured = true;
  }

  function layout() {
    // The entries' room is whatever the left track got. The track is an equal
    // share of the space either side of the widget: it does not depend on the
    // entries' own widths: so this is stable and the fold cannot oscillate.
    // Entries hidden by the mode switch cost nothing and never fold.
    const room = leftGroup.clientWidth;
    let used = 0;
    let folding = false;

    const folded = items.map((item, index) => {
      if (item.button.hidden) {
        return false;
      }
      const cost = entryWidths[index] + (index > 0 ? entryGap : 0);
      const shouldFold = !folding && index > 0 && used + cost > room;
      if (shouldFold) {
        folding = true;
      }
      if (!folding) {
        used += cost;
      }
      return shouldFold;
    });
    items.forEach((item, index) => item.button.classList.toggle("is-folded", folded[index]));
  }

  // The switch mirrors the chat overlay flag, so selecting a segment moves the
  // highlight and the assistant answers in the same render. The widget names
  // the skill it would run, the way the platform run widget names its
  // configuration.
  const render = (state) => {
    // Afloat the rail takes no editor room, so the size-driven auto step-aside
    // no longer applies and only the user's own toggle decides.
    canvas.classList.toggle(
      "rail-collapsed",
      state.railCollapsed || (!isFloatLayout() && state.autoRail));
    const activeMode = state.chatOpen === true ? "agent" : "developer";
    for (const item of modeItems) {
      item.button.setAttribute("aria-pressed", item.name === activeMode ? "true" : "false");
    }
    placeModeThumb(activeMode);
    for (const item of items) {
      item.button.hidden = !item.modes.includes(activeMode);
      item.button.setAttribute(
        "aria-pressed",
        item.name === state.view && !item.button.hidden ? "true" : "false",
      );
    }

    widget.hidden = activeMode === "agent";
    agentWidget.hidden = activeMode !== "agent";

    const repo = state.projectRoot ? basename(state.projectRoot) : "";
    if (agentRepo.textContent !== repo) {
      agentRepo.textContent = repo;
    }
    agentRepo.hidden = !repo;
    agentSep.hidden = !repo;

    const busy = state.chatBusy === true;
    if (busy !== agentBusyShown) {
      agentBusyShown = busy;
      clearTimeout(markTimer);
      if (busy) {
        agentMark.classList.remove("is-done", "is-leaving");
        agentMark.classList.add("is-busy");
        agentMark.replaceChildren(createIcon({name: "spinner"}));
      } else {
        agentMark.classList.remove("is-busy");
        agentMark.classList.add("is-done");
        agentMark.replaceChildren(createIcon({name: "check"}));
        markTimer = setTimeout(() => {
          agentMark.classList.add("is-leaving");
          markTimer = setTimeout(() => {
            // Past the fade, the classes drop and the box folds shut on its
            // own width transition.
            agentMark.classList.remove("is-done", "is-leaving");
            agentMark.replaceChildren();
          }, 180);
        }, 2400);
      }
    }

    const session = (state.sessions ?? []).find((item) => item.id === state.activeSession);
    const sessionLabel = session?.title ?? copy.agent.untitled;
    if (agentName.textContent !== sessionLabel) {
      agentName.textContent = sessionLabel;
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
  // the icon + the label without any local bookkeeping.
  theme.subscribe(({preference}) => {
    const tooltip = copy.theme.tooltip;
    themeButton.dataset.tooltip = tooltip;
    themeButton.setAttribute("aria-label", tooltip);
    themeButton.replaceChildren(createIcon({name: themeIcons[preference]}));
  });

  return {bar};
}

// A toggle button's pressed look + its tooltip move together, and the tooltip
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

// The agent widget names the repository the session runs against, so only the
// root's last segment shows: "/Users/me/Gradum" reads as "Gradum".
function basename(path) {
  return path.split("/").filter(Boolean).pop() ?? path;
}

// "HelloSkill.kt" reads as "Hello" in the widget: the file icon already says it
// is a skill file, so the tag + the extension are noise. The tag is stripped
// wherever it ends a word, so "NewSkill1" becomes "New1" and any casing counts,
// while a name like "Skillful" is left alone.
const SKILL_TAG = /skill(?=\d|\W|$)/gi;

function displayName(name) {
  return name.replace(/\.[^.]+$/, "").replace(SKILL_TAG, "");
}
