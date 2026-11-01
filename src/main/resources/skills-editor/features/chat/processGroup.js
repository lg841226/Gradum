// The working-process group and its tool capsules. The group is a DOM concern:
// it owns its fold state and reports a user toggle back to the chat model, so
// the model can decide whether a settle may collapse it.

import {createIcon} from "../../ui/components/icon.js";
import {copy} from "../../ui/copy.js";

// snake_case aliases become "Read File" style titles, matching the plugin's
// humanizeToolName.
export function humanizeToolName(raw) {
  return String(raw ?? "").split("_").filter(Boolean)
    .map((word) => word.toLowerCase().replace(/^./, (char) => char.toUpperCase()))
    .join(" ") || "Tool";
}

// Failed capsules carry a hover tooltip with the full debug snapshot, in the
// shape of the plugin's formatToolDetails.
export function toolDetailsText(data) {
  const lines = [`Tool: ${data.alias || data.tool || "unknown"}`];
  const args = data.arguments;
  if (args && typeof args === "object") {
    lines.push("Arguments:");
    for (const [key, value] of Object.entries(args)) {
      lines.push(`  ${key}: ${JSON.stringify(value)}`);
    }
  }
  if (data.result != null) {
    lines.push(`Result: ${JSON.stringify(data.result)}`);
  }
  if (data.errorMessage) {
    lines.push(`Error: ${data.errorMessage}`);
  }
  if (data.errorDetail) {
    lines.push(`Detail:\n${data.errorDetail}`);
  }
  return lines.join("\n");
}

export function createProcessGroup({onToggle} = {}) {
  const element = document.createElement("details");
  element.className = "tool-group";
  element.open = true;
  element.dataset.live = "true";

  const summary = document.createElement("summary");
  summary.append(createIcon({name: "chevron-down"}));
  const label = document.createElement("span");
  label.className = "tool-group-label sweep-text";
  label.textContent = copy.chat.working;
  summary.append(label);

  const body = document.createElement("div");
  body.className = "tool-group-body";
  element.append(summary, body);

  // Only the live group reports a toggle: clicking a settled group folds it
  // open or shut, but that choice belongs to the DOM, not to the turn that is
  // still running (and may have its own group by then).
  summary.addEventListener("click", () => {
    if (element.dataset.live === "true") {
      onToggle?.();
    }
  });

  return {
    element,
    body,
    settle(collapse) {
      element.dataset.live = "false";
      label.classList.remove("sweep-text");
      if (collapse) {
        element.open = false;
      }
    },
  };
}

export function createToolCapsule(data) {
  const capsule = document.createElement("div");
  capsule.className = "tool-capsule";
  capsule.append(createIcon({name: "nodes-plugin"}));

  const label = document.createElement("span");
  label.className = "tool-capsule-label";
  label.textContent = humanizeToolName(data.alias || data.tool);
  capsule.append(label);

  if (data.success !== true) {
    capsule.dataset.failed = "true";
    capsule.title = toolDetailsText(data);
  }
  return capsule;
}
