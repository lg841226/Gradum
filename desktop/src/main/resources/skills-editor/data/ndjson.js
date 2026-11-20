// NDJSON framing, independent of fetch and the chat renderer.
//
// A stream ends at EOF, not at a newline, so the parser owns a `flush()`: the
// last frame is delivered even when the server wrote no trailing line break.
// JSON decoding and frame handling are separate failures: a bad line is
// reported as a parse error, while a handler failure is the caller's problem
// and is allowed to propagate rather than be labeled as undecodable.

export function createNdjsonParser({onFrame, onParseError} = {}) {
  let pending = "";

  function deliver(line) {
    const trimmed = line.trim();
    if (!trimmed) {
      return;
    }
    let frame;
    try {
      frame = JSON.parse(trimmed);
    } catch (error) {
      onParseError?.(error, trimmed);
      return;
    }
    onFrame?.(frame);
  }

  return {
    push(chunk) {
      pending += chunk;
      let newlineAt = pending.indexOf("\n");
      while (newlineAt !== -1) {
        deliver(pending.slice(0, newlineAt));
        pending = pending.slice(newlineAt + 1);
        newlineAt = pending.indexOf("\n");
      }
    },
    flush() {
      const rest = pending;
      pending = "";
      deliver(rest);
    },
  };
}
