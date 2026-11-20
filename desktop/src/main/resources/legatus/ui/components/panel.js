export function createPanel() {
  const panel = document.createElement("section");
  panel.className = "panel";
  return panel;
}

export function createPanelHeader({title, actions = []} = {}) {
  const header = document.createElement("header");
  header.className = "panel-header";

  const label = document.createElement("span");
  label.className = "panel-title";
  label.textContent = title;
  header.appendChild(label);

  if (actions.length > 0) {
    const spacer = document.createElement("span");
    const group = document.createElement("div");

    spacer.className = "spacer";
    header.appendChild(spacer);
    group.className = "panel-actions";

    for (const action of actions) {
      group.appendChild(action);
    }

    header.appendChild(group);
  }
  return header;
}
