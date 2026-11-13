// Movy TV remote navigation, profile driven. Injected at document start in every frame of the tv flavor only.
// Concatenated after tvnav-pure.js (window.__movyTvPure) and before tvfs.js (idle, fullscreen policy, media, viewport).
// - Model: the page is a list of lines: carousels ("rows", from the per-source profile or auto-detected horizontal
//   scrollers) holding cards, plus free-standing focusables. LEFT/RIGHT walk inside a row and scroll it so the card is
//   fully visible; UP/DOWN go to the nearest card of the previous/next line keeping the column (Netflix behaviour).
// - Never focusable: profile `skip`, anything not rendered, closed <details>, aria-hidden / off-screen slider layers.
// - First focus on load and on route change: profile `firstFocus` (hero Play), else the first card, never the header.
// - Candidates are cached and rebuilt on a throttled MutationObserver, so a key press only measures rects.
// - Frames: one instance per frame; `__movyTvExit` leaves a frame, `__movyTvEnter` enters one from a direction.
(function (root) {
  'use strict';
  if (typeof window === 'undefined' || window.__movyTv) return;
  var P = root.__movyTvPure;
  if (!P) return;

  var doc = document;
  var inFrame = root.parent !== root;
  var SEL = 'a[href],button,input:not([type=hidden]),select,textarea,summary,iframe,[role=button],[role=link],[role=tab],' +
    '[role=menuitem],[role=checkbox],[role=switch],[role=option],[role=slider],[contenteditable=""],[contenteditable=true],' +
    '[tabindex]:not([tabindex="-1"]),[data-movy-tv]';
  var SKIP_TAG = { SCRIPT: 1, STYLE: 1, LINK: 1, META: 1, HEAD: 1, TITLE: 1, NOSCRIPT: 1, BR: 1, HTML: 1, BODY: 1, SVG: 1, PATH: 1 };
  var TEXT_INPUT = /^(text|search|email|url|tel|password|number)$/i;
  var MAX_CANDS = 700;

  var TV = { PF: null, timings: [], hooks: {}, stats: { rebuilds: 0, lastBuildMs: 0 } };
  root.__movyTv = TV;

  // ---------- profile ----------

  function okSel(s) { try { doc.documentElement.matches(s); return true; } catch (e) { return false; } }
  function joinSel(arr) {
    var out = [];
    for (var i = 0; arr && i < arr.length; i++) if (typeof arr[i] === 'string' && okSel(arr[i])) out.push(arr[i]);
    return out.join(',');
  }

  /** Normalises the raw profile into joined selector strings (empty string = none). */
  function compile(raw) {
    raw = raw || {};
    var pl = raw.player || {}, sv = raw.servers || {}, ep = raw.episodes || {};
    return {
      raw: raw,
      header: joinSel(raw.header), bottomBar: joinSel(raw.bottomBar), rows: joinSel(raw.rows), cards: joinSel(raw.cards),
      focusables: joinSel(raw.focusables), skip: joinSel(raw.skip), firstFocus: raw.firstFocus || [], playButtons: joinSel(raw.playButtons),
      killOnTv: raw.killOnTv || [], viewportWidth: raw.viewportWidth || 1280,
      player: { frame: pl.frame || '', video: pl.video || '', root: pl.root || '', fullscreenTarget: pl.fullscreenTarget || '',
        fullscreenButton: joinSel(pl.fullscreenButton), controls: joinSel(pl.controls), overlayKill: joinSel(pl.overlayKill), depth: pl.depth || 0 },
      servers: sv, episodes: ep
    };
  }

  function loadProfile() {
    var json = null;
    try {
      // The profile follows the TOP-level site: an embed frame asks with its ancestor's host.
      var ao = root.location.ancestorOrigins, top = inFrame && ao && ao.length ? ao[ao.length - 1] : root.location.origin;
      if (root.MovyTvNative && root.MovyTvNative.profile) json = root.MovyTvNative.profile(top);
    } catch (e) { /* none */ }
    var raw = null;
    try { raw = json ? JSON.parse(json) : null; } catch (e) { raw = null; }
    setProfile(raw);
    return !!raw;
  }

  function setProfile(raw) {
    TV.PF = compile(raw);
    cache.dirty = true;
    rowSetKey = '';
    var hs = TV.hooks.profile;
    if (hs) hs(TV.PF);
    scheduleRebuild(0);
  }

  // ---------- state ----------

  var current = null;
  var lastRect = null;
  var colX = null;
  var userMoved = false;
  var idCounter = 0;
  var ids = new WeakMap();
  var cache = { list: [], dirty: true, builtAt: 0, rowOf: new WeakMap(), modal: null };
  var rowSetKey = '';
  var rebuildTimer = 0;
  var lastRebuildCost = 30;
  var beforeModal = null;
  var fallbackFocus = false; // first focus landed on a card because the hero Play was not there yet

  function idOf(el) {
    var id = ids.get(el);
    if (!id) { id = ++idCounter; ids.set(el, id); }
    return id;
  }

  // ---------- candidates ----------

  function toRect(r) { return { l: r.left, t: r.top, r: r.right, b: r.bottom }; }
  function inView(r, mx, my) {
    return r.right > -mx && r.left < root.innerWidth + mx && r.bottom > -my && r.top < root.innerHeight + my;
  }

  function closedDetails(el) {
    var d = el.closest('details');
    while (d) {
      if (!d.open && !(el.tagName === 'SUMMARY' && el.parentElement === d)) return true;
      d = d.parentElement ? d.parentElement.closest('details') : null;
    }
    return false;
  }

  /** -1: fixed to the top (header), +1: fixed to the bottom, 0: scrolls with the page. Fixed bars must not interleave with page rows. */
  function fixOf(el, PF) {
    if (PF.header && el.closest(PF.header)) return -1;
    if (PF.bottomBar && el.closest(PF.bottomBar)) return 1;
    var p = el, n = 0;
    while (p && p !== doc.body && n++ < 8) {
      var pos = root.getComputedStyle(p).position;
      if (pos === 'fixed' || pos === 'sticky') {
        var r = p.getBoundingClientRect();
        return (r.top + r.bottom) / 2 < root.innerHeight / 2 ? -1 : 1;
      }
      p = p.parentElement;
    }
    return 0;
  }

  /** Profile `skip` matches the element itself (list descendants explicitly, e.g. "footer a"); a profile card always wins. */
  function isSkipped(el, PF) {
    if (!PF.skip || !el.matches(PF.skip)) return false;
    return !(PF.cards && el.matches(PF.cards));
  }

  /** A tabindex scroll container that merely holds focusable cards (Movy track, Moonflix region). */
  function isScrollHolder(el) {
    if (!el.hasAttribute('tabindex') && el.tagName !== 'DIV' && el.tagName !== 'SECTION' && el.tagName !== 'UL') return false;
    if (el.tagName === 'A' || el.tagName === 'BUTTON' || el.tagName === 'INPUT') return false;
    if (el.scrollWidth <= el.clientWidth + 12) return false;
    return !!el.querySelector('a[href],button,[role=button],[tabindex]:not([tabindex="-1"])');
  }

  function horizontalScroller(el) {
    var p = el.parentElement, n = 0;
    while (p && n++ < 10) {
      if (p.scrollWidth > p.clientWidth + 12) {
        var ox = root.getComputedStyle(p).overflowX;
        if (ox === 'auto' || ox === 'scroll') return p;
      }
      p = p.parentElement;
    }
    return null;
  }

  /** Full (slow) candidate scan; runs off the key path and is cached. */
  function buildCache() {
    var t0 = root.performance.now();
    var PF = TV.PF || compile(null);
    var out = [], seen = new Set(), i, el, r;
    var sel = SEL + (PF.focusables ? ',' + PF.focusables : '') + (PF.cards ? ',' + PF.cards : '');
    var nodes = doc.querySelectorAll(sel);
    var vh = root.innerHeight;
    for (i = 0; i < nodes.length && out.length < MAX_CANDS; i++) {
      el = nodes[i];
      if (seen.has(el) || isSkipped(el, PF)) continue;
      if (el.tagName === 'IFRAME' && !isPlayerFrame(el, PF)) continue;
      r = el.getBoundingClientRect();
      if (!inView(r, 3000, vh * 3)) continue;
      if (r.width < 14 || r.height < 14) continue;
      seen.add(el);
      out.push({ el: el, native: true, card: !!(PF.cards && el.matches(PF.cards)), fix: fixOf(el, PF) });
    }
    // Clickable "cards": elements the page made clickable with cursor:pointer (outermost of each chain).
    var all = doc.body ? doc.body.getElementsByTagName('*') : [];
    var checked = 0;
    for (i = 0; i < all.length && checked < 2600 && out.length < MAX_CANDS; i++) {
      el = all[i];
      if (SKIP_TAG[el.tagName] || seen.has(el)) continue;
      checked++;
      r = el.getBoundingClientRect();
      if (r.width < 24 || r.height < 24 || !inView(r, 400, vh * 2)) continue;
      var cs = root.getComputedStyle(el);
      if (cs.cursor !== 'pointer' || cs.visibility === 'hidden' || cs.pointerEvents === 'none') continue;
      var p = el.parentElement;
      if (p && !seen.has(p) && root.getComputedStyle(p).cursor === 'pointer' && !p.matches(SEL)) continue;
      var near = el.closest(SEL);
      if (near && near !== el) continue;
      if (el.closest('[aria-hidden=true]') || isSkipped(el, PF)) continue;
      seen.add(el);
      out.push({ el: el, native: false, card: false, fix: fixOf(el, PF) });
    }
    // Drop heuristic wrappers that contain other candidates, and anything inside a profile card or a holder.
    var res = [];
    for (i = 0; i < out.length; i++) {
      var c = out[i], drop = false;
      if (!c.native) {
        for (var j = 0; j < out.length && !drop; j++) if (j !== i && c.el.contains(out[j].el)) drop = true;
      } else if (!c.card && PF.cards) {
        var host = c.el.parentElement && c.el.parentElement.closest(PF.cards);
        if (host) drop = true;
      }
      if (!drop && c.native && c.el.tagName !== 'IFRAME' && isScrollHolder(c.el)) drop = true;
      if (!drop && c.native && /^(DIV|SECTION|MAIN|ARTICLE|UL)$/.test(c.el.tagName) && !c.card) {
        var br = c.el.getBoundingClientRect(); // a page-sized wrapper with a tabindex is not a control
        if (br.width > root.innerWidth * 0.9 && br.height > root.innerHeight * 0.9) drop = true;
      }
      if (!drop) res.push(c);
    }
    assignRows(res, PF);
    cache.list = res;
    cache.dirty = false;
    cache.builtAt = root.performance.now();
    lastRebuildCost = cache.builtAt - t0;
    TV.stats.rebuilds++;
    TV.stats.lastBuildMs = Math.round(lastRebuildCost);
    detectModal(PF);
    repairFocus();
    if (!inFrame && !userMoved && ((!current && !activeEl()) || fallbackFocus)) autoFirstFocus(); // content arrived after the first attempts
  }

  /** The page re-rendered the focused element (a rotating hero, a React key change): keep the highlight alive. */
  function repairFocus() {
    if (inFrame || !current || current.isConnected || activeEl()) return;
    var was = lastRect;
    current = null;
    if (!userMoved || !was) { focusFirst(); return; }
    var items = candItems(null), best = null, bd = Infinity;
    var cx = (was.l + was.r) / 2, cy = (was.t + was.b) / 2;
    for (var i = 0; i < items.length; i++) {
      if (items[i].fix) continue;
      var r = items[i].rect, d = Math.abs((r.l + r.r) / 2 - cx) + Math.abs((r.t + r.b) / 2 - cy);
      if (d < bd) { bd = d; best = items[i]; }
    }
    if (best) setCurrent(best.el, null);
  }

  function assignRows(list, PF) {
    var rowEls = new Map(), n = 0, i;
    if (PF.rows) {
      var rs = doc.querySelectorAll(PF.rows);
      for (i = 0; i < rs.length; i++) rowEls.set(rs[i], 'p' + (n++));
    }
    var scrollers = new Map();
    for (i = 0; i < list.length; i++) {
      var el = list[i].el, rid = null, p = el, depth = 0;
      while (p && depth++ < 14) {
        if (rowEls.has(p) && p !== el) { rid = rowEls.get(p); break; }
        p = p.parentElement;
      }
      if (rid == null) {
        var sc = horizontalScroller(el);
        if (sc) {
          if (!scrollers.has(sc)) scrollers.set(sc, { id: 'a' + (n++), count: 0 });
          var s = scrollers.get(sc);
          s.count++;
          list[i].auto = s;
        }
      }
      list[i].row = rid;
    }
    for (i = 0; i < list.length; i++) if (list[i].row == null && list[i].auto && list[i].auto.count >= 3) list[i].row = list[i].auto.id;
  }

  function scheduleRebuild(delay) {
    if (rebuildTimer) return;
    var wait = delay != null ? delay : Math.max(250, lastRebuildCost * 6);
    rebuildTimer = setTimeout(function () {
      rebuildTimer = 0;
      if (doc.hidden) { cache.dirty = true; return; }
      try { buildCache(); } catch (e) { cache.dirty = true; }
    }, wait);
  }

  function ensureCache() {
    if (cache.dirty && (!cache.list.length || root.performance.now() - cache.builtAt > 3000)) buildCache();
  }

  /** Cheap per-candidate validation on the key path (the cache may be stale). */
  function usable(el, rect) {
    if (!el.isConnected) return false;
    var w = rect.r - rect.l, h = rect.b - rect.t;
    if (w < 14 || h < 14) return false;
    var cs = root.getComputedStyle(el);
    return P.renderable({
      w: w, h: h, display: cs.display, visibility: cs.visibility, pointerEvents: cs.pointerEvents, opacity: parseFloat(cs.opacity),
      disabled: !!el.disabled, hiddenAncestor: !!el.closest('[aria-hidden=true],[hidden],[inert]'), closedDetails: closedDetails(el)
    }, 14);
  }

  // ---------- modal layers ----------

  function detectModal(PF) {
    var vw = root.innerWidth, vh = root.innerHeight, m = null;
    var t = doc.elementFromPoint(vw / 2, vh / 2);
    var p = t, n = 0;
    while (p && p !== doc.body && p !== doc.documentElement && n++ < 12) {
      var cs = root.getComputedStyle(p);
      if (cs.position === 'fixed') {
        var r = p.getBoundingClientRect();
        var z = parseInt(cs.zIndex, 10);
        if (r.width * r.height >= vw * vh * 0.5 && z >= 50 && !(p.tagName === 'IFRAME') && !(PF.header && p.matches(PF.header))
            && !p.querySelector('video') && p.querySelector('a[href],button,input,[role=button]')) m = p;
      }
      p = p.parentElement;
    }
    if (m !== cache.modal) {
      var was = cache.modal;
      cache.modal = m;
      if (m && !was) { beforeModal = current; setTimeout(function () { if (cache.modal === m) focusFirst(m); }, 120); }
      else if (!m && was && beforeModal && beforeModal.isConnected) { var b = beforeModal; beforeModal = null; setCurrent(b, null); }
    }
  }

  // ---------- focus ----------

  function activeEl() {
    var a = doc.activeElement;
    if (!a || a === doc.body || a === doc.documentElement || !a.isConnected) return null;
    return a;
  }

  function fire(el, type) {
    try { el.dispatchEvent(new MouseEvent(type, { bubbles: true, cancelable: true, view: root })); } catch (e) { /* ignore */ }
  }

  function clearRing(el) {
    if (el && el.classList) { el.classList.remove('movy-tvf'); el.classList.remove('movy-tvr'); }
  }

  function setCurrent(el, dir) {
    try { TV.cause = String(new Error().stack).split('at ').slice(2, 5).map(function (l) { return l.split(' ')[0]; }).join('<'); } catch (e) { /* ignore */ }
    if (current && current !== el) { clearRing(current); fire(current, 'mouseout'); }
    current = el;
    if (!el) return;
    el.classList.add('movy-tvf');
    try {
      var rad = root.getComputedStyle(el).borderTopLeftRadius;
      if ((rad === '0px' || !rad) && el.getBoundingClientRect().height > 36) el.classList.add('movy-tvr');
    } catch (e) { /* ignore */ }
    if (!el.matches('a,button,input,select,textarea,summary,iframe,[tabindex],[contenteditable]')) el.setAttribute('tabindex', '-1');
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    fire(el, 'mouseover');
    fire(el, 'mousemove');
    var r = el.getBoundingClientRect();
    lastRect = toRect(r);
    var dx = reveal(el, r);
    colX = P.nextColumn(colX, lastRect, dir);
    if ((dir === 'left' || dir === 'right') && dx) colX -= dx; // the card slides while its row scrolls
  }

  function topInset() {
    var PF = TV.PF, inset = 0;
    if (!PF || !PF.header) return 0;
    var hs = doc.querySelectorAll(PF.header);
    for (var i = 0; i < hs.length && i < 6; i++) {
      var r = hs[i].getBoundingClientRect();
      if (r.height > 0 && r.top <= 2 && r.bottom < root.innerHeight * 0.4) inset = Math.max(inset, r.bottom);
    }
    return inset;
  }

  /** Scrolls the row (horizontally) and the page (vertically) so the focused card is fully visible with a peek. */
  function reveal(el, r) {
    var vw = root.innerWidth, vh = root.innerHeight, shiftX = 0;
    var sc = horizontalScroller(el);
    if (sc) {
      var sr = sc.getBoundingClientRect();
      var d = P.rowScrollDelta({ l: r.left, r: r.right }, { l: Math.max(sr.left, 0), r: Math.min(sr.right, vw) }, Math.min(56, r.width * 0.25));
      if (d) { shiftX = d; try { sc.scrollBy({ left: d, behavior: 'smooth' }); } catch (e) { sc.scrollLeft += d; } }
    } else if (r.left < 0 || r.right > vw) {
      shiftX = r.left < 0 ? r.left - 24 : r.right - vw + 24;
      try { root.scrollBy({ left: shiftX, behavior: 'smooth' }); } catch (e) { /* ignore */ }
    }
    var inHeader = !!(TV.PF && TV.PF.header && el.closest(TV.PF.header));
    var top = inHeader ? 0 : Math.max(Math.min(90, vh * 0.12), topInset() + 14), bottom = vh - Math.min(90, vh * 0.12);
    var dy = 0;
    if (r.height > bottom - top) dy = r.top - top;
    else if (r.top < top) dy = r.top - (vh - r.height) / 2;
    else if (r.bottom > bottom) dy = r.bottom - (vh + r.height) / 2;
    if (Math.abs(dy) > 2) { try { root.scrollBy({ top: dy, behavior: 'smooth' }); } catch (e) { root.scrollBy(0, dy); } }
    return shiftX;
  }

  function currentRect() {
    var a = activeEl() || (current && current.isConnected ? current : null);
    if (a) {
      var r = a.getBoundingClientRect();
      if (r.width > 0 && r.height > 0) { lastRect = toRect(r); return lastRect; }
    }
    return lastRect;
  }

  function firstFocusEl(scope) {
    var PF = TV.PF || compile(null), i, list, el;
    var sels = PF.firstFocus;
    for (i = 0; i < sels.length; i++) {
      if (!okSel(sels[i])) continue;
      list = (scope || doc).querySelectorAll(sels[i]);
      for (var j = 0; j < list.length; j++) {
        el = list[j];
        var r = el.getBoundingClientRect();
        if (r.width >= 14 && r.height >= 14 && usable(el, toRect(r)) && !isSkipped(el, PF)) return el;
      }
    }
    return null;
  }

  /** First focus: profile firstFocus, else the first card of the first row, else the first non-header candidate. */
  function focusFirst(scope, allowHeader) {
    ensureCache();
    var PF = TV.PF || compile(null);
    var el = firstFocusEl(scope);
    var viaProfile = !!el;
    if (!el && scope && PF.playButtons) { // a modal: its Play button first
      var pbs = scope.querySelectorAll(PF.playButtons);
      for (var q = 0; q < pbs.length && !el; q++) if (renderableNow(pbs[q])) el = pbs[q];
    }
    if (!el) {
      var items = candItems(scope);
      var isBar = function (c) { return c.fix || (PF.header && c.el.closest(PF.header)); };
      var inRow = items.filter(function (c) { return c.row != null && !isBar(c); });
      var pool = inRow.length ? inRow : items.filter(function (c) { return !isBar(c); });
      TV.ff = { items: items.length, inRow: inRow.length, pool: pool.length, hdr: PF.header, ff: PF.firstFocus.length };
      if (!pool.length) { if (!allowHeader) return false; pool = items; } // auto first focus waits for content rather than landing on the header
      var best = P.pickInitial(pool, root.innerWidth, root.innerHeight) || (allowHeader ? pool[0] : null);
      el = best && best.el;
      if (!el && !scope) { var bf = bigIframe(); if (bf) el = bf; }
    }
    if (!el) return false;
    colX = null;
    fallbackFocus = !viaProfile && !scope;
    setCurrent(el, null);
    return true;
  }

  var FIX_SHIFT = 1e6;
  function shiftFixed(rect, fix) {
    if (!fix) return rect;
    var d = fix * FIX_SHIFT;
    return { l: rect.l, t: rect.t + d, r: rect.r, b: rect.b + d };
  }

  /** Cached candidates with fresh rects, near the viewport, restricted to the modal layer when one is up. */
  function candItems(scope) {
    var out = [], list = cache.list, modal = scope || cache.modal;
    for (var i = 0; i < list.length; i++) {
      var c = list[i], el = c.el;
      if (!el.isConnected) { cache.dirty = true; continue; }
      if (modal && !modal.contains(el)) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 14 || r.height < 14 || !inView(r, 500, root.innerHeight * 1.6)) continue;
      out.push({ id: idOf(el), el: el, rect: shiftFixed(toRect(r), c.fix), row: c.row, native: c.native, fix: c.fix });
    }
    return out;
  }

  function move(dir) {
    ensureCache();
    var cur = activeEl() || (current && current.isConnected && current.classList.contains('movy-tvf') ? current : null);
    var from = currentRect();
    if (!cur || !from) return focusFirst(null, true);
    var items = candItems(null);
    var curId = idOf(cur);
    var hasCur = false, i;
    for (i = 0; i < items.length; i++) if (items[i].id === curId) { hasCur = true; break; }
    var curFix = fixOf(cur, TV.PF || compile(null));
    if (!hasCur) items.push({ id: curId, el: cur, rect: shiftFixed(from, curFix), row: rowOfEl(cur), native: true, fix: curFix });
    from = shiftFixed(from, curFix);
    var best = null, tries = 0;
    while (tries++ < 6) {
      var lines = P.buildLines(items);
      var vert = dir === 'up' || dir === 'down';
      best = vert ? P.pickVertical(lines, curId, dir, colX == null ? (from.l + from.r) / 2 : colX, root.innerWidth)
        : P.pickHorizontal(lines, curId, dir, from, items);
      if (!best) break;
      if (usable(best.el, best.rect)) break;
      cache.dirty = true;
      items = items.filter(function (c) { return c !== best; });
      best = null;
    }
    if (best && best.el !== cur && !best.el.contains(cur) && !cur.contains(best.el)) { setCurrent(best.el, dir); return true; }
    if (best && best.el !== cur) { setCurrent(best.el, dir); return true; }
    return false;
  }

  function rowOfEl(el) {
    for (var i = 0; i < cache.list.length; i++) if (cache.list[i].el === el) return cache.list[i].row;
    return null;
  }

  function scrollPage(dir) {
    var dy = dir === 'down' ? 1 : dir === 'up' ? -1 : 0;
    var dx = dir === 'right' ? 1 : dir === 'left' ? -1 : 0;
    root.scrollBy({ top: dy * root.innerHeight * 0.6, left: dx * root.innerWidth * 0.6, behavior: 'smooth' });
  }

  /** A player iframe covering most of the page (cross-origin players live in these). */
  function bigIframe() {
    var frames = doc.getElementsByTagName('iframe');
    var best = null, bestArea = 0;
    for (var i = 0; i < frames.length; i++) {
      var r = frames[i].getBoundingClientRect();
      var area = r.width * r.height;
      if (area > root.innerWidth * root.innerHeight * 0.5 && area > bestArea) { best = frames[i]; bestArea = area; }
    }
    return best;
  }

  function isPlayerFrame(el, PF) {
    if (PF.player.frame && el.matches(PF.player.frame)) return true;
    if (PF.player.frame && el.closest(PF.player.frame)) return true;
    var r = el.getBoundingClientRect();
    return r.width * r.height > root.innerWidth * root.innerHeight * 0.5;
  }

  // ---------- keys ----------

  function dirOf(e) {
    switch (e.keyCode) {
      case 37: return 'left';
      case 38: return 'up';
      case 39: return 'right';
      case 40: return 'down';
      default: return null;
    }
  }

  function isTextField(el) {
    if (!el || el === doc.body) return false;
    var t = el.tagName;
    if (t === 'TEXTAREA') return true;
    if (t === 'INPUT') return TEXT_INPUT.test(el.type || 'text');
    return !!el.isContentEditable;
  }

  function caretAtEdge(el, dir) {
    if (!/^(INPUT|TEXTAREA)$/.test(el.tagName)) return true;
    try {
      var s = el.selectionStart, e = el.selectionEnd, n = (el.value || '').length;
      if (s == null) return true;
      return dir === 'left' ? (s === 0 && e === 0) : (s === n && e === n);
    } catch (x) { return true; }
  }

  function renderableNow(el) {
    if (!el.isConnected) return false;
    var r = el.getBoundingClientRect();
    return usable(el, toRect(r));
  }

  function activate(el) {
    if (!renderableNow(el)) { var f = firstFocusEl(); if (f) { setCurrent(f, null); el = f; } } // stale hero slide
    if (TV.hooks.beforeActivate) TV.hooks.beforeActivate(el);
    if (el.tagName === 'IFRAME') {
      try { el.focus(); el.contentWindow.focus(); } catch (e) { /* cross-origin focus is fine */ }
      enterFrame(el, null);
      return true;
    }
    if (isTextField(el)) { el.focus(); fire(el, 'click'); return false; }
    var type = (el.type || '').toLowerCase();
    if (el.tagName === 'SELECT' || el.tagName === 'TEXTAREA') return false;
    if (el.tagName === 'INPUT' && type === 'range') return false;
    el.click();
    if (TV.hooks.afterActivate) TV.hooks.afterActivate(el);
    return true;
  }

  function enterFrame(el, dir) {
    try { el.contentWindow.postMessage({ __movyTvEnter: dir || 'down' }, '*'); } catch (e) { /* ignore */ }
  }

  function exitUp(dir) {
    try { root.parent.postMessage({ __movyTvExit: dir }, '*'); } catch (x) { /* ignore */ }
  }

  function record(e, t0, label) {
    var now = root.performance.now();
    var sync = now - t0;
    var lat = e && e.timeStamp ? t0 - e.timeStamp : 0;
    root.requestAnimationFrame(function () {
      root.requestAnimationFrame(function () {
        var rec = { dir: label, sync: Math.round(sync * 10) / 10, lat: Math.round(lat * 10) / 10, k2p: Math.round((root.performance.now() - (e && e.timeStamp ? e.timeStamp : t0)) * 10) / 10 };
        TV.timings.push(rec);
        if (TV.timings.length > 80) TV.timings.shift();
      });
    });
  }

  function onKey(e) {
    if (e.defaultPrevented || e.ctrlKey || e.metaKey || e.altKey) return;
    var t0 = root.performance.now();
    var dir = dirOf(e);
    var a = activeEl();
    if (TV.hooks.key && TV.hooks.key(e, dir, a)) return;

    if (dir) {
      if (a && isTextField(a) && (dir === 'left' || dir === 'right') && !caretAtEdge(a, dir)) return; // caret moves
      if (a && a.tagName === 'SELECT') return;
      e.preventDefault();
      e.stopPropagation();
      userMoved = true;
      fallbackFocus = false;
      if (dir === 'up' || dir === 'down') { if (colX == null && lastRect) colX = (lastRect.l + lastRect.r) / 2; }
      var moved = move(dir);
      record(e, t0, dir + (moved ? '' : '!'));
      if (!moved) {
        if (inFrame) exitUp(dir);
        else if (dir === 'up' || dir === 'down') scrollPage(dir);
        else if (dir === 'right' && !e.repeat) {
          try { if (root.MovyTvNative) root.MovyTvNative.edgeRight(); } catch (x) { /* ignore */ }
        }
      } else if (current && current.tagName === 'IFRAME' && TV.PF && isPlayerFrame(current, TV.PF)) {
        enterFrame(current, dir);
      }
      return;
    }

    if (e.keyCode === 13 || e.keyCode === 23) {
      if (a && a !== doc.body) {
        if (e.repeat) { e.preventDefault(); return; }
        if (activate(a)) { e.preventDefault(); e.stopPropagation(); }
        return;
      }
      var big = bigIframe();
      if (big) { e.preventDefault(); e.stopPropagation(); setCurrent(big, null); activate(big); return; }
      if (!e.repeat && !inFrame) {
        var ff = firstFocusEl();
        if (ff) { e.preventDefault(); e.stopPropagation(); setCurrent(ff, null); activate(ff); return; }
      }
      if (!e.repeat && inFrame) {
        var t = doc.elementFromPoint(root.innerWidth / 2, root.innerHeight / 2);
        if (t) { e.preventDefault(); t.click(); }
      }
    }
  }

  root.addEventListener('keydown', onKey, true);

  // ---------- frames ----------

  root.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.__movyTvEnter && e.source === root.parent) {
      root.focus();
      ensureCache();
      var items = candItems(null);
      var best = P.pickEntry(items, d.__movyTvEnter);
      if (best) { colX = null; setCurrent(best.el, null); }
      return;
    }
    if (d.__movyTvExit) {
      var frames = doc.getElementsByTagName('iframe');
      for (var i = 0; i < frames.length; i++) {
        if (frames[i].contentWindow !== e.source) continue;
        root.focus();
        frames[i].blur();
        var r = toRect(frames[i].getBoundingClientRect());
        var items2 = candItems(null).filter(function (c) { return c.el !== frames[i]; });
        var best2 = P.pick(r, items2, d.__movyTvExit);
        if (best2) setCurrent(best2.el, d.__movyTvExit); else setCurrent(frames[i], null);
        return;
      }
    }
    if (d.__movyTvProfile && e.source === root.parent) { setProfile(d.__movyTvProfile); relayDown(d); }
  });

  function relayDown(msg) {
    var frames = doc.getElementsByTagName('iframe');
    for (var i = 0; i < frames.length; i++) {
      try { frames[i].contentWindow.postMessage(msg, '*'); } catch (e) { /* ignore */ }
    }
  }

  // ---------- lifecycle ----------

  var lastHref = '';
  var firstFocusTries = 0;

  function autoFirstFocus() {
    if (inFrame || userMoved || cache.modal) return;
    var a = activeEl();
    if (a && a !== current) return; // the site (or a text field) owns the focus
    var ff = firstFocusEl();
    if (ff && ff !== current) { colX = null; fallbackFocus = false; setCurrent(ff, null); return; } // the hero Play showed up after a fallback focus
    if (a) return;
    if (focusFirst()) return;
    if (++firstFocusTries < 40) setTimeout(autoFirstFocus, 600);
  }

  function onRoute() {
    lastHref = root.location.href;
    userMoved = false;
    firstFocusTries = 0;
    clearRing(current);
    current = null;
    colX = null;
    cache.dirty = true;
    scheduleRebuild(250);
    [350, 900, 1800, 3200].forEach(function (ms) { setTimeout(autoFirstFocus, ms); });
  }

  function watch() {
    if (!doc.documentElement) { setTimeout(watch, 20); return; }
    var mo = new MutationObserver(function () { cache.dirty = true; scheduleRebuild(); });
    mo.observe(doc.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['open', 'hidden', 'aria-hidden', 'tabindex'] });
    lastHref = root.location.href;
    if (!inFrame) {
      setInterval(function () { if (root.location.href !== lastHref) onRoute(); }, 600);
      ['pushState', 'replaceState'].forEach(function (k) {
        var orig = root.history[k];
        root.history[k] = function () { var r = orig.apply(this, arguments); setTimeout(function () { if (root.location.href !== lastHref) onRoute(); }, 0); return r; };
      });
    }
    root.addEventListener('scroll', function () { cache.dirty = true; scheduleRebuild(450); }, { capture: true, passive: true });
    doc.addEventListener('DOMContentLoaded', function () { scheduleRebuild(100); setTimeout(autoFirstFocus, 400); });
    root.addEventListener('load', function () { scheduleRebuild(100); setTimeout(autoFirstFocus, 300); [900, 2000, 3500].forEach(function (ms) { setTimeout(autoFirstFocus, ms); }); });
  }

  // The ring follows real focus: it is removed as soon as the element loses it (fullscreen changes, re-renders).
  doc.addEventListener('focusout', function (e) { clearRing(e.target); }, true);

  function installStyle() {
    if (doc.getElementById('__movy_tv_style')) return;
    var st = doc.createElement('style');
    st.id = '__movy_tv_style';
    st.textContent =
      '.movy-tvf,.movy-tvf:focus{outline:3px solid #fff!important;outline-offset:3px!important;' +
      'box-shadow:0 0 0 6px rgba(10,132,255,.6),0 0 18px 4px rgba(255,255,255,.22)!important;' +
      'scale:1.08!important;transition:scale .15s ease-out!important;z-index:2147483000!important}' +
      '.movy-tvr{border-radius:16px!important}' +
      '*:focus{outline:3px solid #fff!important;outline-offset:3px!important}' +
      'html{scroll-behavior:smooth}';
    (doc.head || doc.documentElement).appendChild(st);
  }
  doc.addEventListener('DOMContentLoaded', installStyle);
  installStyle();

  // ---------- public ----------

  TV.move = move;
  TV.collect = function () { ensureCache(); return candItems(null); };
  TV.focusFirst = focusFirst;
  TV.autoFirstFocus = autoFirstFocus;
  TV.reloadProfile = function () {
    if (!loadProfile()) return false;
    if (!inFrame) relayDown({ __movyTvProfile: TV.PF.raw });
    return true;
  };
  TV.setCurrent = setCurrent;
  TV.activeEl = activeEl;
  TV.isPlayerFrame = isPlayerFrame;
  TV.bigIframe = bigIframe;
  TV.relayDown = relayDown;
  TV.okSel = okSel;
  TV.score = P.score;
  TV.pick = P.pick;
  TV.stats.cacheSize = function () { return cache.list.length; };
  TV.debug = function () {
    var a = activeEl(), r = a && a.getBoundingClientRect();
    return { cur: a && (a.tagName + '.' + (a.className || '').toString().slice(0, 50) + ' "' + (a.innerText || a.getAttribute('aria-label') || '').trim().slice(0, 30) + '"'),
      rect: r && [Math.round(r.left), Math.round(r.top), Math.round(r.width), Math.round(r.height)], colX: colX && Math.round(colX), cands: cache.list.length,
      rows: cache.list.filter(function (c) { return c.row != null; }).length, modal: !!cache.modal, userMoved: userMoved, cause: TV.cause, ff: TV.ff, sy: Math.round(root.scrollY),
      t: TV.timings.slice(-4) };
  };

  loadProfile();
  watch();
})(typeof window !== 'undefined' ? window : {});
