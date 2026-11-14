import {createButton, createIconButton} from "../ui/components/button.js";
import {createSegmented, setSegmentedValue} from "../ui/components/segmented.js";
import {createIcon} from "../ui/components/icon.js";
import {copy} from "../ui/copy.js";
import {isFloatLayout} from "./floatPanels.js";

export function mountMenubar(store, {
  canvas,
  panelMenus = [],
  onRun,
  onBuild,
  onToggleSplit,
  onToggleSettings,
}) {
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

  const views = [
    {
      name: "editor",
      label: copy.control.editor,
      mnemonic: 1,
      modes: ["developer"],
      order: 3,
      select: () => ({view: "editor", railCollapsed: true, collapsed: true}),
    },
    {
      name: "all",
      label: copy.control.all,
      mnemonic: 0,
      modes: ["developer"],
      order: 4,
      select: () => ({view: "all", railCollapsed: false, collapsed: false}),
    },
    {
      name: "session",
      label: copy.control.session,
      mnemonic: 0,
      modes: ["agent"],
      order: 5,
      select: (state) => ({view: "session", railCollapsed: !state.railCollapsed}),
    },
    ...panelMenus,
  ].sort((left, right) => left.order - right.order);

  const modesGroup = createSegmented({
    className: "menubar-group menubar-modes",
    options: modes.map((mode) => ({value: mode.name, label: mode.label})),
    value: store.getState().chatOpen === true ? "agent" : "developer",
    onChange: (name) => {
      const mode = modes.find((entry) => entry.name === name);
      if (!mode) {
        return;
      }
      store.setState(mode.select(store.getState()));
      closeNav();
    },
  });

  const viewsGroup = document.createElement("div");
  viewsGroup.className = "menubar-group menubar-views";

  const items = views.map((view) => {
    const button = createButton({
      label: view.label,
      icon: view.icon,
      variant: "menu",
      onClick: () => {
        store.setState(view.select(store.getState()));
        closeNav();
      },
    });
    underlineMnemonic(button, view.mnemonic);
    viewsGroup.appendChild(button);
    return {name: view.name, button, modes: view.modes, pressed: view.pressed};
  });

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

  const settingsButton = createIconButton({
    icon: "settings",
    tooltip: copy.tooltip.settings,
    onClick: () => onToggleSettings?.(),
  });

  const rightGroup = document.createElement("div");
  rightGroup.className = "menubar-group menubar-right";
  rightGroup.append(
    minimapButton,
    splitButton,
    splitDownButton,
    settingsButton,
    modesGroup,
  );

  bar.append(navToggle, leftGroup, widget, agentWidget, rightGroup);
  canvas.appendChild(bar);

  let busyKey = null;
  let agentBusyShown = false;
  let markTimer = null;

  let measured = false;
  let measuredScale = "";
  let entryWidths = [];
  let entryGap = 0;

  // Read with every entry still unfolded, so the numbers are their natural
  // size. The entries never shrink: the stylesheet holds them at their content
  // width, so nothing is squeezed while this runs.
  function measure() {
    bar.classList.add("is-measuring");
    entryWidths = items.map((item) => item.button.hidden ? 0 : item.button.offsetWidth);
    bar.classList.remove("is-measuring");

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

  const render = (state) => {
    const activeMode = state.chatOpen === true ? "agent" : "developer";
    setSegmentedValue(modesGroup, activeMode);
    for (const item of items) {
      item.button.hidden = !item.modes.includes(activeMode);
      const pressed = (item.pressed ? item.pressed(state) : item.name === state.view)
        && !item.button.hidden;
      item.button.setAttribute("aria-pressed", pressed ? "true" : "false");
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

    const chatMode = state.chatOpen === true;
    const direction = (chatMode ? state.chatSplitDirection : state.splitDirection) === "down"
      ? "down"
      : "right";
    const splitOn = chatMode ? Boolean(state.chatSplit) : Boolean(state.split) && Boolean(activeName);
    const splitRightOn = splitOn && direction === "right";
    const splitDownOn = splitOn && direction === "down";

    splitButton.disabled = !chatMode && !activeName;
    splitDownButton.disabled = !chatMode && !activeName;
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

    const scaleKey = `${state.uiScale ?? 1}:${state.uiFontSize ?? 14}`;
    if (scaleKey !== measuredScale) {
      if (measured) {
        for (const item of items) {
          item.button.classList.remove("is-folded");
          item.button.style.removeProperty("--menu-entry-width");
        }
      }
      measure();
      measuredScale = scaleKey;
    }
    layout();
  };

  store.subscribe(render);
  render(store.getState());

  new ResizeObserver(layout).observe(bar);

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

// The agent widget names the repository the session runs against, so only the
// root's last segment shows: "/Users/me/Gradum" reads as "Gradum".
function basename(path) {
  return path.split("/").filter(Boolean).pop() ?? path;
}

// "HelloSkill.kt" reads as "Hello" in the widget: the file icon already says it
// is a skill file, so the tag and the extension are noise. The tag is stripped
// wherever it ends a word, so "NewSkill1" becomes "New1" and any casing counts,
// while a name like "Skillful" is left alone.
const SKILL_TAG = /skill(?=\d|\W|$)/gi;

function displayName(name) {
  return basename(name).replace(/\.[^.]+$/, "").replace(SKILL_TAG, "");
}
