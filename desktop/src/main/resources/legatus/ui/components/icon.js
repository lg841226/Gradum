import {icons} from "../icons.js";

const SVG_NS = "http://www.w3.org/2000/svg";

const FILE_TYPE_ICONS = {
  kt: "kotlin",
  kts: "kotlin",
  java: "java",
  css: "css",
  scss: "css",
  less: "css",
  html: "html",
  htm: "html",
  js: "javascript",
  mjs: "javascript",
  cjs: "javascript",
  jsx: "javascript",
  json: "json",
  md: "markdown",
  markdown: "markdown",
  txt: "file",
  png: "image",
  jpg: "image",
  jpeg: "image",
  gif: "image",
  webp: "image",
  svg: "image",
  bmp: "image",
  ico: "image",
  tif: "image",
  tiff: "image",
};

export function fileTypeIcon(path) {
  const lower = String(path).toLowerCase();
  const cut = Math.max(lower.lastIndexOf("/"), lower.lastIndexOf("\\"));
  const name = cut >= 0 ? lower.slice(cut + 1) : lower;
  const dot = name.lastIndexOf(".");
  return FILE_TYPE_ICONS[name.slice(dot + 1)] || "file";
}

export function createIcon({name, size = 16} = {}) {
  const svg = document.createElementNS(SVG_NS, "svg");
  svg.setAttribute("width", String(size));
  svg.setAttribute("height", String(size));
  svg.setAttribute("fill", "none");
  svg.setAttribute("focusable", "false");
  svg.setAttribute("viewBox", "0 0 16 16");
  svg.setAttribute("stroke-width", "1.5");
  svg.setAttribute("aria-hidden", "true");
  svg.setAttribute("stroke", "currentColor");
  svg.setAttribute("stroke-linecap", "round");
  svg.setAttribute("stroke-linejoin", "round");
  svg.classList.add("icon");
  svg.innerHTML = icons[name] || "";
  return svg;
}
