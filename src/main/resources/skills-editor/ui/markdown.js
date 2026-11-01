// Markdown-to-DOM renderer for the agent bubble. No third-party parser (this
// page must run offline); covers paragraphs, headings, nested lists, task
// lists, fenced code with a language label and copy action, blockquotes,
// tables, rules, and the inline code / bold / italic / strikethrough / link
// shapes. Text is untrusted, so nothing is ever assigned to innerHTML: every
// token goes through createElement / createTextNode, and a link's scheme is
// checked before it becomes an href.
//
// renderMarkdown is streaming-aware: block elements are matched by position
// and type and only their contents are refreshed, so nothing reflows and
// reused nodes keep their identity across tokens.

import {safeUrl} from "../core/safety.js";

const INLINE_SPLIT =
  /(`[^`]+`|\*\*[^*]+\*\*|~~[^~]+~~|\*[^*\s][^*]*\*|\[[^\]]+]\([^)\s]+\))/;

const BLOCK_START = /^(```|#{1,6}\s|\s*[-*]\s|\s*\d+[.]\s|\s*\||\s*>)/;

const TASK_ITEM = /^\[([ xX])]\s+/;

function renderInline(parent, text) {
  for (const part of text.split(INLINE_SPLIT)) {
    if (!part) continue;
    const link = part.match(/^\[([^\]]+)]\(([^)\s]+)\)$/);
    if (part.length > 2 && part.startsWith("`") && part.endsWith("`") && !link) {
      const code = document.createElement("code");
      code.className = "md-code-inline";
      code.textContent = part.slice(1, -1);
      parent.append(code);
    } else if (part.startsWith("**") && part.endsWith("**") && part.length > 4) {
      const bold = document.createElement("strong");
      renderInline(bold, part.slice(2, -2));
      parent.append(bold);
    } else if (part.startsWith("~~") && part.endsWith("~~") && part.length > 4) {
      const strike = document.createElement("del");
      renderInline(strike, part.slice(2, -2));
      parent.append(strike);
    } else if (part.length > 2 && part.startsWith("*") && part.endsWith("*")) {
      const em = document.createElement("em");
      em.textContent = part.slice(1, -1);
      parent.append(em);
    } else if (link) {
      const href = safeUrl(link[2]);
      if (!href) {
        parent.append(document.createTextNode(part));
        continue;
      }
      const anchor = document.createElement("a");
      anchor.className = "md-link";
      anchor.href = href;
      anchor.textContent = link[1];
      anchor.target = "_blank";
      anchor.rel = "noreferrer";
      parent.append(anchor);
    } else {
      parent.append(document.createTextNode(part));
    }
  }
}

function parseBlocks(text) {
  const lines = text.split("\n");
  const blocks = [];
  let i = 0;
  while (i < lines.length) {
    const line = lines[i];
    if (/^\s*$/.test(line)) {
      i++;
    } else if (line.startsWith("```")) {
      const lang = line.slice(3).trim();
      const body = [];
      i++;
      while (i < lines.length && !lines[i].startsWith("```")) body.push(lines[i++]);
      i++;
      blocks.push({type: "code", lang, src: body.join("\n")});
    } else if (/^#{1,6}\s/.test(line)) {
      const heading = line.match(/^(#{1,6})\s+(.*)$/);
      blocks.push({type: `h${heading[1].length}`, text: heading[2]});
      i++;
    } else if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      blocks.push({type: "hr"});
      i++;
    } else if (/^\s*>/.test(line)) {
      const buf = [];
      while (i < lines.length && /^\s*>/.test(lines[i])) {
        buf.push(lines[i++].replace(/^\s*>\s?/, ""));
      }
      blocks.push({type: "quote", text: buf.join("\n")});
    } else if (/^\s*[-*]\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*[-*]\s+/.test(lines[i])) {
        const depth = Math.floor(lines[i].match(/^\s*/)[0].length / 2);
        items.push({depth, text: lines[i].replace(/^\s*[-*]\s+/, "")});
        i++;
      }
      blocks.push({type: "ul", items});
    } else if (/^\s*\d+[.]\s+/.test(line)) {
      const items = [];
      while (i < lines.length && /^\s*\d+[.]\s+/.test(lines[i])) {
        const depth = Math.floor(lines[i].match(/^\s*/)[0].length / 2);
        items.push({depth, text: lines[i].replace(/^\s*\d+[.]\s+/, "")});
        i++;
      }
      blocks.push({type: "ol", items});
    } else if (
      /^\s*\|/.test(line) &&
      i + 1 < lines.length &&
      /^\s*\|?[\s:|-]+-[\s:|-]*$/.test(lines[i + 1])
    ) {
      const rows = [];
      while (i < lines.length && /^\s*\|/.test(lines[i])) rows.push(lines[i++]);
      const cells = rows.map((row) =>
        row.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((cell) => cell.trim()),
      );
      blocks.push({type: "table", header: cells[0], rows: cells.slice(2)});
    } else {
      const buf = [line];
      i++;
      while (i < lines.length && !/^\s*$/.test(lines[i]) && !BLOCK_START.test(lines[i])) {
        buf.push(lines[i++]);
      }
      blocks.push({type: "p", text: buf.join(" ")});
    }
  }
  return blocks;
}

function buildBlock(type) {
  if (type === "code") {
    const pre = document.createElement("pre");
    pre.className = "md-code";
    pre.append(document.createElement("code"));
    return pre;
  }
  if (type === "ul" || type === "ol") {
    const list = document.createElement(type);
    list.className = "md-list";
    return list;
  }
  if (type === "quote") {
    return document.createElement("blockquote");
  }
  if (type === "table") {
    const wrap = document.createElement("div");
    wrap.className = "md-table-wrap";
    wrap.append(document.createElement("table"));
    return wrap;
  }
  const el = document.createElement(type);
  el.className = `md-${type}`;
  return el;
}

function fillItem(li, text) {
  const task = text.match(TASK_ITEM);
  if (task) {
    li.className = "md-task";
    const box = document.createElement("span");
    box.className = "md-task-box";
    box.dataset.checked = task[1] === " " ? "false" : "true";
    li.append(box);
    text = text.slice(task[0].length);
  } else {
    li.className = "";
  }
  renderInline(li, text);
}

// Rebuilds the list from flat `{depth, text}` rows; deeper rows become nested
// lists under the previous item, so agents' multi-level outlines keep their
// shape. The list is cleared first, since streaming refreshes reuse it.
function fillList(list, items) {
  list.replaceChildren();
  // Each stack entry is a list level holding items of its own depth; the
  // root list holds depth 0.
  const stack = [{depth: 0, el: list}];
  for (const {depth, text} of items) {
    const level = Math.max(0, depth);
    let owner = stack[stack.length - 1];
    while (level < owner.depth) {
      stack.pop();
      owner = stack[stack.length - 1];
    }
    // A deeper row nests under the previous item; with no previous item
    // (a deeper first row), it flattens onto the current level instead of
    // building a detached subtree.
    const parent = level > owner.depth ? owner.el.lastElementChild : null;
    if (parent) {
      const sub = document.createElement(list.tagName);
      sub.className = "md-list";
      parent.append(sub);
      stack.push({depth: level, el: sub});
    }
    const li = document.createElement("li");
    fillItem(li, text);
    stack[stack.length - 1].el.append(li);
  }
}

function fillTable(table, block) {
  const head = document.createElement("thead");
  const headRow = document.createElement("tr");
  for (const cell of block.header) {
    const th = document.createElement("th");
    renderInline(th, cell);
    headRow.append(th);
  }
  head.append(headRow);
  const body = document.createElement("tbody");
  for (const row of block.rows) {
    const tr = document.createElement("tr");
    for (const cell of row) {
      const td = document.createElement("td");
      renderInline(td, cell);
      tr.append(td);
    }
    body.append(tr);
  }
  table.replaceChildren(head, body);
}

function fillBlock(el, block) {
  if (block.type === "code") {
    el.querySelector("code").textContent = block.src;
  } else if (block.type === "ul" || block.type === "ol") {
    fillList(el, block.items);
  } else if (block.type === "table") {
    fillTable(el.firstChild, block);
  } else if (block.type === "quote") {
    // A quote renders its inner markdown with the same streaming-aware pass,
    // so a quote that grows across tokens reuses its own paragraphs.
    renderMarkdown(el, block.text);
  } else if (block.type === "hr") {
    // A rule has no content to fill.
  } else {
    // Reused blocks keep their element across renders; clear before the
    // inline pass or every token would stack another copy of the text.
    el.replaceChildren();
    renderInline(el, block.text);
  }
}

// Renders `text` into `target`, reusing block elements by position and type.
export function renderMarkdown(target, text) {
  const blocks = parseBlocks(text);
  for (let i = 0; i < blocks.length; i++) {
    const block = blocks[i];
    let el = target.children[i];
    if (!el || el.dataset.type !== block.type) {
      const fresh = buildBlock(block.type);
      fresh.dataset.type = block.type;
      if (el) target.replaceChild(fresh, el);
      else target.append(fresh);
      el = fresh;
    }
    fillBlock(el, block);
  }
  while (target.children.length > blocks.length) target.lastChild.remove();
}
