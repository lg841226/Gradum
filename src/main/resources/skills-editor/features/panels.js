import {createIconButton} from "../ui/components/button.js";
import {createPanel} from "../ui/components/panel.js";
import {createTab} from "../ui/components/tab.js";
import {copy} from "../ui/copy.js";

export function mountPanels(store, {canvas}) {
  const panel = createPanel();
  panel.classList.add("bottom");

  const header = document.createElement("header");
  header.className = "panel-header";

  const problemsTab = createTab({
    label: copy.control.problems,
    variant: "panel",
    active: true,
    onActivate: () => show("problems"),
  });
  const outputTab = createTab({
    label: copy.control.output,
    variant: "panel",
    onActivate: () => show("output"),
  });

  const spacer = document.createElement("span");
  spacer.className = "spacer";

  const collapseButton = createIconButton({
    icon: "hide",
    tooltip: copy.tooltip.hide,
    onClick: () => toggle(),
  });

  const title = document.createElement("span");
  title.className = "panel-title";
  title.textContent = copy.bottom.title;

  const actions = document.createElement("div");
  actions.className = "panel-actions";
  actions.append(collapseButton);

  header.append(title, problemsTab, outputTab, spacer, actions);

  const outputBody = document.createElement("div");
  const problemsBody = document.createElement("div");
  outputBody.className = "panel-body";
  problemsBody.className = "panel-body";

  panel.append(header, problemsBody, outputBody);
  canvas.appendChild(panel);

  function show(name) {
    store.setState({panel: name, collapsed: false, view: name});
  }

  function toggle() {
    store.setState({collapsed: !store.getState().collapsed});
  }

  const apply = (state) => {
    const hidden = state.collapsed || state.autoBottom;

    canvas.classList.toggle("bottom-hidden", hidden);
    canvas.style.setProperty("--bottom-height", `${state.bottomHeight}px`);

    const showingProblems = !hidden && state.panel === "problems";
    const showingOutput = !hidden && state.panel === "output";

    problemsTab.setAttribute("aria-selected", showingProblems ? "true" : "false");
    outputTab.setAttribute("aria-selected", showingOutput ? "true" : "false");

    problemsBody.hidden = !showingProblems;
    outputBody.hidden = !showingOutput;
  };

  store.subscribe(apply);
  apply(store.getState());

  return {panel, problemsBody, outputBody, show};
}
