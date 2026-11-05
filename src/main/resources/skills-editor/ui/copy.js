// Single source of truth for every user facing string. Descriptive copy is
// written as complete sentences; control labels stay short verb phrases.
//
// Style: lines stay under 100 characters. Long sentences break at clause
// boundaries inside a parenthesized and chain, one clause per line. Branching
// copy uses if/return instead of a wide ternary, so each variant reads as its
// own block. The fragments must reassemble to the exact same output string.

function plural(count, noun) {
  return count === 1 ? noun : `${noun}s`;
}

function formatBytes(bytes) {
  if (bytes < 1024) {
    return `${bytes} ${plural(bytes, "byte")}`;
  }
  return `${(bytes / 1024).toFixed(1)} KB`;
}

export const copy = {
  brand: {
    name: "Gradum Workspace",
  },

  control: {
    all: "All",
    agent: "Agent",
    editor: "Editor",
    output: "Console",
    session: "Session",
    problems: "Problems",
    explorer: "Explorer",
    developer: "Develop",
  },

  agent: {
    untitled: "New session",
  },

  theme: {
    dark: "Dark",
    light: "Light",
    tooltip: "Change theme",
  },

  chat: {
    stop: "Stop",
    send: "Send",
    model: "Model",
    thinking: "Thinking",
    thoughtNoTime: "Thought",
    working: "Working Process",
    permissions: "Permissions",
    noModels: "No models available",
    runtimeDefault: "Runtime Default",
    askTitle: "Gradum needs your input",
    answerPlaceholder: "Type your answer",
    answered: (label) => `Answered: ${label}`,
    placeholder: "Ask a question, or hand off a task for this project",
    answerFailed: (message) => `Could not answer: ${message}`,
    thoughtFor: (seconds) => `Thought for ${seconds.toFixed(1)}s`,
    rootMissing:
      "Set the project root in the status bar first: the absolute path of the " +
      "directory Gradum should work in. Sessions and file tools are rooted there.",
    sendFailed: (message) => `Send failed: ${message}`,
    loadFailed: (message) => `Could not load session: ${message}`,
    choices: {
      reject: "Reject",
      allow_once: "Allow once",
      allow_always: "Allow always",
    },
    modes: {
      edit: {label: "Edit Permissions", info: "Edit files and code by hand"},
      agent: {label: "Full Agent Mode", info: "Reads, writes, executes complex tasks"},
      read_only: {label: "Read Permissions", info: "Read, explore and run diagnostics"},
      yolo: {label: "Full Access", info: "Skips all approvals, runs edits and commands directly"},
    },
  },

  tooltip: {
    hide: "Hide",
    closeTab: "Close",
    newChat: "New chat",
    expandTree: "Expand",
    run: "Run and install",
    newSkill: "Create skill",
    unsplit: "Unsplit editor",
    split: "Split editor right",
    hideMinimap: "Hide minimap",
    showMinimap: "Show minimap",
    splitDown: "Split editor down",
    build: "Build without installing",
    collapseTree: "Collapse this folder",
  },

  status: {
    noSkill: "No opened skill",
    invalidName: "Invalid name",
    unreachable: "Server not responding",
    unauthorized: "Server rejected the request: the editor token is missing or has expired.",
    loadFailed: (name, detail) => `Could not open ${name}: ${detail}`,
    nameTaken: (name) => `${name} already exists`,
  },

  rail: {
    root: "skills",
    sessions: "Sessions",
    title: "Skill Explorer",
    sessionsRoot: "sessions",
    unknownTime: "Unknown Time",
    sessionDay: (date) => {
      const month = String(date.getMonth() + 1).padStart(2, "0");
      const day = String(date.getDate()).padStart(2, "0");
      return `${date.getFullYear()}-${month}-${day}`;
    },
  },

  bottom: {
    title: "Diagnostics",
  },

  editor: {
    emptyTitle: "No skill is open",
    emptyHint: "Choose a skill in Skill Explorer, or create a new one with the Add skill button.",
  },

  completion: {
    label: "Code completion",
  },

  statusBar: {
    lineEnding: "LF",
    encoding: "UTF-8",
    label: "Status bar",
    encodingTip: "Text stored as UTF-8",
    projectRoot: "Project working directory",
    lineEndingTip: "Lines end with LF (\\n)",
    lockedTip: "Make the skill file editable",
    unlockedTip: "Make the skill file read only and lock it",
    position: (line, column) => `${line}:${column}`,
    indent: (count) => `${count} ${plural(count, "space")}`,
    errors: (count) => `${count} ${plural(count, "error")}`,
    warnings: (count) => `${count} ${plural(count, "warning")}`,
    cursors: (count) => `${count} ${plural(count, "text cursor")}`,
    positionLabel: (line, column) => `Line ${line}, column ${column}`,
    indentLabel: (count) => `Indent ${count} ${plural(count, "space")}`,
    positionTip: (line, column) => `Caret at line ${line}, column ${column}`,
    selection: (count, lineCount) =>
      `${count} ${plural(count, "char")}, ${lineCount} ${plural(lineCount, "break")}`,
  },

  unlock: {
    submit: "Unlock",
    cancel: "Cancel",
    title: "Enable Editing",
    codePlaceholder: "Pairing code",
    placeholder: "Enter server token",
    invalid: "Sorry, the token did not match, please try again",
    codeInvalid: "Sorry, the code was incorrect, please try again",
    codeHint: "Enter the pairing code shown on the Gradum console.",
    hint: "Paste server token ~/.gradum/server.token to exit reader mode.",
  },

  problems: {
    stale: "Edited since the last run.",
    noSkillHint: "Open a skill to see its problems here.",
    staleHint: "Build or run the skill to check the edited text.",
    emptyHint: "Edit the buffer, then build or run the skill, to check a new version.",
    empty: (name) => `The skill ${name} has no problems`,
  },

  log: {
    ready: () =>
      (
        `Workspace connected to Gradum server at ${location.host}. ` +
        `Editor is ready, and ~/.gradum/skills is watched for external skill changes.`
      ),

    deploying: (name, {lines, bytes}) =>
      (
        `Deploying ${name}: writing editor buffer to ~/.gradum/skills/${name} ` +
        `(${lines} ${plural(lines, "line")}, ${formatBytes(bytes)}), ` +
        `compiling with the embedded Kotlin compiler, ` +
        `then reloading external skill registry so agent can pick it up.`
      ),

    compiled: (fileName, warnings, ms) => {
      if (warnings > 0) {
        return (
          `Deploy completed in ${ms} ms: ${fileName} compiled with 0 errors and ${warnings} ` +
          `${plural(warnings, "warning")}, source was written to ~/.gradum/skills/${fileName}, ` +
          `and reloaded class is registered for agent. ` +
          `Review ${warnings} ${plural(warnings, "warning")} in Problems panel.`
        );
      }
      return (
        `Deploy completed in ${ms} ms: ${fileName} compiled with 0 errors and 0 warnings, ` +
        `source was written to ~/.gradum/skills/${fileName}, ` +
        `and freshly compiled class is loaded and registered, so agent can run it right now.`
      );
    },

    failed: (fileName, errors, warnings, ms) => {
      const errorCount = `${errors} ${plural(errors, "error")}`;
      const warningCount = warnings > 0
        ? ` and ${warnings} ${plural(warnings, "warning")}`
        : "";
      return (
        `Deploy completed in ${ms} ms, but ${fileName} failed to compile with ` +
        `${errorCount}${warningCount}. ` +
        `Source was still written to ~/.gradum/skills/${fileName}, ` +
        `while previously compiled version, if one exists, stays loaded and registered. ` +
        `Compiler reported:`
      );
    },

    problem: (item) => {
      if (item.line == null) {
        return `Problem: ${item.message}`;
      }
      const column = item.column != null ? `, column ${item.column}` : "";
      return `Line ${item.line}${column}: ${item.message}`;
    },

    moreProblems: (count) => {
      const verb = count === 1 ? "is" : "are";
      return `${count} further ${plural(count, "problem")} ${verb} listed in Problems panel.`;
    },

    fixHint: "Fix reported problems above and run skill again to recompile it.",

    building: (name, {lines, bytes}) =>
      (
        `Building ${name}: compiling the editor buffer with the embedded Kotlin compiler ` +
        `(${lines} ${plural(lines, "line")}, ${formatBytes(bytes)}). ` +
        `Nothing is written to ~/.gradum/skills/ and the loaded skill is left as it is.`
      ),

    built: (fileName, warnings, ms) => {
      if (warnings > 0) {
        return (
          `Build finished in ${ms} ms: ${fileName} compiled with 0 errors and ${warnings} ` +
          `${plural(warnings, "warning")}. Nothing was written or installed, ` +
          `so the agent still runs the previously loaded version.`
        );
      }
      return (
        `Build finished in ${ms} ms: ${fileName} compiled with 0 errors and 0 warnings. ` +
        `Nothing was written or installed.`
      );
    },

    buildFailed: (fileName, errors, warnings, ms) => {
      const errorCount = `${errors} ${plural(errors, "error")}`;
      const warningCount = warnings > 0
        ? ` and ${warnings} ${plural(warnings, "warning")}`
        : "";
      return (
        `Build finished in ${ms} ms, but ${fileName} failed to compile with ` +
        `${errorCount}${warningCount}. Nothing was written or installed. ` +
        `Compiler reported:`
      );
    },

    rejected: (status, detail) => {
      if (status === 401) {
        return (
          `Server rejected the request with HTTP 401 (Unauthorized): ` +
          `editor token is missing or has expired. ` +
          `Reopen editor URL with a fresh ?token= query parameter and try again.`
        );
      }
      return `Server rejected the request with HTTP ${status}: ${detail}.`;
    },

    malformed: (detail) =>
      (
        `Server answered, but the response could not be parsed (${detail}), ` +
        `so the compile result is unknown.`
      ),

    unreachable: () =>
      (
        `Gradum server at ${location.host} did not respond. ` +
        `Check that it is still running and reload workspace.`
      ),
  },
};
