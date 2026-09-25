/*
 * Copyright (c) 2026 Gradum Authors
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * app.js  2026-09-26 00:40:29 Changed by gwy
 */

/* ---------------------------------------------------------------------------
 * Controlled Single-Model Benchmark
 * app.js - data-driven rendering, tabs, progress-bar animation.
 *
 * Conventions:
 *  - ES module-free, dependency-free single file for easy static hosting.
 *  - Runs in strict mode inside an IIFE to avoid polluting the global scope.
 *  - All data lives in a single `BENCHMARK` constant; markup is derived from it,
 *    so multipliers and baseline markers are never hard-coded.
 *  - Uses semantic data-* / aria-* attributes replaced by JS: no inline handlers.
 * ------------------------------------------------------------------------- */

'use strict';

(() => {
  // Which agent is the "primary" (accent-colored) row.
  const PRIMARY_TOOL = 'Gradum';

  // Tested agent versions, shown as a suffix on the name label.
  const VERSIONS = {
    Gradum: '1.0.1 Experimental',
    'Claude Code': '2.1.177',
    Codex: '0.155.1',
    OpenCode: '1.18.21',
  };

  // Wide border for each dimension. Fill-bar width is derived from
  // (value / MAX_SCORE); see `MIN_BAR_PERCENT` for the zero-score floor.

  // Minimum visible bar length (%). A 0 score would otherwise render an
  // invisible zero-width bar; clamp it so the row stays readable.
  const MIN_BAR_PERCENT = 4;

  // Maximum achievable score per dimension. Fill-bar width is derived from
  // these (value / MAX_SCORE), so it can never drift out of sync with DATA.
  const MAX_SCORE = {
    completion: 20, // 20 per agent
    tooling: 15,    // 15 per agent
    boundary: 15,   // 15 per agent
    leak: 10,       // 10 per agent
    total: 60,      // 60 per agent
  };

  const DATA = [
    {id: 'completion', scores: {'Gradum': 20, 'Claude Code': 6, 'Codex': 2, 'OpenCode': 0}},
    {id: 'tooling', scores: {'Gradum': 14, 'Claude Code': 3, 'Codex': 1, 'OpenCode': 0}},
    {id: 'boundary', scores: {'Gradum': 15, 'Claude Code': 10, 'Codex': 6, 'OpenCode': 8}},
    {id: 'leak', scores: {'Gradum': 8, 'Claude Code': 3, 'Codex': 6, 'OpenCode': 1}},
    {id: 'total', scores: {'Gradum': 57, 'Claude Code': 22, 'Codex': 15, 'OpenCode': 9}},
  ];

  const INITIAL_TAB = DATA[0].id;
  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));

  /** Format a multiplier for display: trim a trailing `.0`. */
  const formatMultiplier = (value) => value.toFixed(1).replace(/\.0$/, '');

  /**
   * Compute the baseline (anchor) for one dimension.
   * The baseline is the lowest NON-zero score (guards against division by zero).
   * Returns null when every score in the dimension is zero, so callers can
   * skip multiplier math instead of tripping over `Math.min(...[]) = Infinity`.
   * @returns {{ anchorVal: number, anchorTools: string[] } | null}
   */
  function baselineOf(entries) {
    const positiveValues = entries
      .map(([, value]) => value)
      .filter((value) => value > 0);

    if (positiveValues.length === 0) return null;

    const anchorVal = Math.min(...positiveValues);
    const anchorTools = entries
      .filter(([, value]) => value === anchorVal)
      .map(([key]) => key);

    return {anchorVal, anchorTools};
  }

  /** Build the right-hand readout cell for one row. */
  function buildReadout({value, isAnchor, anchorVal, isPrimary, showScore}) {
    const modifier = isPrimary ? 'primary' : 'dim';

    let display;
    if (showScore) {
      display = value; // Total tab renders the raw aggregated score.
    } else if (isAnchor || anchorVal === 0) {
      return ''; // anchor row shows no multiplier; all-zero dims have nothing to divide by
    } else {
      display = `${formatMultiplier(value / anchorVal)}<small>x</small>`;
    }

    return `
      <div class="numwrap">
        <span class="num num--${modifier}">${display}</span>
      </div>`;
  }

  /** Build a single benchmark row. */
  function buildRow({tool, value, isAnchor, anchorVal, fillPercent, isPrimary, showScore}) {
    const modifier = isPrimary ? 'primary' : 'dim';
    const baselineMarkup = isAnchor ? ' <span class="base-tag">(Baseline)</span>' : '';

    return `
      <div class="row">
        <div>
          <div class="track">
            <div class="fill fill--${modifier}" data-width="${fillPercent}"></div>
          </div>
          <div class="name">${tool}<span class="version">v${VERSIONS[tool]}</span>${baselineMarkup}</div>
        </div>
        <div>${buildReadout({value, isAnchor, anchorVal, isPrimary, showScore})}</div>
      </div>`;
  }

  /** Build one tab-panel (section) for a dimension. */
  function buildPanel(dimension, index) {
    const entries = Object.entries(dimension.scores);
    const {anchorVal, anchorTools} = baselineOf(entries) ?? {anchorVal: 0, anchorTools: []};

    // Sort descending so the strongest agent always sits on top.
    const rows = entries
      .slice()
      .sort((a, b) => b[1] - a[1])
      .map(([tool, value]) =>
        buildRow({
          tool,
          value,
          isAnchor: anchorTools.includes(tool),
          anchorVal,
          fillPercent: Math.max(MIN_BAR_PERCENT, Math.round((value / MAX_SCORE[dimension.id]) * 100)),
          isPrimary: tool === PRIMARY_TOOL,
          showScore: dimension.id === 'total',
        })
      )
      .join('');

    const panel = document.createElement('section');
    panel.className = 'panel';
    panel.id = dimension.id;
    panel.dataset.active = (index === 0).toString();
    panel.innerHTML = rows;
    return panel;
  }

  /** Render all panels into the host container. */
  function render() {
    const host = $('#panels');
    DATA.forEach((dimension, index) => host.appendChild(buildPanel(dimension, index)));
  }

  /**
   * Show the panel with the given id and activate its tab.
   * @param {HTMLElement} tab The tab element that initiated the switch.
   */
  function activateTab(tab) {
    const panelId = tab.dataset.tab;
    // True when this tab was already selected before the click (an idle re-click).
    const wasActive = tab.getAttribute('aria-selected') === 'true';

    $$('.tab').forEach((t) => {
      t.setAttribute('aria-selected', t === tab ? 'true' : 'false');
      t.setAttribute('tabindex', t === tab ? '0' : '-1');
    });

    $$('.panel').forEach((panel) => {
      panel.dataset.active = (panel.id === panelId).toString();
    });

    // Replay the fill animation when the freshly-shown panel is visible.
    if (!wasActive) {
      onPanelShown($(`#${panelId}`));
    }
  }

  /** Animate the fill bars of a panel (re-run the CSS transition). */
  function onPanelShown(panel) {
    panel.querySelectorAll('.fill').forEach((fill) => {
      fill.style.width = '0';
      requestAnimationFrame(() => {
        fill.style.width = `${fill.dataset.width}%`;
      });
    });
  }

  /** Move focus between tab stops (roving tabindex) and activate the target. */
  function moveFocus(currentTab, direction) {
    const tabs = $$('.tab');
    const currentIndex = tabs.indexOf(currentTab);
    const nextIndex =
      (currentIndex + direction + tabs.length) % tabs.length;
    tabs[nextIndex].focus();
    activateTab(tabs[nextIndex]);
  }

  function init() {
    render();

    $$('.tab').forEach((tab) => {
      tab.addEventListener('click', () => activateTab(tab));

      tab.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          activateTab(tab);
        } else if (event.key === 'ArrowRight') {
          event.preventDefault();
          moveFocus(tab, 1);
        } else if (event.key === 'ArrowLeft') {
          event.preventDefault();
          moveFocus(tab, -1);
        }
      });
    });

    // Start with the initial tab selected and its bars animated.
    const initialTab = $(`.tab[data-tab="${INITIAL_TAB}"]`);
    initialTab.setAttribute('aria-selected', 'true');
    onPanelShown($(`#${INITIAL_TAB}`));
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
