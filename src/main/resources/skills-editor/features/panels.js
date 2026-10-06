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

  header.append(title, problemsTab, outputTab, spacer, collapseButton);

  const problemsBody = document.createElement("div");
  problemsBody.className = "panel-body";
  const outputBody = document.createElement("div");
  outputBody.className = "panel-body";

  panel.append(header, problemsBody, outputBody);
  canvas.appendChild(panel);

  function show(name) {
    // The menu highlights the same view, so a tab click keeps both in step.
    store.setState({panel: name, collapsed: false, view: name});
  }

  function toggle() {
    store.setState({collapsed: !store.getState().collapsed});
  }

  const apply = (state) => {
    // The size-driven override folds the tool window on a short window without
    // clearing the user's own setting, so growing the window brings it back.
    const hidden = state.collapsed || state.autoBottom;
    // Hiding drops the whole tool window, its splitter included; the menu's
    // Problems and Output items bring it back. --bottom-height keeps its value,
    // so reopening restores the remembered height.
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
