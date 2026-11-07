import {createIcon, fileTypeIcon} from "../ui/components/icon.js";
import {createListItem} from "../ui/components/list.js";
import {copy} from "../ui/copy.js";

function message(text) {
  return createListItem({label: text, muted: true});
}

export function mountFileTree(store, {panel, listDirectory, onPick} = {}) {
  const tree = document.createElement("div");
  tree.className = "list tree-panel-tree";
  tree.setAttribute("role", "tree");

  panel.append(tree);

  let request = 0;
  let loadedRoot = null;

  function paintChildren(container, entries) {
    if (entries.length === 0) {
      container.replaceChildren(message(copy.chat.attachEmpty));
      return;
    }
    container.replaceChildren(...entries.map((node) => renderNode(node)));
  }

  function folder(node) {
    const children = document.createElement("div");
    children.className = "tree-children";
    children.setAttribute("role", "group");
    children.hidden = true;

    const item = document.createElement("div");
    item.className = "tree-item";
    item.setAttribute("role", "treeitem");
    item.setAttribute("aria-expanded", "false");

    const row = document.createElement("div");
    row.className = "tree-row";
    row.dataset.tooltip = node.path;

    const twisty = document.createElement("button");
    twisty.type = "button";
    twisty.className = "tree-twisty";
    twisty.dataset.tooltip = copy.tooltip.expandTree;
    twisty.setAttribute("aria-label", copy.tooltip.expandTree);
    twisty.appendChild(createIcon({name: "chevron-down"}));

    const label = document.createElement("span");
    label.className = "tree-row-label";
    label.textContent = node.name;

    row.append(twisty, createIcon({name: "folder"}), label);
    item.append(row, children);

    let loaded = false;
    let loading = false;

    const load = () => {
      if (loaded || loading) {
        return;
      }
      loading = true;
      children.replaceChildren(message(copy.chat.attachLoading));
      listDirectory(node.path)
        .then((entries) => {
          loading = false;
          loaded = true;
          paintChildren(children, entries);
        })
        .catch((error) => {
          loading = false;
          children.replaceChildren(message(error?.message || copy.chat.attachEmpty));
        });
    };

    twisty.addEventListener("click", () => {
      const open = item.getAttribute("aria-expanded") !== "true";
      item.setAttribute("aria-expanded", open ? "true" : "false");
      children.hidden = !open;
      const tip = open ? copy.tooltip.collapseTree : copy.tooltip.expandTree;
      twisty.dataset.tooltip = tip;
      twisty.setAttribute("aria-label", tip);
      if (open) {
        load();
      }
    });
    return item;
  }

  function renderNode(node) {
    if (node.directory) {
      return folder(node);
    }
    const item = createListItem({
      label: node.name,
      icon: fileTypeIcon(node.path),
      onClick: () => onPick?.(node.path),
    });
    item.dataset.tooltip = node.path;
    return item;
  }

  function loadRoot(root) {
    const token = ++request;
    if (!root) {
      tree.replaceChildren(message(copy.chat.attachNoRoot));
      return;
    }
    tree.replaceChildren(message(copy.chat.attachLoading));
    listDirectory("")
      .then((entries) => {
        if (token === request) {
          paintChildren(tree, entries);
        }
      })
      .catch((error) => {
        if (token === request) {
          tree.replaceChildren(message(error?.message || copy.chat.attachEmpty));
        }
      });
  }

  const render = (state) => {
    const open = state.chatOpen === true && state.treePanelOpen === true;

    if (!open) {
      return;
    }

    const root = state.projectRoot ?? "";
    if (root !== loadedRoot) {
      loadedRoot = root;
      loadRoot(root);
    }
  };

  store.subscribe(render);
  render(store.getState());

  return {panel};
}
