import {clear} from "../core/dom.js";
import {createIconButton} from "../ui/components/button.js";
import {createListItem, createTreeRow} from "../ui/components/list.js";
import {createPanel, createPanelHeader} from "../ui/components/panel.js";
import {copy} from "../ui/copy.js";
// The rail island. It owns the panel list and reports the selection back
// through the callbacks wired in main.js. It serves two masters: the skill
// tree by default, and the assistant's session list while the chat view is
// open: same panel, same plus button, different content.
export function mountRail(store, {canvas, onOpen, onCreate, onOpenSession}) {
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

  const title = panel.querySelector(".panel-title");

  const list = document.createElement("div");
  list.className = "list";
  list.setAttribute("role", "tree");
  panel.appendChild(list);
  canvas.appendChild(panel);

  let signature = null;
  const render = (state) => {
    const chat = state.chatOpen === true;

    newButton.disabled = Boolean(state.reader);
    const newTooltip = chat ? copy.tooltip.newChat : copy.tooltip.newSkill;
    if (newButton.dataset.tooltip !== newTooltip) {
      newButton.dataset.tooltip = newTooltip;
      newButton.setAttribute("aria-label", newTooltip);
    }
    const heading = chat ? copy.rail.sessions : copy.rail.title;
    if (title.textContent !== heading) {
      title.textContent = heading;
    }

    const activeName = state.docs.find((doc) => doc.id === state.activeId)?.name ?? null;
    const next = chat
      ? JSON.stringify(["chat", state.sessions, state.activeSession, state.projectRoot, state.treeCollapsed])
      : JSON.stringify([
        "skills",
        [...new Set([...state.files, ...state.docs.map((doc) => doc.name)])],
        activeName,
        state.files,
        state.treeCollapsed,
      ]);
    if (next === signature) {
      return;
    }
    signature = next;

    clear(list);

    if (chat) {
      // The session tree mirrors the skill tree exactly: a folder root row
      // with its twisty, then one icon row per session, built from the same
      // list components. The fold state is shared with the skill tree — it is
      // the same panel, one tree at a time.
      const branch = document.createElement("div");
      branch.className = "tree-children";
      branch.setAttribute("role", "group");
      branch.hidden = state.treeCollapsed;
      for (const session of state.sessions) {
        branch.appendChild(createListItem({
          label: session.title || session.id,
          icon: "session",
          active: session.id === state.activeSession,
          onClick: () => onOpenSession?.(session.id),
        }));
      }
      list.appendChild(createTreeRow({
        label: copy.rail.sessionsRoot,
        expanded: !state.treeCollapsed,
        onToggle: () => store.setState({treeCollapsed: !store.getState().treeCollapsed}),
        children: branch,
      }));
      if (state.sessions.length === 0) {
        const hint = document.createElement("div");
        hint.className = "list-hint";
        hint.textContent = state.projectRoot
          ? "No sessions yet."
          : "Set a project root in the status bar below.";
        list.appendChild(hint);
      }
      return;
    }

    // The branch folds away whole, so the root row is what stays behind. It is
    // handed to the tree row so the group nests inside its tree item.
    const names = [...new Set([...state.files, ...state.docs.map((doc) => doc.name)])];
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
