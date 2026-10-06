// Minimal observable store. State flows one way: features mutate the store and
// render from the state they read back, never by reaching into each other.

export function createStore(initialState) {
  let state = initialState;
  const listeners = new Set();

  const emit = () => {
    for (const listener of listeners) {
      listener(state);
    }
  };

  return {
    getState() {
      return state;
    },
    setState(patch) {
      state = {...state, ...patch};
      emit();
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

export function activeDoc(state) {
  return state.docs.find((doc) => doc.id === state.activeId) || null;
}

export function findDoc(state, id) {
  return state.docs.find((doc) => doc.id === id) || null;
}

export function findDocByName(state, name) {
  return state.docs.find((doc) => doc.name === name) || null;
}

// Returns the patch that replaces a single document, leaving the rest of the
// state untouched.
export function withDoc(state, id, patch) {
  return {
    docs: state.docs.map((doc) => (doc.id === id ? {...doc, ...patch} : doc)),
  };
}
