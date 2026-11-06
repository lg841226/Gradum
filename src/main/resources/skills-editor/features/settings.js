// The settings window: a floating island over the workspace rather than a
// modal, built from the same list rows, buttons and fields as the rest of the
// editor. It owns two things that no other feature does: the four
// interface-scale ratios on the root, and the single place the theme is
// switched from (the menu bar calls theme.setMode too, so both entries read
// the same preference).
//
// The scale variables are CSS custom properties on the editor root. Their
// defaults live in tokens.css; index.html pre-paints the saved values so a
// refresh never flashes the defaults first, and this feature is the only writer
// after boot.
import {createIconButton} from "../ui/components/button.js";
import {createListItem} from "../ui/components/list.js";
import {createSegmented, setSegmentedValue} from "../ui/components/segmented.js";
import {createSelect} from "../ui/components/select.js";
import {createSwitch, setSwitchValue} from "../ui/components/switch.js";
import {copy} from "../ui/copy.js";

const SCALES = [0.9, 1, 1.1, 1.25];
const UI_FONT_SIZES = [12, 13, 14, 15, 16];
const CODE_FONT_SIZES = [12, 13, 14, 15, 16, 17, 18];

const UI_FONT_BASE = 14;
const CODE_FONT_BASE = 13;

export function mountSettings(store, {canvas, theme}) {
  const constraints = copy.settings;

  const win = document.createElement("div");
  win.className = "settings-window";
  win.setAttribute("role", "dialog");
  win.setAttribute("aria-label", constraints.title);
  win.tabIndex = -1;
  win.hidden = true;

  const side = document.createElement("div");
  side.className = "settings-side";

  const nav = document.createElement("nav");
  nav.className = "settings-nav";
  nav.setAttribute("role", "tree");
  nav.setAttribute("aria-label", constraints.title);

  const main = document.createElement("div");
  main.className = "settings-main";

  const header = document.createElement("header");
  header.className = "settings-header";

  const heading = document.createElement("h2");
  heading.className = "settings-heading";

  const collapse = createIconButton({
    icon: "hide",
    tooltip: constraints.collapse,
    onClick: () => hide(),
  });
  collapse.classList.add("settings-collapse");

  const resize = document.createElement("div");
  resize.className = "settings-resize";
  resize.setAttribute("aria-hidden", "true");

  const body = document.createElement("div");
  body.className = "settings-body";

  header.append(heading, collapse);
  main.append(header, body);

  // ---- Appearance -------------------------------------------------------

  const scaleSelect = createSelect({
    label: constraints.appearance.scale,
    options: SCALES.map((value) => ({
      value,
      label: constraints.value.percent(value),
    })),
    value: store.getState().uiScale,
    portal: canvas,
    onChange: (value) => store.setState({uiScale: Number(value)}),
  });

  const uiFontSelect = createSelect({
    label: constraints.appearance.interfaceFont,
    options: UI_FONT_SIZES.map((value) => ({
      value,
      label: constraints.value.pixels(value),
    })),
    value: store.getState().uiFontSize,
    portal: canvas,
    onChange: (value) => store.setState({uiFontSize: Number(value)}),
  });

  const codeFontSelect = createSelect({
    label: constraints.appearance.codeFont,
    options: CODE_FONT_SIZES.map((value) => ({
      value,
      label: constraints.value.pixels(value),
    })),
    value: store.getState().codeFontSize,
    portal: canvas,
    onChange: (value) => store.setState({codeFontSize: Number(value)}),
  });

  const selects = [scaleSelect, uiFontSelect, codeFontSelect];
  const closeMenus = () => {
    for (const select of selects) {
      select.close();
    }
  };

  const themeGroup = createSegmented({
    label: constraints.appearance.theme,
    options: [
      {value: "light", label: copy.theme.light},
      {value: "dark", label: copy.theme.dark},
    ],
    value: theme.preference(),
    onChange: (value) => theme.setMode(value),
  });

  const indentSwitch = createSwitch({
    label: constraints.appearance.indentGuides,
    checked: store.getState().indentGuides !== false,
    onChange: (value) => store.setState({indentGuides: value}),
  });

  const minimapSwitch = createSwitch({
    label: constraints.appearance.minimap,
    checked: !store.getState().minimapHidden,
    onChange: (value) => store.setState({minimapHidden: !value}),
  });

  const appearance = document.createElement("div");
  appearance.className = "settings-category";
  appearance.append(
    createCard(constraints.appearance.interfaceGroup, [
      createRow(constraints.appearance.scale, constraints.appearance.scaleDetail, scaleSelect.element),
      createRow(constraints.appearance.interfaceFont, constraints.appearance.interfaceFontDetail, uiFontSelect.element),
      createRow(constraints.appearance.theme, constraints.appearance.themeDetail, themeGroup),
    ]),
    createCard(constraints.appearance.editorGroup, [
      createRow(constraints.appearance.codeFont, constraints.appearance.codeFontDetail, codeFontSelect.element),
      createRow(constraints.appearance.indentGuides, constraints.appearance.indentGuidesDetail, indentSwitch),
      createRow(constraints.appearance.minimap, constraints.appearance.minimapDetail, minimapSwitch),
    ]),
  );

  // ---- Editor -----------------------------------------------------------

  const tabGroup = createSegmented({
    label: constraints.editor.tabWidth,
    options: [2, 4].map((value) => ({value, label: String(value)})),
    value: store.getState().tabWidth,
    onChange: (value) => store.setState({tabWidth: Number(value)}),
  });

  const completionSwitch = createSwitch({
    label: constraints.editor.completion,
    checked: store.getState().completionAuto !== false,
    onChange: (value) => store.setState({completionAuto: value}),
  });

  const editor = document.createElement("div");
  editor.className = "settings-category";
  editor.append(
    createCard(constraints.editor.generalGroup, [
      createRow(constraints.editor.tabWidth, constraints.editor.tabWidthDetail, tabGroup),
      createRow(constraints.editor.completion, constraints.editor.completionDetail, completionSwitch),
    ]),
  );

  // ---- About ------------------------------------------------------------

  const about = document.createElement("div");
  about.className = "settings-category";
  about.append(
    createCard(constraints.about.applicationGroup, [
      createValueRow(
        constraints.about.version,
        constraints.about.versionDetail,
        constraints.about.versionValue,
      ),
      createValueRow(constraints.about.license, "", constraints.about.licenseValue),
      createValueRow(
        constraints.about.icons,
        constraints.about.iconsDetail,
        constraints.about.iconsValue,
      ),
    ]),
  );

  const categories = [
    {id: "appearance", label: constraints.categories.appearance, icon: "theme-light", panel: appearance},
    {id: "editor", label: constraints.categories.editor, icon: "edit", panel: editor},
    {id: "about", label: constraints.categories.about, icon: "info-outline", panel: about},
  ];

  const navItems = new Map();
  for (const group of [
    {label: constraints.groups.settings, ids: ["appearance", "editor"]},
    {label: constraints.groups.system, ids: ["about"]},
  ]) {
    const groupLabel = document.createElement("div");
    groupLabel.className = "settings-nav-group";
    groupLabel.textContent = group.label;
    nav.appendChild(groupLabel);
    for (const id of group.ids) {
      const category = categories.find((entry) => entry.id === id);
      const item = createListItem({
        label: category.label,
        icon: category.icon,
        onClick: () => selectCategory(id),
      });
      navItems.set(id, item);
      nav.appendChild(item);
    }
  }

  const version = document.createElement("div");
  version.className = "settings-version";
  version.textContent = `${constraints.about.version} ${constraints.about.versionValue}`;

  const navToggle = createIconButton({
    icon: "chevron-right",
    tooltip: constraints.collapseNav,
    onClick: () => store.setState({
      settingsNavCollapsed: store.getState().settingsNavCollapsed !== true,
    }),
  });
  navToggle.classList.add("settings-nav-toggle");

  const foot = document.createElement("div");
  foot.className = "settings-side-foot";
  foot.append(version, navToggle);

  for (const category of categories) {
    category.panel.hidden = true;
    body.appendChild(category.panel);
  }

  side.append(nav, foot);
  win.append(side, main, resize);
  canvas.appendChild(win);

  let active = "appearance";

  function selectCategory(id) {
    active = id;
    heading.textContent = constraints.categories[id];
    for (const category of categories) {
      category.panel.hidden = category.id !== id;
    }
    for (const [name, item] of navItems) {
      item.setAttribute("aria-selected", name === id ? "true" : "false");
    }
  }

  // ---- scale -----------------------------------------------------------

  let appliedScale = "";

  const applyScale = (state) => {
    const key = [
      state.uiScale ?? 1,
      state.uiFontSize ?? UI_FONT_BASE,
      state.codeFontSize ?? CODE_FONT_BASE,
      state.tabWidth ?? 4,
    ].join(":");
    if (key === appliedScale) {
      return;
    }
    const booting = appliedScale === "";
    appliedScale = key;

    canvas.style.setProperty("--ui-scale", String(state.uiScale ?? 1));
    canvas.style.setProperty(
      "--ui-font-ratio",
      String((state.uiFontSize ?? UI_FONT_BASE) / UI_FONT_BASE),
    );
    canvas.style.setProperty(
      "--code-font-ratio",
      String((state.codeFontSize ?? CODE_FONT_BASE) / CODE_FONT_BASE),
    );
    canvas.style.setProperty("--tab-width", String(state.tabWidth ?? 4));

    if (!booting) {
      // The panes cache their line metrics + the menu bar caches its entry
      // widths. Both recompute on the resize the editor already listens for.
      window.dispatchEvent(new Event("resize"));
    }
  };

  // The nav folds to icons only. The category names then move into the
  // delegated tooltip: the label text is gone, and the native title would wait
  // a second and ignore the theme.
  let navCollapsed = null;

  const applyNavCollapsed = (state) => {
    const collapsed = state.settingsNavCollapsed === true;
    if (collapsed === navCollapsed) {
      return;
    }
    navCollapsed = collapsed;
    win.classList.toggle("is-nav-collapsed", collapsed);

    const toggleTip = collapsed ? constraints.expandNav : constraints.collapseNav;
    navToggle.dataset.tooltip = toggleTip;
    navToggle.setAttribute("aria-label", toggleTip);

    for (const category of categories) {
      const item = navItems.get(category.id);
      if (collapsed) {
        item.dataset.tooltip = category.label;
        item.removeAttribute("title");
      } else {
        delete item.dataset.tooltip;
        item.title = category.label;
      }
    }
  };

  const refreshSegmented = () => {
    setSegmentedValue(tabGroup, store.getState().tabWidth ?? 4);
    setSegmentedValue(themeGroup, theme.preference());
  };

  const sync = (state) => {
    applyScale(state);
    applyNavCollapsed(state);

    scaleSelect.setValue(state.uiScale ?? 1);
    uiFontSelect.setValue(state.uiFontSize ?? UI_FONT_BASE);
    codeFontSelect.setValue(state.codeFontSize ?? CODE_FONT_BASE);
    refreshSegmented();
    setSwitchValue(indentSwitch, state.indentGuides !== false);
    setSwitchValue(minimapSwitch, !state.minimapHidden);
    setSwitchValue(completionSwitch, state.completionAuto !== false);
  };

  const syncTheme = ({preference}) => setSegmentedValue(themeGroup, preference);

  store.subscribe(sync);
  sync(store.getState());
  theme.subscribe(syncTheme);
  syncTheme({preference: theme.preference()});

  let dragging = false;
  let resizing = false;
  let endDrag = null;
  let endResize = null;

  const clamp = (value, min, max) => Math.min(Math.max(value, min), max);

  const storedNumber = (value) =>
    typeof value === "number" && Number.isFinite(value) ? value : null;

  const storedPosition = (state) => {
    const x = storedNumber(state.settingsX);
    const y = storedNumber(state.settingsY);
    return x === null || y === null ? null : {x, y};
  };

  const storedSize = (state) => {
    const width = storedNumber(state.settingsW);
    const height = storedNumber(state.settingsH);
    return width === null || height === null ? null : {width, height};
  };

  // The island lives inside the canvas' content box: clamping to it keeps the
  // island gap on every edge and never lets it overhang the workspace.
  const canvasBox = () => {
    const bounds = canvas.getBoundingClientRect();
    const style = getComputedStyle(canvas);
    return {
      left: bounds.left + (parseFloat(style.paddingLeft) || 0),
      top: bounds.top + (parseFloat(style.paddingTop) || 0),
      right: bounds.right - (parseFloat(style.paddingRight) || 0),
      bottom: bounds.bottom - (parseFloat(style.paddingBottom) || 0),
    };
  };

  // Both ends come from the CSS tokens (calc already resolves to px), so min
  // and max follow --ui-scale without a second table in JS. The canvas still
  // wins when the workspace itself is smaller than the token allows.
  const sizeLimits = (box) => {
    const style = getComputedStyle(win);
    return {
      minWidth: Math.min(parseFloat(style.minWidth) || 0, box.right - box.left),
      minHeight: Math.min(parseFloat(style.minHeight) || 0, box.bottom - box.top),
      maxWidth: Math.min(parseFloat(style.maxWidth) || Infinity, box.right - box.left),
      maxHeight: Math.min(parseFloat(style.maxHeight) || Infinity, box.bottom - box.top),
    };
  };

  const applySize = (width, height, box = canvasBox()) => {
    const limits = sizeLimits(box);
    const nextWidth = clamp(width, limits.minWidth, limits.maxWidth);
    const nextHeight = clamp(height, limits.minHeight, limits.maxHeight);
    win.style.width = `${nextWidth}px`;
    win.style.height = `${nextHeight}px`;
    return {width: nextWidth, height: nextHeight};
  };

  // A window larger than the canvas pins to its top-left, which still leaves
  // the title row reachable for dragging; otherwise it stays fully inside.
  const placeAt = (x, y) => {
    const box = canvasBox();
    const maxX = Math.max(box.left, box.right - win.offsetWidth);
    const maxY = Math.max(box.top, box.bottom - win.offsetHeight);
    const left = clamp(x, box.left, maxX);
    const top = clamp(y, box.top, maxY);
    win.style.left = `${left}px`;
    win.style.top = `${top}px`;
    return {left, top};
  };

  const centre = (box = canvasBox()) => ({
    x: box.left + (box.right - box.left - win.offsetWidth) / 2,
    y: box.top + (box.bottom - box.top - win.offsetHeight) / 2,
  });

  header.addEventListener("pointerdown", (event) => {
    if (dragging || resizing || (event.pointerType === "mouse" && event.button !== 0)) {
      return;
    }
    closeMenus();
    if (event.target.closest("button, input, select, textarea, a")) {
      return;
    }
    event.preventDefault();

    const box = canvasBox();
    const maxX = Math.max(box.left, box.right - win.offsetWidth);
    const maxY = Math.max(box.top, box.bottom - win.offsetHeight);
    const startLeft = parseFloat(win.style.left) || 0;
    const startTop = parseFloat(win.style.top) || 0;
    const originX = event.clientX;
    const originY = event.clientY;
    let left = startLeft;
    let top = startTop;
    let moved = false;

    dragging = true;
    win.classList.add("is-dragging");
    document.body.classList.add("is-dragging");
    document.body.style.cursor = "grabbing";
    header.setPointerCapture(event.pointerId);

    const move = (step) => {
      left = clamp(startLeft + step.clientX - originX, box.left, maxX);
      top = clamp(startTop + step.clientY - originY, box.top, maxY);
      win.style.left = `${left}px`;
      win.style.top = `${top}px`;
      moved = true;
    };

    const finish = () => {
      header.removeEventListener("pointermove", move);
      header.removeEventListener("pointerup", finish);
      header.removeEventListener("pointercancel", finish);
      win.classList.remove("is-dragging");
      document.body.classList.remove("is-dragging");
      document.body.style.cursor = "";
      endDrag = null;
      dragging = false;
      if (moved) {
        store.setState({settingsX: left, settingsY: top});
      }
    };
    endDrag = finish;

    header.addEventListener("pointermove", move);
    header.addEventListener("pointerup", finish);
    header.addEventListener("pointercancel", finish);
  });

  // Resizing only moves the right + bottom edges: left/top stay put, so the
  // room still available is measured from the island's own corner.
  resize.addEventListener("pointerdown", (event) => {
    if (resizing || dragging || (event.pointerType === "mouse" && event.button !== 0)) {
      return;
    }
    closeMenus();
    event.preventDefault();

    const box = canvasBox();
    const startLeft = parseFloat(win.style.left) || 0;
    const startTop = parseFloat(win.style.top) || 0;
    const startWidth = win.offsetWidth;
    const startHeight = win.offsetHeight;
    const limits = sizeLimits(box);
    const maxWidth = Math.max(1, Math.min(limits.maxWidth, box.right - startLeft));
    const maxHeight = Math.max(1, Math.min(limits.maxHeight, box.bottom - startTop));
    const minWidth = Math.min(limits.minWidth, maxWidth);
    const minHeight = Math.min(limits.minHeight, maxHeight);
    const originX = event.clientX;
    const originY = event.clientY;
    let width = startWidth;
    let height = startHeight;
    let moved = false;

    resizing = true;
    win.classList.add("is-resizing");
    document.body.classList.add("is-dragging");
    document.body.style.cursor = "nwse-resize";
    resize.setPointerCapture(event.pointerId);

    const move = (step) => {
      width = clamp(startWidth + step.clientX - originX, minWidth, maxWidth);
      height = clamp(startHeight + step.clientY - originY, minHeight, maxHeight);
      win.style.width = `${width}px`;
      win.style.height = `${height}px`;
      moved = true;
    };

    const finish = () => {
      resize.removeEventListener("pointermove", move);
      resize.removeEventListener("pointerup", finish);
      resize.removeEventListener("pointercancel", finish);
      win.classList.remove("is-resizing");
      document.body.classList.remove("is-dragging");
      document.body.style.cursor = "";
      endResize = null;
      resizing = false;
      if (moved) {
        store.setState({settingsW: width, settingsH: height});
      }
    };
    endResize = finish;

    resize.addEventListener("pointermove", move);
    resize.addEventListener("pointerup", finish);
    resize.addEventListener("pointercancel", finish);
  });

  // ---- collapse / restore ----------------------------------------------

  const hide = () => {
    endDrag?.();
    endResize?.();
    closeMenus();
    win.hidden = true;
  };

  const show = () => {
    win.hidden = false;
    selectCategory(active);

    const state = store.getState();
    const box = canvasBox();

    const size = storedSize(state);
    if (size) {
      applySize(size.width, size.height, box);
    } else {
      win.style.width = "";
      win.style.height = "";
    }

    const position = storedPosition(state);
    const target = position ?? centre(box);
    const placed = placeAt(target.x, target.y);

    const patch = {};
    const settled = {width: win.offsetWidth, height: win.offsetHeight};
    if (size && (settled.width !== size.width || settled.height !== size.height)) {
      patch.settingsW = settled.width;
      patch.settingsH = settled.height;
    }
    if (position && (placed.left !== position.x || placed.top !== position.y)) {
      patch.settingsX = placed.left;
      patch.settingsY = placed.top;
    }
    if (Object.keys(patch).length > 0) {
      store.setState(patch);
    }
    refreshSegmented();
    win.focus();
  };

  const toggle = () => {
    if (win.hidden) {
      show();
    } else {
      hide();
    }
  };

  // The island's own size follows --ui-scale and the room follows the
  // viewport; re-apply both limits so a scaled or shrunken window stays inside
  // the canvas. No setState here: this runs synchronously inside the store
  // notification that applyScale triggers.
  window.addEventListener("resize", () => {
    if (win.hidden || dragging || resizing) {
      return;
    }
    const state = store.getState();
    const box = canvasBox();
    const size = storedSize(state);
    if (size) {
      applySize(size.width, size.height, box);
    }
    const position = storedPosition(state);
    const target = position ?? centre(box);
    placeAt(target.x, target.y);
    refreshSegmented();
  });

  document.addEventListener("keydown", (event) => {
    if (win.hidden || event.key !== "Escape") {
      return;
    }
    event.preventDefault();
    hide();
  });

  selectCategory(active);

  return {
    show,
    hide,
    toggle,
    isOpen: () => !win.hidden,
    window: win,
  };
}

function createCard(title, rows) {
  const card = document.createElement("section");
  card.className = "settings-card";

  const heading = document.createElement("h3");
  heading.className = "settings-card-title";
  heading.textContent = title;

  const list = document.createElement("div");
  list.className = "settings-rows";
  list.append(...rows);

  card.append(heading, list);
  return card;
}

function createRow(title, detail, control) {
  const row = document.createElement("div");
  row.className = "settings-row";

  const text = document.createElement("div");
  text.className = "settings-row-text";

  const label = document.createElement("span");
  label.className = "settings-row-label";
  label.textContent = title;
  text.appendChild(label);

  if (detail) {
    const note = document.createElement("span");
    note.className = "settings-row-detail";
    note.textContent = detail;
    text.appendChild(note);
  }

  const controls = document.createElement("div");
  controls.className = "settings-row-control";
  controls.append(...(Array.isArray(control) ? control : [control]));

  row.append(text, controls);
  return row;
}

function createValueRow(title, detail, value) {
  const text = document.createElement("div");
  text.className = "settings-value";
  text.textContent = value;
  return createRow(title, detail, text);
}
