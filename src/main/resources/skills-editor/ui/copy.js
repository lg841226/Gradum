// Single source of truth for every user-facing string. Descriptive copy is
// written as complete sentences; control labels stay short verb phrases.
//
// Style: lines stay under 100 characters. Long sentences break at clause
// boundaries inside a parenthesized `+` chain, one clause per line. Branching
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
    editor: "Editor",
    output: "Console",
    explorer: "Explorer",
    problems: "Problems",
  },

  theme: {
    dark: "Dark",
    light: "Light",
    tooltip: "Change theme",
  },

  tooltip: {
    hide: "Hide",
    closeTab: "Close",
    expandTree: "Expand",
    run: "Run and install",
    newSkill: "Create skill",
    unsplit: "Unsplit editor",
    split: "Split editor right",
    splitDown: "Split editor down",
    build: "Build without installing",
    collapseTree: "Collapse this folder",
  },

  status: {
    noSkill: "No opened skill",
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
    encodingTip: "Text stored as UTF-8",
    lineEndingTip: "Lines end with LF (\\n)",
    position: (line, column) => `${line}:${column}`,
    errors: (count) => `${count} ${plural(count, "error")}`,
    warnings: (count) => `${count} ${plural(count, "warning")}`,
    positionLabel: (line, column) => `Line ${line}, column ${column}`,
    positionTip: (line, column) => `Caret at line ${line}, column ${column}`,
  },

  problems: {
    stale: "Edited since the last run.",
    empty: (name) => `The skill ${name} has no problems.`,
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
        `Check that it is still running (./gradlew run) and reload workspace.`
      ),
  },
};
