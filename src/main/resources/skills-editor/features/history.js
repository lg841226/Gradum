// Snapshot undo for the whole editor. Every committed edit: a native
// keystroke, a multi-cursor splice, a Tab indent, a restored state — pushes
// the same kind of record, so one stack per document answers Cmd+Z from
// anywhere and the tab indent no longer wipes the platform's private history
// out from under the user. Snapshots carry the cursors with the text, so an
// undo puts the caret back where the edit left it.

// The stack outgrows nothing a session can type into a skill file; past the
// cap the oldest states fall off the bottom and the index follows them down.
const MAX_STATES = 300;

// Consecutive edits closer together than this belong to one gesture: a
// burst of typing undoes as a burst, not one letter at a time. An undo or a
// redo breaks the window, so the next keystroke starts a fresh group instead
// of being merged into the state that was just restored.
const COALESCE_MS = 900;

const stacks = new Map();

function stackFor(docId) {
  let entry = stacks.get(docId);
  if (!entry) {
    entry = {states: [], index: -1, lastAt: 0};
    stacks.set(docId, entry);
  }
  return entry;
}

// The buffer as a document opens. Reopening a document that already has a
// stack keeps it: the text left in memory and the history of how it got there
// still belong together.
function seedHistory(docId, snapshot) {
  const entry = stackFor(docId);
  if (entry.index === -1) {
    entry.states = [snapshot];
    entry.index = 0;
  }
}

// One committed edit. Within the coalescing window the current state is
// replaced: the group's first state still holds the before-picture, so one
// undo reverts the whole burst. Outside it the tail is truncated (redo dies
// with any new edit) and the new state is appended.
function recordEdit(docId, snapshot) {
  const entry = stackFor(docId);
  const now = Date.now();
  const current = entry.index >= 0 ? entry.states[entry.index] : null;
  if (current && current.text === snapshot.text) {
    return;
  }

  if (entry.index >= 0 && now - entry.lastAt <= COALESCE_MS) {
    entry.states[entry.index] = snapshot;
  } else {
    entry.states = entry.states.slice(0, entry.index + 1);
    entry.states.push(snapshot);
    entry.index += 1;
    if (entry.states.length > MAX_STATES) {
      entry.states.shift();
      entry.index -= 1;
    }
  }
  entry.lastAt = now;
}

function undo(docId) {
  const entry = stacks.get(docId);
  if (!entry || entry.index <= 0) {
    return null;
  }
  entry.index -= 1;
  entry.lastAt = 0;
  return entry.states[entry.index];
}

function redo(docId) {
  const entry = stacks.get(docId);
  if (!entry || entry.index >= entry.states.length - 1) {
    return null;
  }
  entry.index += 1;
  entry.lastAt = 0;
  return entry.states[entry.index];
}

export {recordEdit, redo, seedHistory, undo};
