// The command layer sits between the container + the host: the host registers
// what a command is, this module decides whether a key event reaches it. The
// listener lives on the editor container, never on window, so embedding cannot
// steal a shortcut from the surrounding page.
//
// Priority is fixed in two rules: an event an edit surface already consumed
// (`defaultPrevented`) is never offered to a command, and among commands bound
// to the same keys the first enabled one in registration order wins. A matching
// command whose `enabled` says no is skipped without consuming the event, so a
// disabled key falls through to the next command or to the platform instead of
// becoming a silent no-op.
//
// Key grammar: "Mod+N", "Mod+Enter". `Mod` is Cmd on macOS + Ctrl elsewhere;
// Shift + Alt are matched exactly (absent means they must be up).

const TEXT_ENTRY = "input, textarea, [contenteditable='true'], [contenteditable='']";

function isTextEntry(target) {
  if (!(target instanceof Element)) {
    return false;
  }
  const field = target.closest(TEXT_ENTRY);
  if (!field) {
    return false;
  }
  return !field.classList.contains("source");
}

function matches(event, combination) {
  const parts = combination.split("+");
  const key = parts.pop().toLowerCase();
  let mod = false;
  let shift = false;
  let alt = false;

  for (const part of parts) {
    const name = part.toLowerCase();
    if (name === "mod") {
      mod = true;
    } else if (name === "shift") {
      shift = true;
    } else if (name === "alt") {
      alt = true;
    } else {
      return false;
    }
  }

  if (mod !== (event.metaKey || event.ctrlKey)) {
    return false;
  }
  if (shift !== event.shiftKey || alt !== event.altKey) {
    return false;
  }
  return event.key.toLowerCase() === key;
}

export function mountCommands(root, commands, {store, getActiveDoc, getContext}) {
  const list = commands.filter((command) => command && command.id);

  function contextOf(extra) {
    return {
      ...getContext(),
      state: store.getState(),
      getActiveDoc,
      ...extra,
    };
  }

  function execute(command) {
    command.run?.(contextOf({event: null, isTextEntry: false}));
  }

  root.addEventListener("keydown", (event) => {
    if (event.defaultPrevented || event.isComposing) {
      return;
    }

    const textEntry = isTextEntry(event.target);
    const state = store.getState();

    for (const command of list) {
      if (!command.keys || !command.keys.some((key) => matches(event, key))) {
        continue;
      }
      const enabled = command.enabled
        ? command.enabled({state, getActiveDoc, target: event.target, isTextEntry: textEntry, event})
        : true;
      if (!enabled) {
        continue;
      }
      event.preventDefault();
      command.run?.(contextOf({event, isTextEntry: textEntry}));
      return;
    }
  });

  return {
    run(id) {
      const command = list.find((entry) => entry.id === id);
      if (!command) {
        return false;
      }
      execute(command);
      return true;
    },
  };
}
