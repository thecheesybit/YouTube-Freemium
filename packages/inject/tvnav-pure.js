// Movy TV navigation: pure functions (no DOM). Rects are {l,t,r,b}. Loaded before tvnav.js in the browser
// (exported as window.__movyTvPure) and required by the node unit tests (module.exports).
(function (root) {
  'use strict';

  var DIRS = { left: [-1, 0], right: [1, 0], up: [0, -1], down: [0, 1] };

  // ---------- pure: scoring ----------

  /** rect: {l,t,r,b}. Returns a score (lower is better) or -1 when `to` is not a candidate in direction `dir`. */
  function score(from, to, dir) {
    var d = DIRS[dir];
    var fcx = (from.l + from.r) / 2, fcy = (from.t + from.b) / 2;
    var tcx = (to.l + to.r) / 2, tcy = (to.t + to.b) / 2;
    var main, gap, perpCenter, perpGap, fromHalfPerp, toHalfPerp;
    if (d[0] !== 0) {
      main = (tcx - fcx) * d[0];
      gap = d[0] > 0 ? to.l - from.r : from.l - to.r;
      perpCenter = Math.abs(tcy - fcy);
      perpGap = Math.max(0, Math.max(from.t, to.t) - Math.min(from.b, to.b));
      fromHalfPerp = (from.b - from.t) / 2;
      toHalfPerp = (to.b - to.t) / 2;
    } else {
      main = (tcy - fcy) * d[1];
      gap = d[1] > 0 ? to.t - from.b : from.t - to.b;
      perpCenter = Math.abs(tcx - fcx);
      perpGap = Math.max(0, Math.max(from.l, to.l) - Math.min(from.r, to.r));
      fromHalfPerp = (from.r - from.l) / 2;
      toHalfPerp = (to.r - to.l) / 2;
    }
    if (main <= 1) return -1; // must lie ahead of the current element's centre
    if (gap < -Math.min(fromHalfPerp, toHalfPerp) * 0.5 - 2 && gap < 0) {
      // heavily overlapping along the axis: only accept when clearly ahead
      if (main < 8) return -1;
    }
    // cone: roughly +-68 degrees, widened by the element sizes so big cards stay reachable
    if (perpCenter > main * 2.5 + fromHalfPerp + toHalfPerp) return -1;
    var g = Math.max(0, gap);
    return g + perpGap * 2.4 + perpCenter * 0.6 + main * 0.05;
  }

  /** cands: [{rect}] ; returns the best candidate for `dir` from `from`, or null. */
  function pick(from, cands, dir) {
    var best = null, bestScore = Infinity;
    for (var i = 0; i < cands.length; i++) {
      var s = score(from, cands[i].rect, dir);
      if (s >= 0 && s < bestScore) { best = cands[i]; bestScore = s; }
    }
    return best;
  }

  /** First element in reading order inside the viewport (used when nothing is focused yet). */
  function pickInitial(cands, vw, vh) {
    var best = null, bestKey = Infinity;
    for (var i = 0; i < cands.length; i++) {
      var r = cands[i].rect;
      if (r.b < 0 || r.t > vh || r.r < 0 || r.l > vw) continue;
      var key = Math.max(0, r.t) * 3 + Math.max(0, r.l);
      if (key < bestKey) { best = cands[i]; bestKey = key; }
    }
    return best;
  }

  /** Seeks by `delta` seconds within [0, duration]. */
  function seekTarget(current, duration, delta) {
    var t = current + delta;
    if (t < 0) t = 0;
    if (isFinite(duration) && duration > 0 && t > duration) t = duration;
    return t;
  }

  function fmt(sec) {
    if (!isFinite(sec) || sec < 0) sec = 0;
    sec = Math.floor(sec);
    var h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
    var mm = (h > 0 && m < 10 ? '0' : '') + m, ss = (s < 10 ? '0' : '') + s;
    return (h > 0 ? h + ':' : '') + mm + ':' + ss;
  }


  // ---------- pure: visibility ----------

  /**
   * Whether an element described by `d` can take focus. d: {w, h, display, visibility, pointerEvents, opacity,
   * hiddenAncestor, closedDetails, offscreenLayer, disabled}. Never focusable: anything not rendered, inside a closed
   * <details>, inside an aria-hidden ancestor or an off-screen slider layer.
   */
  function renderable(d, minSize) {
    var m = minSize == null ? 14 : minSize;
    if (!d || d.disabled) return false;
    if (d.w < m || d.h < m) return false;
    if (d.display === 'none' || d.visibility === 'hidden' || d.visibility === 'collapse') return false;
    if (d.pointerEvents === 'none') return false;
    if (d.opacity !== undefined && d.opacity === 0) return false;
    if (d.hiddenAncestor || d.closedDetails || d.offscreenLayer) return false;
    return true;
  }

  // ---------- pure: rows, lines and column keeping ----------

  function cx(r) { return (r.l + r.r) / 2; }
  function cy(r) { return (r.t + r.b) / 2; }

  /** Vertical overlap of two rects as a fraction of the smaller height. */
  function vOverlap(a, b) {
    var o = Math.min(a.b, b.b) - Math.max(a.t, b.t);
    var h = Math.min(a.b - a.t, b.b - b.t);
    return h > 0 ? o / h : 0;
  }

  /**
   * Groups items [{id, rect, row}] into horizontal lines. All items with the same non-null `row` form one line (a
   * carousel); free items (row == null) join a line when they overlap vertically by at least half. Lines are sorted by
   * centre y, and each line's items by centre x.
   */
  function buildLines(items) {
    var byRow = {}, lines = [], free = [], i, it, l;
    for (i = 0; i < items.length; i++) {
      it = items[i];
      if (it.row != null) {
        l = byRow[it.row];
        if (!l) { l = byRow[it.row] = { row: it.row, items: [], free: false }; lines.push(l); }
        l.items.push(it);
      } else free.push(it);
    }
    free.sort(function (a, b) { return cy(a.rect) - cy(b.rect); });
    var freeLines = [];
    for (i = 0; i < free.length; i++) {
      it = free[i];
      l = null;
      for (var j = freeLines.length - 1; j >= 0 && j >= freeLines.length - 3; j--) {
        if (vOverlap(it.rect, freeLines[j].box) >= 0.5) { l = freeLines[j]; break; }
      }
      if (!l) { l = { row: null, items: [], free: true, box: { l: it.rect.l, t: it.rect.t, r: it.rect.r, b: it.rect.b } }; freeLines.push(l); lines.push(l); }
      l.items.push(it);
      l.box = { l: Math.min(l.box.l, it.rect.l), t: Math.min(l.box.t, it.rect.t), r: Math.max(l.box.r, it.rect.r), b: Math.max(l.box.b, it.rect.b) };
    }
    for (i = 0; i < lines.length; i++) {
      l = lines[i];
      l.items.sort(function (a, b) { return cx(a.rect) - cx(b.rect); });
      var t = Infinity, b = -Infinity;
      for (var k = 0; k < l.items.length; k++) { t = Math.min(t, l.items[k].rect.t); b = Math.max(b, l.items[k].rect.b); }
      l.top = t; l.bottom = b; l.cy = (t + b) / 2;
    }
    lines.sort(function (a, b2) { return a.cy - b2.cy; });
    return lines;
  }

  function lineOf(lines, id) {
    for (var i = 0; i < lines.length; i++) {
      for (var j = 0; j < lines[i].items.length; j++) if (lines[i].items[j].id === id) return { line: i, index: j };
    }
    return null;
  }

  /** Item of `line` closest to column x: one containing x wins, then the nearest edge, then the nearest centre. */
  function nearestInColumn(line, x, vw) {
    var best = null, bd = Infinity, bc = Infinity;
    for (var i = 0; i < line.items.length; i++) {
      var r = line.items[i].rect, c = cx(r);
      if (vw && (c < 0 || c > vw)) continue;
      var d = x < r.l ? r.l - x : x > r.r ? x - r.r : 0;
      var dc = Math.abs(c - x);
      if (d < bd || (d === bd && dc < bc)) { best = line.items[i]; bd = d; bc = dc; }
    }
    return best;
  }

  /** UP/DOWN: nearest card of the previous/next line, keeping the column `x`. Returns an item or null. */
  function pickVertical(lines, id, dir, x, vw) {
    var at = lineOf(lines, id);
    if (!at) return null;
    var step = dir === 'down' ? 1 : -1;
    var curLine = lines[at.line];
    for (var i = at.line + step; i >= 0 && i < lines.length; i += step) {
      var l = lines[i];
      if (Math.abs(l.cy - curLine.cy) < 4) continue;
      var it = nearestInColumn(l, x, vw);
      if (it) return it;
    }
    return null;
  }

  /**
   * LEFT/RIGHT: inside a carousel the neighbour in order (null at the ends, the row never leaks); otherwise the
   * neighbour on the same free line, else the best spatial candidate among all items.
   */
  function pickHorizontal(lines, id, dir, from, all) {
    var at = lineOf(lines, id);
    if (!at) return pick(from, all, dir);
    var line = lines[at.line];
    var step = dir === 'right' ? 1 : -1;
    if (!line.free) return line.items[at.index + step] || null;
    var next = line.items[at.index + step];
    if (next) return next;
    return pick(from, all.filter(function (c) { return c.id !== id && line.items.indexOf(c) < 0; }), dir);
  }

  /** Column bookkeeping: horizontal moves reset the column to the new element's centre; vertical moves keep it. */
  function nextColumn(col, rect, dir) {
    if (dir === 'up' || dir === 'down') return col == null ? cx(rect) : col;
    return cx(rect);
  }

  /** Horizontal scroll delta for a card inside a scroller so it is fully visible with a peek of the next one. */
  function rowScrollDelta(card, view, peek) {
    var p = peek || 0;
    if (card.l - p < view.l) return card.l - p - view.l;
    if (card.r + p > view.r) return card.r + p - view.r;
    return 0;
  }

  /** Entry candidate when focus enters a frame from direction `dir`. */
  function pickEntry(cands, dir) {
    var best = null, bk = Infinity;
    for (var i = 0; i < cands.length; i++) {
      var r = cands[i].rect;
      var key = dir === 'down' ? r.t * 4 + r.l : dir === 'up' ? -r.b * 4 + r.l : dir === 'right' ? r.l * 4 + r.t : -r.r * 4 + r.t;
      if (key < bk) { best = cands[i]; bk = key; }
    }
    return best;
  }

  var api = {
    score: score, pick: pick, pickInitial: pickInitial, seekTarget: seekTarget, fmt: fmt,
    renderable: renderable, buildLines: buildLines, lineOf: lineOf, nearestInColumn: nearestInColumn,
    pickVertical: pickVertical, pickHorizontal: pickHorizontal, nextColumn: nextColumn, rowScrollDelta: rowScrollDelta,
    pickEntry: pickEntry
  };
  if (typeof module === 'object' && module && module.exports) module.exports = api;
  else if (typeof window !== 'undefined') window.__movyTvPure = api;
})(typeof window !== 'undefined' ? window : {});
