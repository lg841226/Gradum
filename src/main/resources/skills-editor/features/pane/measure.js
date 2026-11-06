// Horizontal measurement against the rendered highlight overlay.
//
// The caret + the squiggles are drawn, not laid out by the text layer, so
// their horizontal offsets are read back off the very glyphs on screen instead
// of being predicted from a font. Neither counting columns nor measuring with
// a canvas survives a fallback: a CJK glyph is drawn by whatever font the stack
// falls through to, and its advance is whatever that font says. A Range over
// the line's own text nodes answers exactly, and because the range is read
// after the overlay has been scrolled it already carries the horizontal
// scroll. Reading a rect does not dirty layout the way a style write would, so
// a scroll frame stays cheap.

export function createMeasure({highlightCode}) {
  function textPositionAt(lineNode, index) {
    let remaining = index;
    const walker = document.createTreeWalker(lineNode, NodeFilter.SHOW_TEXT);

    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      if (remaining <= node.data.length) {
        return {node, offset: remaining};
      }
      remaining -= node.data.length;
    }
    return null;
  }

  function advance(lineNumber, charCount) {
    if (charCount <= 0) {
      return 0;
    }
    const lineNode = highlightCode.querySelector(`.line[data-line="${lineNumber}"]`);
    if (!lineNode) {
      return 0;
    }
    const position = textPositionAt(lineNode, charCount);
    if (!position) {
      return 0;
    }

    const range = document.createRange();
    range.setStart(lineNode, 0);
    range.setEnd(position.node, position.offset);

    const rangeEnd = range.getBoundingClientRect().right;
    const lineStart = lineNode.getBoundingClientRect().left;
    return Math.max(0, rangeEnd - lineStart);
  }

  return {advance};
}
