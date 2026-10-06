import {findDoc, findDocByName, withDoc} from "../core/store.js";
import {loadSkill} from "../data/skillsApi.js";
import {createTextField} from "../ui/components/field.js";
import {renderTabStrip} from "../ui/components/tab.js";
import {copy} from "../ui/copy.js";

const NAME_PATTERN = /^[A-Za-z_][A-Za-z0-9_]*$/;

// A name is taken when it is already on disk or open in a tab. Renaming ignores
// the tab being renamed so a no-op rename stays valid.
function isNameTaken(state, name, exceptId = null) {
  if (state.files.includes(name)) {
    return true;
  }
  return state.docs.some((doc) => doc.id !== exceptId && doc.name === name);
}

// Owns the open documents: open, create, close, activate and rename. The tab
// strip container belongs to the editor island and is handed in by main.js.
export function mountTabs(store, {strip}) {
  const starter = document.getElementById("starterTemplate")?.textContent ?? "";

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

  function nextName(state) {
    let index = 1;
    while (isNameTaken(state, `NewSkill${index}.kt`)) {
      index++;
    }
    return `NewSkill${index}.kt`;
  }

  async function open(name) {
    const existing = findDocByName(store.getState(), name);
    if (existing) {
      activate(existing.id);
      return;
    }

    let remote = null;
    try {
      remote = await loadSkill(name);
    } catch (_error) {
      // The server is down: open the template and surface the reason instead of
      // dropping the open request.
      store.setState({status: {text: copy.status.unreachable, tone: "error"}});
    }

    const source = remote === null ? starter : remote;
    addDoc({
      id: store.getState().nextId,
      name,
      source,
      baseline: source,
      dirty: false,
      diagnostics: [],
    });
  }

  function create() {
    const state = store.getState();
    addDoc({
      id: state.nextId,
      name: nextName(state),
      source: starter,
      baseline: starter,
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
    const bare = String(rawName).trim().replace(/\.kt$/, "");
    if (!NAME_PATTERN.test(bare)) {
      store.setState({status: {text: copy.status.invalidName, tone: "error"}});
      return false;
    }

    const name = `${bare}.kt`;
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

    // Match the label it replaces, so the tab keeps its size while renaming
    // instead of jumping to the field's default width.
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
    ]);
    if (next === signature) {
      return;
    }
    signature = next;

    renderTabStrip(
      strip,
      state.docs.map((doc) => ({
        id: doc.id,
        label: doc.name,
        icon: "kotlin",
        variant: "file",
        active: doc.id === state.activeId,
        dirty: doc.dirty,
        tooltip: doc.name,
      })),
      {
        onActivate: (tab) => activate(Number(tab.dataset.id)),
        onClose: (tab) => close(Number(tab.dataset.id)),
        onRename: (tab) => {
          const doc = findDoc(store.getState(), Number(tab.dataset.id));
          if (doc) {
            beginRename(tab, doc);
          }
        },
      },
    );
  };

  store.subscribe(render);
  render(store.getState());

  return {open, create, close, closeActive, activate, rename, render};
}
