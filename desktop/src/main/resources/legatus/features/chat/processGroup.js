// The working-process group and its tool capsules. The group is a DOM concern:
// it owns its fold state and reports a user toggle back to the chat model, so
// the model can decide whether a settle may collapse it.

import {createIcon} from "../../ui/components/icon.js";
import {copy} from "../../ui/copy.js";

// Icons mirror the plugin's per-alias mapping exactly: the same platform glyphs
// now live in ui/icons.js, extracted from the IntelliJ Platform build. Anything
// unregistered falls back to AllIconsKeys.General.Groups, which is what the
// plugin's DefaultRenderer actually draws.
const TOOL_ICONS = {
  ran: "cmd",
  written: "edit",
  read: "show",
  explored: "project-wide-analysis-off",
  grep: "search",
  glob: "search",
  searched: "pp-web",
  "mcp tools": "external-tools",
  planned: "bullet-list",
  completed: "report",
};

function toolIconName(data) {
  const alias = String(data.alias ?? data.tool ?? "").trim().toLowerCase();
  if (alias === "delegate") {
    return data.arguments?.pending === false ? "checked" : "services";
  }
  return TOOL_ICONS[alias] ?? "groups";
}

function asText(value) {
  return typeof value === "string" ? value : "";
}

function firstText(...values) {
  for (const value of values) {
    const text = asText(value);
    if (text) {
      return text;
    }
  }
  return "";
}

function asCount(value) {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

// The plugin's parseLineRangePair: "12-20" is a range, "12" is a single line.
function lineRange(value) {
  const text = asText(value);
  if (!text) {
    return [null, null];
  }
  const parts = text.split("-");
  if (parts.length === 2) {
    return [Number.parseInt(parts[0], 10) || null, Number.parseInt(parts[1], 10) || null];
  }
  if (parts.length === 1) {
    return [Number.parseInt(parts[0], 10) || null, null];
  }
  return [null, null];
}

// The one-line summary the plugin draws after the tool label. Only fields the
// live `tool_call` event actually carries are read; a missing field yields a
// shorter summary instead of a fabricated zero.
export function toolSummary(data) {
  const args = data.arguments ?? {};
  const result = data.result ?? {};
  const alias = String(data.alias ?? data.tool ?? "").trim().toLowerCase();

  switch (alias) {
    case "ran": {
      const reason = firstText(args.reason);
      return [reason, args.background === true ? "后台运行" : ""].filter(Boolean).join(" ");
    }

    case "written": {
      const path = firstText(args.path);
      if (!path) {
        return "";
      }
      const added = asCount(result.linesAdded) ?? 0;
      const removed = asCount(result.linesRemoved) ?? 0;
      const total = asCount(result.totalLines) ?? 0;
      const parts = [path.split("/").pop() || path];
      if (added > 0) {
        parts.push(`+${added}`);
      }
      if (removed > 0) {
        parts.push(`-${removed}`);
      }
      if (added === 0 && removed === 0 && total > 0) {
        parts.push(`+${total}`);
      }
      return parts.join(" ");
    }

    case "read": {
      const path = firstText(args.path);
      if (!path) {
        return "";
      }
      const [rangeStart, rangeEnd] = lineRange(result.lineRange);
      const start = asCount(result.startLine) ?? rangeStart;
      const end = asCount(result.endLine) ?? rangeEnd;
      const lineText = start !== null && end !== null
        ? `第 ${start} ~ ${end} 行`
        : start !== null ? `L${start}` : "";
      return [path.split("/").pop() || path, lineText].filter(Boolean).join(" ");
    }

    case "explored": {
      const depth = asCount(result.depth);
      return depth === null ? "" : `深度 ${depth}`;
    }

    case "grep":
    case "glob": {
      const pattern = firstText(args.pattern);
      const total = asCount(alias === "grep" ? result.total_matches : result.total_files);
      return [pattern, total === null ? "" : `${total} 条匹配`].filter(Boolean).join(" ");
    }

    case "searched": {
      const query = firstText(args.query);
      const total = Array.isArray(result.results) ? result.results.length : null;
      return [query, total === null ? "" : `搜索到 ${total} 条结果`].filter(Boolean).join(" ");
    }

    case "mcp tools": {
      const query = firstText(args.query);
      const tools = Array.isArray(result.tools) ? result.tools : null;
      const total = asCount(result.exposed_tools)
        ?? asCount(result.matched)
        ?? (tools ? tools.length : null);
      return [query, total === null ? "" : `${total} 个工具`].filter(Boolean).join(" ");
    }

    case "planned": {
      const tasks = Array.isArray(result.tasks)
        ? result.tasks
        : Array.isArray(args.tasks) ? args.tasks : null;
      if (!tasks) {
        return "";
      }
      return [firstText(tasks[0]), `${tasks.length} 项任务`].filter(Boolean).join(" ");
    }

    case "completed":
      return firstText(args.task, result.currentTask, result.skippedTask, result.task, args.content);

    case "delegate":
      return firstText(result.title, args.title);

    default:
      return "";
  }
}

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
  capsule.append(createIcon({name: toolIconName(data)}));

  const label = document.createElement("span");
  label.className = "tool-capsule-label";
  label.textContent = humanizeToolName(data.alias || data.tool);
  capsule.append(label);

  const summaryText = toolSummary(data);
  if (summaryText) {
    const summary = document.createElement("span");
    summary.className = "tool-capsule-detail";
    summary.textContent = summaryText;
    capsule.append(summary);
  }

  if (data.success !== true) {
    capsule.dataset.failed = "true";
    capsule.title = toolDetailsText(data);
  }
  return capsule;
}
