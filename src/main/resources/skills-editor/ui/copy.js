// Single source of truth for every user-facing string. Descriptive copy is
// written as complete sentences; control labels stay short verb phrases.

function plural(count, noun) {
  return count === 1 ? noun : `${noun}s`;
}

export const copy = {
  brand: {
    name: "Gradum Workspace",
  },

  control: {
    explorer: "Explorer",
    problems: "Problems",
    output: "Output",
    editor: "Editor",
    all: "All",
  },

  theme: {
    light: "Light",
    dark: "Dark",
    tooltip: (name) => `Theme ${name}. Click to change.`,
  },

  tooltip: {
    run: "Run this skill and install it into ~/.gradum/skills",
    newSkill: "Create a new skill",
    hide: "Hide this panel",
    collapseTree: "Collapse this folder",
    expandTree: "Expand this folder",
    resize: "Drag to resize the panel",
    closeTab: "Close this skill tab",
  },

  status: {
    noSkill: "No skill is open",
    invalidName: "Invalid name",
    nameTaken: (name) => `${name} already exists`,
    unreachable: "Server not responding",
  },

  rail: {
    title: "Skill Explorer",
    root: "skills",
  },

  bottom: {
    title: "Diagnostics",
  },

  editor: {
    emptyTitle: "No skill is open",
    emptyHint: "Choose a skill in Skill Explorer, or create a new one with the Add skill button.",
  },

  statusBar: {
    errors: (count) => `${count} ${plural(count, "error")}`,
    warnings: (count) => `${count} ${plural(count, "warning")}`,
  },

  problems: {
    empty: (name) => `The skill ${name} has no problems.`,
    stale: "Edited since the last run.",
  },

  log: {
    ready: "The skill editor is ready.",
    deploying: (name) => `The skill ${name} is being deployed to ~/.gradum/skills.`,
    compiled: (name, warnings) => warnings > 0
      ? `The skill ${name} compiled with ${warnings} ${plural(warnings, "warning")}.`
      : `The skill ${name} compiled successfully.`,
    failed: (name, errors) => `The skill ${name} has ${errors} ${plural(errors, "error")}.`,
  },
};
