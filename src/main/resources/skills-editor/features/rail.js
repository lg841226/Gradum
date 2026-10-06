import {clear} from "../core/dom.js";
import {createIconButton} from "../ui/components/button.js";
import {createListItem, createTreeRow} from "../ui/components/list.js";
import {createPanel, createPanelHeader} from "../ui/components/panel.js";
import {copy} from "../ui/copy.js";

// The Skill Explorer island. It owns the list and reports the selected skill
// back through the callback wired in main.js. Its header carries the two actions
// that belong to the explorer: create a skill, and hide the panel.
export function mountRail(store, {canvas, onOpen, onCreate}) {
  const panel = createPanel();
  panel.classList.add("rail");

  const newButton = createIconButton({
    icon: "plus",
    tooltip: copy.tooltip.newSkill,
    onClick: () => onCreate?.(),
  });
  const hideButton = createIconButton({
    icon: "hide",
    tooltip: copy.tooltip.hide,
    onClick: () => store.setState({railCollapsed: true}),
  });

  panel.appendChild(createPanelHeader({
    title: copy.rail.title,
    actions: [newButton, hideButton],
  }));

  const list = document.createElement("div");
  list.className = "list";
  list.setAttribute("role", "tree");
  panel.appendChild(list);
  canvas.appendChild(panel);

  let signature = null;
  const render = (state) => {
    const names = [...new Set([...state.files, ...state.docs.map((doc) => doc.name)])];
    const activeName = state.docs.find((doc) => doc.id === state.activeId)?.name ?? null;
    const next = JSON.stringify([names, activeName, state.files, state.treeCollapsed]);
    if (next === signature) return;
    signature = next;

    clear(list);

    // The branch folds away whole, so the root row is what stays behind. It is
    // handed to the tree row so the group nests inside its tree item.
    const branch = document.createElement("div");
    branch.className = "tree-children";
    branch.setAttribute("role", "group");
    branch.hidden = state.treeCollapsed;
    for (const name of names) {
      branch.appendChild(createListItem({
        label: name,
        icon: "kotlin",
        active: name === activeName,
        muted: !state.files.includes(name),
        onClick: () => onOpen?.(name),
      }));
    }

    list.appendChild(createTreeRow({
      label: copy.rail.root,
      expanded: !state.treeCollapsed,
      onToggle: () => store.setState({treeCollapsed: !store.getState().treeCollapsed}),
      children: branch,
    }));
  };

  store.subscribe(render);
  render(store.getState());

  return {panel};
}
