// Single source of truth for every user-facing string. Descriptive copy is
// written as complete sentences; control labels stay short verb phrases.

function plural(count, noun) {
  return count === 1 ? noun : `${noun}s`;
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} ${plural(bytes, "byte")}`;
  return `${(bytes / 1024).toFixed(1)} KB`;
}

export const copy = {
  brand: {
    name: "Gradum Workspace",
  },

  control: {
    all: "All",
    output: "Output",
    editor: "Editor",
    explorer: "Explorer",
    problems: "Problems",
  },

  theme: {
    dark: "Dark",
    light: "Light",
    tooltip: (name) => `Theme ${name}. Click to change.`,
  },

  tooltip: {
    hide: "Hide this panel",
    newSkill: "Create a new skill",
    expandTree: "Expand this folder",
    closeTab: "Close this skill tab",
    resize: "Drag to resize the panel",
    collapseTree: "Collapse this folder",
    run: "Run this skill and install it into ~/.gradum/skills",
  },

  status: {
    noSkill: "No skill is open",
    invalidName: "Invalid name",
    unreachable: "Server not responding",
    nameTaken: (name) => `${name} already exists`,
  },

  rail: {
    root: "skills",
    title: "Skill Explorer",
  },

  bottom: {
    title: "Diagnostics",
  },

  editor: {
    emptyTitle: "No skill is open",
    emptyHint: "Choose a skill in Skill Explorer, or create a new one with the Add skill button.",
  },

  statusBar: {
    lineEnding: "LF",
    encoding: "UTF-8",
    label: "Status bar",
    position: (line, column) => `${line}:${column}`,
    errors: (count) => `${count} ${plural(count, "error")}`,
    warnings: (count) => `${count} ${plural(count, "warning")}`,
    positionLabel: (line, column) => `Line ${line}, column ${column}`,
  },

  problems: {
    stale: "Edited since the last run.",
    empty: (name) => `The skill ${name} has no problems.`,
  },

  log: {
    ready: () => `Workspace connected to the Gradum server at ${location.host}; the editor is ready and ~/.gradum/skills is watched for external skill changes.`,
    deploying: (name, {lines, bytes}) =>
      `Deploying ${name} — writing the editor buffer to ~/.gradum/skills/${name} (${lines} ${plural(lines, "line")}, ${formatBytes(bytes)}), then compiling it with the embedded Kotlin compiler and reloading the external skill registry so the agent can pick the skill up.`,
    compiled: (fileName, warnings, ms) => warnings > 0
      ? `The deploy completed in ${ms} ms: ${fileName} compiled with 0 errors and ${warnings} ${plural(warnings, "warning")}, the source was written to ~/.gradum/skills/${fileName}, and the reloaded class is registered for the agent. Review ${warnings === 1 ? "the warning" : "the warnings"} in the Problems panel.`
      : `The deploy completed in ${ms} ms: ${fileName} compiled with 0 errors and 0 warnings, the source was written to ~/.gradum/skills/${fileName}, and the freshly compiled class is loaded and registered, so the agent can run the skill right now.`,
    failed: (fileName, errors, warnings, ms) =>
      `The deploy completed in ${ms} ms, but ${fileName} failed to compile with ${errors} ${plural(errors, "error")}${warnings > 0 ? ` and ${warnings} ${plural(warnings, "warning")}` : ""}. The source was still written to ~/.gradum/skills/${fileName}, while the previously compiled version, if one exists, stays loaded and registered. The compiler reported:`,
    problem: (item) => item.line != null
      ? `Line ${item.line}${item.column != null ? `, column ${item.column}` : ""}: ${item.message}`
      : `Problem: ${item.message}`,
    moreProblems: (count) => `${count} further ${plural(count, "problem")} ${count === 1 ? "is" : "are"} listed in the Problems panel.`,
    fixHint: "Fix the reported problems above and run the skill again to recompile it.",
    rejected: (status, detail) => status === 401
      ? `The server rejected the deploy request with HTTP 401 (Unauthorized): the editor token is missing or has expired. Reopen the editor URL with a fresh ?token= query parameter and try again.`
      : `The server rejected the deploy request with HTTP ${status}: ${detail}.`,
    malformed: (detail) =>
      `The server answered the deploy request but its response could not be parsed (${detail}), so the compile result is unknown.`,
    unreachable: () =>
      `The Gradum server at ${location.host} did not answer the deploy request; check that it is still running (for example with ./gradlew run) and reload the workspace.`,
  },
};
