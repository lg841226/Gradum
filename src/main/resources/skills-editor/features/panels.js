import {isFloatLayout} from "./floatPanels.js";

export function mountPanels(store, {panel}) {
  const outputBody = document.createElement("div");
  const problemsBody = document.createElement("div");
  outputBody.className = "panel-body";
  problemsBody.className = "panel-body";

  panel.append(problemsBody, outputBody);

  function show(name) {
    store.setState({panel: name, collapsed: false, view: name});
  }

  const apply = (state) => {
    const hidden = state.collapsed || (!isFloatLayout() && state.autoBottom);
    const showingProblems = !hidden && state.panel === "problems";
    const showingOutput = !hidden && state.panel === "output";

    problemsBody.hidden = !showingProblems;
    outputBody.hidden = !showingOutput;
  };

  store.subscribe(apply);
  apply(store.getState());

  return {panel, problemsBody, outputBody, show};
}
