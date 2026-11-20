import {findDoc, findDocByName, withDoc} from "../core/store.js";
import {createTextField} from "../ui/components/field.js";
import {renderTabStrip} from "../ui/components/tab.js";
import {copy} from "../ui/copy.js";

// A name is taken when it is already on disk or open in a tab. Renaming ignores
// the tab being renamed so a no-op rename stays valid.
function isNameTaken(state, name, exceptId = null) {
  if (state.files.includes(name)) {
    return true;
  }
  return state.docs.some((doc) => doc.id !== exceptId && doc.name === name);
}

function iconFor(name) {
  return name.toLowerCase().endsWith(".kt") ? "kotlin" : null;
}

// The tab carries the file name; the root/relative path stays the document's
// identity and rides along in the tooltip.
function labelFor(name) {
  const parts = String(name).split("/").filter(Boolean);
  return parts[parts.length - 1] ?? String(name);
}

// Owns the open documents: open, create, close, activate and rename. The tab
// strip containers belong to the editor island and are handed in by the
// assembly entry, one per editor pane, all listing the same documents. Each view
// may bring its own close handler; a pane without one closes the document itself.
//
// The host owns everything the filesystem knows: how a name is opened, what a
// new file is called and what it starts from, and how a name is normalized. This
// file only tracks the tabs that result.
export function mountTabs(store, {strips, documents}) {
  function activate(id) {
    const state = store.getState();
    if (state.activeId === id) {
      store.setState({focusToken: state.focusToken + 1});
      return;
    }
    store.setState({activeId: id, focusToken: state.focusToken + 1});
  }

  function addDoc(doc) {
    const state = store.getState();
    store.setState({
      docs: [...state.docs, doc],
      activeId: doc.id,
      nextId: state.nextId + 1,
      focusToken: state.focusToken + 1,
    });
  }

  const loading = new Map();

  async function loadDoc(name) {
    let result;
    try {
      result = await documents.open(name);
    } catch (_error) {
      store.setState({status: {text: copy.status.unreachable, tone: "error"}});
      return;
    }
    if (result.error) {
      store.setState({status: {text: result.error, tone: "error"}});
      return;
    }

    const current = findDocByName(store.getState(), name);
    if (current) {
      activate(current.id);
      return;
    }

    addDoc({
      id: store.getState().nextId,
      name,
      source: result.source,
      baseline: result.source,
      dirty: false,
      diagnostics: [],
    });
  }

  async function open(name) {
    const existing = findDocByName(store.getState(), name);
    if (existing) {
      activate(existing.id);
      return;
    }

    const pending = loading.get(name);
    if (pending) {
      await pending;
      return;
    }

    const task = loadDoc(name);
    loading.set(name, task);
    try {
      await task;
    } finally {
      loading.delete(name);
    }
  }

  function create() {
    const state = store.getState();

    if (state.reader) {
      return;
    }
    if (state.offline) {
      store.setState({status: {text: copy.status.unreachable, tone: "error"}});
      return;
    }

    const fresh = documents.create({
      files: state.files,
      names: state.docs.map((doc) => doc.name),
    });
    if (!fresh) {
      return;
    }

    addDoc({
      id: state.nextId,
      name: fresh.name,
      source: fresh.source,
      baseline: fresh.source,
      dirty: false,
      diagnostics: [],
    });
  }

  function close(id) {
    const state = store.getState();
    const index = state.docs.findIndex((doc) => doc.id === id);
    if (index === -1) {
      return;
    }

    const docs = state.docs.filter((doc) => doc.id !== id);
    let activeId = state.activeId;
    if (activeId === id) {
      const next = docs[index] || docs[index - 1] || null;
      activeId = next ? next.id : null;
    }
    store.setState({docs, activeId, focusToken: state.focusToken + 1});
  }

  function closeActive() {
    const activeId = store.getState().activeId;
    if (activeId != null) {
      close(activeId);
    }
  }

  function rename(id, rawName) {
    const current = findDoc(store.getState(), id);
    const name = documents.rename(rawName, current?.name);
    if (!name) {
      store.setState({status: {text: copy.status.invalidName, tone: "error"}});
      return false;
    }

    const state = store.getState();
    if (isNameTaken(state, name, id)) {
      store.setState({status: {text: copy.status.nameTaken(name), tone: "error"}});
      return false;
    }

    store.setState({
      ...withDoc(state, id, {name}),
      status: {text: "", tone: "neutral"},
    });
    return true;
  }

  function beginRename(tab, doc) {
    const label = tab.querySelector(".tab-label");
    if (!label) {
      return;
    }

    const width = label.getBoundingClientRect().width;
    const field = createTextField({
      value: doc.name,
      onCommit: (value) => {
        field.replaceWith(label);
        rename(doc.id, value);
      },
      onCancel: () => field.replaceWith(label),
    });
    field.style.width = `${width}px`;
    label.replaceWith(field);
    field.focus();
    field.select();
  }

  let signature = null;
  const render = (state) => {
    const next = JSON.stringify([
      state.docs.map((doc) => [doc.id, doc.name, doc.dirty]),
      state.activeId,
      state.offline,
    ]);
    if (next === signature) {
      return;
    }
    signature = next;

    const tabModels = state.docs.map((doc) => ({
      id: doc.id,
      icon: iconFor(doc.name),
      variant: "file",
      label: labelFor(doc.name),
      dirty: doc.dirty,
      offline: state.offline,
      tooltip: doc.name,
      active: doc.id === state.activeId,
    }));
    const handlers = {
      onActivate: (_tab, id) => activate(Number(id)),
      onClose: (_tab, id) => close(Number(id)),
      onRename: (tab, id) => {
        const doc = findDoc(store.getState(), Number(id));
        if (doc) {
          beginRename(tab, doc);
        }
      },
    };

    for (const view of strips) {
      renderTabStrip(view.strip, tabModels, {
        onActivate: handlers.onActivate,
        onClose: view.onClose ?? handlers.onClose,
        onRename: handlers.onRename,
        closeTooltip: view.closeTooltip,
      });
    }
  };

  store.subscribe(render);
  render(store.getState());

  return {open, create, close, closeActive, render};
}
