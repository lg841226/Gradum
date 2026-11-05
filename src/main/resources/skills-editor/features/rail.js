import {clear} from "../core/dom.js";
import {createIconButton} from "../ui/components/button.js";
import {createIcon} from "../ui/components/icon.js";
import {createListItem, createTreeRow} from "../ui/components/list.js";
import {createPanel, createPanelHeader} from "../ui/components/panel.js";
import {copy} from "../ui/copy.js";

const UNKNOWN_GROUP = "unknown-time";

// A group row repeats the tree row markup because the shared createTreeRow pins
// the folder glyph; a session group is a package instead. Every class name is
// one the tree styles already target, so the twisty, the ellipsis and the
// hover keep their existing look, and each nesting level keeps the indent.
function createGroupRow({label, expanded, children, onToggle}) {
  const item = document.createElement("div");
  item.className = "tree-item";
  item.setAttribute("role", "treeitem");

  const row = document.createElement("div");
  row.className = "tree-row";
  row.title = label;

  const twisty = document.createElement("button");
  twisty.type = "button";
  twisty.className = "tree-twisty";

  const name = document.createElement("span");
  name.className = "tree-row-label";
  name.textContent = label;

  const paint = (open) => {
    item.setAttribute("aria-expanded", open ? "true" : "false");
    children.hidden = !open;
    const tip = open ? copy.tooltip.collapseTree : copy.tooltip.expandTree;
    twisty.dataset.tooltip = tip;
    twisty.setAttribute("aria-label", tip);
  };

  twisty.appendChild(createIcon({name: "chevron-down"}));
  twisty.addEventListener("click", () => {
    const open = item.getAttribute("aria-expanded") !== "true";
    onToggle(open);
    paint(open);
  });

  row.append(twisty, createIcon({name: "package"}), name);
  item.appendChild(row);
  item.appendChild(children);
  paint(expanded);
  return item;
}

// The transcript index sends epoch milliseconds for both stamps. The updated
// stamp wins because the list is ordered by recent activity; a stamp that is
// missing, not a finite number, or not a positive epoch is skipped, and both
// skipped means the session has no place on the timeline.
function sessionTime(session) {
  for (const field of ["updatedAt", "createdAt"]) {
    const value = session[field];
    if (typeof value === "number" && Number.isFinite(value) && value > 0) {
      return value;
    }
  }
  return null;
}

// Groups are local calendar days. A day key is built from the local getters,
// never from UTC, so a session stays on the day the user saw it on.
function dayKey(date) {
  return `${date.getFullYear()}-${date.getMonth() + 1}-${date.getDate()}`;
}

// Sessions keep the incoming order when neither stamp is usable, because there
// is no time to sort the unknown group by; everywhere else the newest session
// leads. The unknown group sorts last: it has no place on the timeline.
function groupSessions(sessions) {
  const groups = new Map();
  for (const session of sessions) {
    const time = sessionTime(session);
    const moment = time === null ? null : new Date(time);
    const key = moment === null ? UNKNOWN_GROUP : dayKey(moment);
    let group = groups.get(key);
    if (!group) {
      const day = moment === null
        ? null
        : new Date(moment.getFullYear(), moment.getMonth(), moment.getDate());
      group = {key, day, sessions: []};
      groups.set(key, group);
    }
    group.sessions.push({session, time});
  }
  const ordered = [...groups.values()].sort((a, b) => {
    if (a.day === null) return 1;
    if (b.day === null) return -1;
    return b.day.getTime() - a.day.getTime();
  });
  for (const group of ordered) {
    group.sessions.sort((a, b) => {
      if (a.time === null || b.time === null) return 0;
      return b.time - a.time;
    });
  }
  return ordered;
}

// The rail island. It owns the panel list and reports the selection back
// through the callbacks wired in main.js. It serves two masters: the skill
// tree by default + the assistant's session list while the chat view is
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

  // The user's fold choice per day, kept here so a store update that redraws
  // the tree reopens exactly the days that were open. The newest day starts
  // open on its own, so a typical list needs no extra click.
  const groupFolds = new Map();

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
      ? JSON.stringify([
        "chat",
        state.sessions,
        state.activeSession,
        state.projectRoot,
        state.treeCollapsed,
      ])
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
      // Three levels: the root row folds the whole panel, each local day is a
      // package row, and the sessions hang off their day. Groups and sessions
      // both run newest first; the unknown group closes the list. Only the
      // newest day starts open, the older ones wait for a click, and the fold
      // the user picks survives a redraw through groupFolds.
      const groups = groupSessions(state.sessions);
      const branch = document.createElement("div");
      branch.className = "tree-children";
      branch.setAttribute("role", "group");
      branch.hidden = state.treeCollapsed;
      const newest = groups[0]?.key ?? null;
      for (const group of groups) {
        const expanded = groupFolds.has(group.key)
          ? groupFolds.get(group.key)
          : group.key === newest;
        const children = document.createElement("div");
        children.className = "tree-children";
        children.setAttribute("role", "group");
        for (const {session} of group.sessions) {
          children.appendChild(createListItem({
            label: session.title || session.id,
            icon: "session",
            active: session.id === state.activeSession,
            onClick: () => onOpenSession?.(session.id),
          }));
        }
        branch.appendChild(createGroupRow({
          label: group.day === null ? copy.rail.unknownTime : copy.rail.sessionDay(group.day),
          expanded,
          children,
          onToggle: (open) => groupFolds.set(group.key, open),
        }));
      }
      list.appendChild(createTreeRow({
        label: copy.rail.sessionsRoot,
        expanded: !state.treeCollapsed,
        onToggle: () => store.setState({treeCollapsed: !store.getState().treeCollapsed}),
        children: branch,
      }));
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
