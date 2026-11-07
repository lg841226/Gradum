import {STATE_SCHEMA} from "../core/schema.js";
import {copy} from "../ui/copy.js";
import {createIconButton} from "../ui/components/button.js";
import {createIcon} from "../ui/components/icon.js";
import {createPanel, createPanelHeader} from "../ui/components/panel.js";
import {createTab} from "../ui/components/tab.js";
import {isFloatLayout, mountFloatPanels} from "./floatPanels.js";
import {mountFileTree} from "./fileTree.js";
import {mountAssistant} from "./assistant/index.js";
import {mountPanels} from "./panels.js";
import {mountRail} from "./rail.js";
import {isDragging, mountSplitters} from "./splitter.js";

const resolve = (value, context) => (typeof value === "function" ? value(context) : value);
const defaultOf = (key) => STATE_SCHEMA[key].default;

const modeOf = (state) => (state.chatOpen === true ? "agent" : "developer");

function createSize(key, cssVar, min, max, splitterClass) {
  return {key, cssVar, default: defaultOf(key), min, max, splitterClass};
}

function treeMax({canvas, element, siblings, scale, gap}) {
  const rail = siblings.rail?.element;
  if (!rail) {
    return 480;
  }
  const collapsed = canvas.classList.contains("rail-collapsed");
  const chatLeft = rail.getBoundingClientRect().right + (collapsed ? 0 : gap);
  return Math.max(
    150,
    Math.min(480, (element.getBoundingClientRect().right - chatLeft - gap) / scale - 150),
  );
}

function isOpen(descriptor, state, floating) {
  const spec = descriptor.open;
  if (spec.modes && !spec.modes.includes(modeOf(state))) {
    return false;
  }
  const fallback = spec.inverted ? !spec.defaultOpen : spec.defaultOpen;
  const raw = state[spec.key] ?? fallback;
  let open = spec.inverted ? !raw : Boolean(raw);
  if (!floating && spec.autoKey && state[spec.autoKey]) {
    open = false;
  }
  return open;
}

function applyOpenState(canvas, descriptor, element, open) {
  const spec = descriptor.open;
  if (spec.openClass) {
    canvas.classList.toggle(spec.openClass, open);
  }
  if (spec.closedClass) {
    canvas.classList.toggle(spec.closedClass, !open);
  }
  if (spec.hide) {
    element.hidden = !open;
  }
}

function applySize(canvas, descriptor, state) {
  if (isDragging()) {
    return;
  }
  const spec = descriptor.size;
  const value = state[spec.key] ?? spec.default;
  canvas.style.setProperty(spec.cssVar, `calc(${value}px * var(--ui-scale))`);
}

function createAction(spec, state) {
  const icon = resolve(spec.icon, state);
  const node = createIconButton({
    icon,
    tooltip: resolve(spec.tooltip, state),
    disabled: resolve(spec.disabled, state) === true,
    onClick: spec.onClick,
  });
  return {node, spec, icon};
}

function applyAction(handle, state) {
  const icon = resolve(handle.spec.icon, state);
  if (icon !== handle.icon) {
    handle.icon = icon;
    handle.node.replaceChildren(createIcon({name: icon}));
  }
  const tooltip = resolve(handle.spec.tooltip, state);
  if (tooltip && handle.node.dataset.tooltip !== tooltip) {
    handle.node.dataset.tooltip = tooltip;
    handle.node.setAttribute("aria-label", tooltip);
  }
  handle.node.disabled = resolve(handle.spec.disabled, state) === true;
}

function createHeader(descriptor, state) {
  const actionHandles = (descriptor.actions ?? []).map((spec) => createAction(spec, state));
  const actionNodes = actionHandles.map((handle) => handle.node);
  const header = createPanelHeader({
    title: resolve(descriptor.title, state) ?? "",
    actions: actionNodes,
  });
  const tabHandles = (descriptor.tabs ?? []).map((spec) => ({
    spec,
    node: createTab({
      label: spec.label,
      variant: "panel",
      active: resolve(spec.active, state) === true,
      onActivate: spec.onActivate,
    }),
  }));
  const spacer = header.querySelector(".spacer");
  for (const handle of tabHandles) {
    header.insertBefore(handle.node, spacer);
  }
  descriptor.element.appendChild(header);
  return {
    titleNode: header.querySelector(".panel-title"),
    actions: actionHandles,
    tabs: tabHandles,
  };
}

export function mountPanelRegistry(store, {canvas, panels}) {
  const api = {};
  const entries = [];
  const initialState = store.getState();
  const floating = isFloatLayout();

  for (const descriptor of panels) {
    const element = createPanel();
    element.classList.add(descriptor.className);
    descriptor.element = element;
    canvas.appendChild(element);

    applyOpenState(canvas, descriptor, element, isOpen(descriptor, initialState, floating));

    const chrome = descriptor.header === false ? null : createHeader(descriptor, initialState);
    api[descriptor.id] = descriptor.render({panel: element, state: initialState}) ?? {};

    if (descriptor.size) {
      applySize(canvas, descriptor, initialState);
    }
    entries.push({descriptor, chrome});
  }

  store.subscribe((state) => {
    const floating = isFloatLayout();
    for (const {descriptor, chrome} of entries) {
      applyOpenState(canvas, descriptor, descriptor.element, isOpen(descriptor, state, floating));
      if (chrome) {
        const title = resolve(descriptor.title, state) ?? "";
        if (chrome.titleNode.textContent !== title) {
          chrome.titleNode.textContent = title;
        }
        for (const handle of chrome.actions) {
          applyAction(handle, state);
        }
        for (const handle of chrome.tabs) {
          handle.node.setAttribute(
            "aria-selected",
            resolve(handle.spec.active, state) === true ? "true" : "false",
          );
        }
      }
      if (descriptor.size) {
        applySize(canvas, descriptor, state);
      }
    }
  });

  mountSplitters(store, {canvas, panels: panels.filter((descriptor) => descriptor.size)});
  mountFloatPanels(store, {
    canvas,
    panels: panels.filter((descriptor) => descriptor.floating),
  });

  return {
    panels,
    api,
    menus: panels.flatMap((descriptor) => descriptor.menus ?? []),
  };
}

export function createPanelRegistry({
  store,
  onOpen,
  onCreate,
  onOpenSession,
  listDirectory,
  onPick,
  onOpenFile,
}) {
  const rail = {
    id: "rail",
    title: (state) => (state.chatOpen === true ? copy.rail.sessions : copy.rail.title),
    side: "left",
    className: "rail",
    size: createSize("railWidth", "--rail-width", 150, 480, "splitter-rail"),
    open: {
      key: "railCollapsed",
      defaultOpen: !defaultOf("railCollapsed"),
      inverted: true,
      autoKey: "autoRail",
      closedClass: "rail-collapsed",
    },
    floating: true,
    actions: [
      {
        icon: "plus",
        tooltip: (state) => (state.chatOpen === true ? copy.tooltip.newChat : copy.tooltip.newSkill),
        disabled: (state) => state.reader === true,
        onClick: () => onCreate?.(),
      },
      {
        icon: "hide",
        tooltip: copy.tooltip.hide,
        onClick: () => store.setState({railCollapsed: true}),
      },
    ],
    menus: [
      {
        name: "explorer",
        label: copy.control.explorer,
        mnemonic: 0,
        modes: ["developer"],
        order: 0,
        select: (state) => ({view: "explorer", railCollapsed: !state.railCollapsed}),
        pressed: (state) => state.view === "explorer",
      },
    ],
    render: ({panel}) => mountRail(store, {
      panel,
      onOpen,
      onCreate,
      onOpenSession,
    }),
  };

  const files = {
    id: "files",
    title: copy.files.title,
    side: "right",
    className: "tree-panel",
    size: createSize("treeWidth", "--tree-width", 150, treeMax, "splitter-tree"),
    open: {
      key: "treePanelOpen",
      defaultOpen: defaultOf("treePanelOpen"),
      modes: ["agent"],
      openClass: "tree-open",
      closedClass: "tree-hidden",
    },
    floating: true,
    actions: [
      {
        icon: "hide",
        tooltip: copy.tooltip.hide,
        onClick: () => store.setState({treePanelOpen: false}),
      },
    ],
    menus: [
      {
        name: "files",
        label: copy.files.title,
        mnemonic: 0,
        modes: ["agent"],
        order: 6,
        select: (state) => ({treePanelOpen: !state.treePanelOpen}),
        pressed: (state) => state.treePanelOpen === true,
      },
    ],
    render: ({panel}) => mountFileTree(store, {panel, listDirectory, onPick}),
  };

  const bottom = {
    id: "bottom",
    title: copy.bottom.title,
    side: "bottom",
    className: "bottom",
    size: createSize(
      "bottomHeight",
      "--bottom-height",
      80,
      ({scale}) => (window.innerHeight * 0.7) / scale,
    ),
    open: {
      key: "collapsed",
      defaultOpen: !defaultOf("collapsed"),
      inverted: true,
      autoKey: "autoBottom",
      modes: ["developer"],
      closedClass: "bottom-hidden",
    },
    floating: true,
    actions: [
      {
        icon: "hide",
        tooltip: copy.tooltip.hide,
        onClick: () => store.setState({collapsed: !store.getState().collapsed}),
      },
    ],
    tabs: [
      {
        label: copy.control.problems,
        active: (state) => !(state.collapsed || (!isFloatLayout() && state.autoBottom))
          && state.panel === "problems",
        onActivate: () => store.setState({panel: "problems", collapsed: false, view: "problems"}),
      },
      {
        label: copy.control.output,
        active: (state) => !(state.collapsed || (!isFloatLayout() && state.autoBottom))
          && state.panel === "output",
        onActivate: () => store.setState({panel: "output", collapsed: false, view: "output"}),
      },
    ],
    menus: [
      {
        name: "problems",
        label: copy.control.problems,
        mnemonic: 0,
        modes: ["developer"],
        order: 1,
        select: () => ({view: "problems", panel: "problems", collapsed: false}),
        pressed: (state) => state.view === "problems",
      },
      {
        name: "output",
        label: copy.control.output,
        mnemonic: 0,
        modes: ["developer"],
        order: 2,
        select: () => ({view: "output", panel: "output", collapsed: false}),
        pressed: (state) => state.view === "output",
      },
    ],
    render: ({panel}) => mountPanels(store, {panel}),
  };

  const assistant = {
    id: "assistant",
    title: copy.control.agent,
    side: "center",
    className: "assistant",
    header: false,
    open: {
      key: "chatOpen",
      defaultOpen: defaultOf("chatOpen"),
      modes: ["agent"],
      openClass: "chat-open",
      hide: true,
    },
    floating: false,
    render: ({panel}) => mountAssistant(store, {panel, onOpenFile}),
  };

  return [rail, files, bottom, assistant];
}
