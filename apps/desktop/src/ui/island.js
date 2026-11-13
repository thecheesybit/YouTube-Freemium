'use strict';
// Island renderer: a floating capsule (red, wiggles every 10 s) that morphs with spring physics into a black squircle
// panel with one gradient tile per source and a detached settings bubble. It renders main's state snapshot only.
(function () {
  var h = UI.h;
  var play = UI.play;
  var T = UI.T;
  document.documentElement.style.setProperty('--spring-soft', T.soft.css);

  var CAPSULE = { w: 7, h: 64, r: 3.5, inset: 6 };
  var PANEL_W = 84;
  var PANEL_R = 30;
  var TOP_PAD = 12;
  var BUBBLE = 44;
  var BUBBLE_GAP = 10;
  var BOTTOM_PAD = 14;
  var NUDGE_EVERY_MS = 10000;
  var NUDGE_MS = 520;
  var NUDGE_DP = 3.5;
  var IDLE_COLLAPSE_MS = 4000;
  var STAGGER_MS = 25;
  var BUBBLE_DELAY_MS = 70;

  var pill = document.getElementById('pill');
  var tilesEl = document.getElementById('tiles');
  var bubble = document.getElementById('bubble');
  var ring = h('div#ring');

  var state = null;
  var mode = 'hidden'; // hidden | collapsed | expanded
  var busy = Promise.resolve();
  var signature = '';
  var idleTimer = null;
  var hovered = false;
  var pressed = false;
  var panelH = 300;

  function queue(fn) {
    busy = busy.then(fn).catch(function () {});
    return busy;
  }

  // ---- rendering ----
  function build(sources) {
    tilesEl.textContent = '';
    tilesEl.appendChild(ring);
    sources.forEach(function (s) {
      var btn = h('button.src', { 'data-id': s.id, 'aria-label': s.name, tabindex: '-1', onclick: function () { pick(s.id); } },
        h('span.face', null, UI.tile(s, 44, true)),
        h('span.name', { text: s.name }));
      tilesEl.appendChild(btn);
    });
  }

  function paintState() {
    if (!state) return;
    var sig = state.sources.map(function (s) { return s.id + s.name + s.accent + s.mono; }).join('|');
    if (sig !== signature) {
      signature = sig;
      build(state.sources);
      if (mode === 'expanded') showTilesNow();
    }
    state.sources.forEach(function (s) {
      var el = tilesEl.querySelector('[data-id="' + s.id + '"]');
      if (!el) return;
      el.classList.toggle('active', s.id === state.activeId);
      var dot = el.querySelector('.dot');
      dot.className = 'dot h-' + s.health;
    });
    measure();
    moveRing(true);
  }

  function measure() {
    panelH = tilesEl.offsetHeight;
  }

  function moveRing(animate) {
    var src = tilesEl.querySelector('.src.active');
    if (!src) { ring.style.opacity = '0'; return; }
    var to = 'translate(' + (src.offsetLeft + (src.offsetWidth - 44) / 2 - 5) + 'px,' + (src.offsetTop - 5) + 'px)';
    if (animate && ring.style.transform) ring.animate([{ transform: ring.style.transform }, { transform: to }], { duration: T.soft.duration, easing: T.soft.css });
    ring.style.transform = to;
    ring.style.opacity = mode === 'expanded' ? '1' : '0';
  }

  // ---- geometry ----
  function viewH() { return window.innerHeight; }
  function capsuleTop() { return Math.round((viewH() - CAPSULE.h) / 2); }
  function frame(o) {
    return {
      width: o.w + 'px', height: o.h + 'px', top: o.top + 'px', borderRadius: o.r + 'px', backgroundColor: o.bg, opacity: o.opacity === undefined ? 1 : o.opacity,
    };
  }
  function capsuleFrame() { return frame({ w: CAPSULE.w, h: CAPSULE.h, top: capsuleTop(), r: CAPSULE.r, bg: '#e50914' }); }
  function panelFrame() { return frame({ w: PANEL_W, h: panelH, top: TOP_PAD, r: PANEL_R, bg: '#000000' }); }
  function sliverFrame() { return frame({ w: 2, h: CAPSULE.h, top: capsuleTop(), r: 1, bg: '#e50914', opacity: 0 }); }

  function applyFrame(el, f) { Object.keys(f).forEach(function (k) { el.style[k] = f[k]; }); }

  // ---- state machine ----
  function setShown(shown) {
    return queue(function () {
      if (shown && mode === 'hidden') return show();
      if (!shown && mode !== 'hidden') return hide();
    });
  }

  function show() {
    return window.movy.islandView('collapsed').then(function () {
      mode = 'collapsed';
      pill.classList.remove('expanded');
      applyFrame(pill, sliverFrame());
      pill.classList.remove('hidden');
      return play(pill, [sliverFrame(), capsuleFrame()], T.expand).then(scheduleNudge);
    });
  }

  function hide() {
    clearTimeout(idleTimer);
    var before = mode === 'expanded' ? collapseNow() : Promise.resolve();
    return before.then(function () {
      return play(pill, [capsuleFrame(), sliverFrame()], T.collapse, { duration: 220 });
    }).then(function () {
      mode = 'hidden';
      pill.classList.add('hidden');
      return window.movy.islandView('hidden');
    });
  }

  function expand() {
    if (mode !== 'collapsed') return Promise.resolve();
    return queue(function () {
      if (mode !== 'collapsed') return null;
      measure();
      var total = TOP_PAD + panelH + BUBBLE_GAP + BUBBLE + BOTTOM_PAD;
      return window.movy.islandView('expanded', total).then(function () {
        mode = 'expanded';
        pill.classList.add('expanded');
        window.movy.act('island-expanded', { on: true });
        bubble.style.top = TOP_PAD + panelH + BUBBLE_GAP + 'px';
        var from = frame({ w: CAPSULE.w, h: CAPSULE.h, top: Math.round((total - CAPSULE.h) / 2), r: CAPSULE.r, bg: '#e50914' });
        applyFrame(pill, from);
        moveRing(false);
        var morph = play(pill, [from, panelFrame()], T.expand);
        UI.wait(T.expand.duration * 0.45).then(showTiles);
        return morph.then(armIdle);
      });
    });
  }

  function showTiles() {
    var items = tilesEl.querySelectorAll('.src');
    play(tilesEl, [{ opacity: 0 }, { opacity: 1 }], T.soft, { duration: 160 });
    Array.prototype.forEach.call(items, function (el, i) {
      play(el, [{ opacity: 0, transform: 'scale(0.85)' }, { opacity: 1, transform: 'scale(1)' }], T.soft, { delay: i * STAGGER_MS });
    });
    ring.style.opacity = '1';
    bubble.classList.add('on');
    play(bubble, [{ opacity: 0, transform: 'scale(0.3)' }, { opacity: 1, transform: 'scale(1)' }], T.expand, { delay: BUBBLE_DELAY_MS + items.length * STAGGER_MS });
  }

  function showTilesNow() {
    tilesEl.style.opacity = '1';
    tilesEl.querySelectorAll('.src').forEach(function (el) { el.style.opacity = '1'; el.style.transform = 'scale(1)'; });
  }

  function collapse() {
    if (mode !== 'expanded') return Promise.resolve();
    return queue(function () { return mode === 'expanded' ? collapseNow() : null; });
  }

  function collapseNow() {
    clearTimeout(idleTimer);
    bubble.classList.remove('on');
    ring.style.opacity = '0';
    var total = viewH();
    var from = panelFrame();
    var to = frame({ w: CAPSULE.w, h: CAPSULE.h, top: Math.round((total - CAPSULE.h) / 2), r: CAPSULE.r, bg: '#e50914' });
    play(tilesEl, [{ opacity: 1 }, { opacity: 0 }], T.soft, { duration: 120 });
    tilesEl.querySelectorAll('.src').forEach(function (el) { el.style.opacity = '0'; el.style.transform = 'scale(0.85)'; });
    play(bubble, [{ opacity: 1, transform: 'scale(1)' }, { opacity: 0, transform: 'scale(0.3)' }], T.collapse, { duration: 160 });
    return UI.wait(90).then(function () { return play(pill, [from, to], T.collapse); }).then(function () {
      return window.movy.islandView('collapsed');
    }).then(function () {
      mode = 'collapsed';
      pill.classList.remove('expanded');
      applyFrame(pill, capsuleFrame());
      window.movy.act('island-expanded', { on: false });
      cooldownUntil = Date.now() + 500;
      scheduleNudge();
    });
  }

  function toggle() {
    if (mode === 'expanded') collapse();
    else if (mode === 'collapsed') expand();
    else queue(function () { return null; }).then(function () { if (mode === 'collapsed') expand(); });
  }

  function pick(id) {
    window.movy.act('pick', { id: id });
    UI.wait(380).then(collapse);
  }

  // ---- idle collapse + attention nudge ----
  function armIdle() {
    clearTimeout(idleTimer);
    if (mode !== 'expanded' || hovered) return;
    idleTimer = setTimeout(collapse, IDLE_COLLAPSE_MS);
  }

  var nudgeTimer = null;
  function scheduleNudge() {
    clearTimeout(nudgeTimer);
    nudgeTimer = setTimeout(nudge, NUDGE_EVERY_MS);
  }

  /** Three decaying swings (3.5 px, 0.5 s) of the collapsed capsule; skipped while expanded, hidden or touched. */
  function nudge() {
    scheduleNudge();
    if (mode !== 'collapsed' || hovered || pressed || busyDrag) return;
    var frames = [];
    for (var i = 0; i <= 26; i++) {
      var t = i / 26;
      var dx = t <= 0 || t >= 1 ? 0 : Math.sin(t * Math.PI * 6) * (1 - t) * NUDGE_DP;
      frames.push({ transform: 'translateX(' + dx.toFixed(2) + 'px)', offset: t });
    }
    pill.animate(frames, { duration: NUDGE_MS, easing: 'linear' });
  }

  // ---- pointer: tap or vertical drag on the capsule ----
  var busyDrag = false;
  var startY = 0;
  var dragging = false;
  pill.addEventListener('pointerdown', function (e) {
    if (mode !== 'collapsed') return;
    pressed = true;
    dragging = false;
    startY = e.screenY;
    pill.setPointerCapture(e.pointerId);
  });
  pill.addEventListener('pointermove', function (e) {
    if (!pressed || mode !== 'collapsed') return;
    var dy = e.screenY - startY;
    if (!dragging && Math.abs(dy) > 5) {
      dragging = true;
      busyDrag = true;
      window.movy.act('island-drag', { phase: 'start' });
    }
    if (dragging) window.movy.act('island-drag', { phase: 'move', dy: dy });
  });
  function endPointer(e) {
    if (!pressed) return;
    pressed = false;
    try { pill.releasePointerCapture(e.pointerId); } catch (x) { /* not captured */ }
    if (dragging) {
      window.movy.act('island-drag', { phase: 'end' });
      dragging = false;
      setTimeout(function () { busyDrag = false; }, 50);
    } else if (e.type === 'pointerup') {
      expand();
    }
  }
  pill.addEventListener('pointerup', endPointer);
  pill.addEventListener('pointercancel', endPointer);
  // Hover: ~120 ms intent on the capsule expands it; leaving the panel collapses it after a 600 ms grace period
  // (so the way to the gear bubble does not close it). Click and Ctrl+K still work.
  var HOVER_INTENT_MS = 120;
  var LEAVE_GRACE_MS = 600;
  var hoverTimer = null;
  var leaveTimer = null;
  var cooldownUntil = 0;
  document.documentElement.addEventListener('mouseenter', function () {
    hovered = true;
    clearTimeout(idleTimer);
    clearTimeout(leaveTimer);
    if (mode === 'collapsed' && !pressed && Date.now() > cooldownUntil) {
      clearTimeout(hoverTimer);
      hoverTimer = setTimeout(function () { if (hovered) expand(); }, HOVER_INTENT_MS);
    }
  });
  document.documentElement.addEventListener('mouseleave', function () {
    hovered = false;
    clearTimeout(hoverTimer);
    if (mode === 'expanded') {
      clearTimeout(leaveTimer);
      leaveTimer = setTimeout(function () { if (!hovered) collapse(); }, LEAVE_GRACE_MS);
    }
  });

  bubble.addEventListener('click', function () { window.movy.act('settings'); collapse(); });
  window.addEventListener('blur', function () { if (mode === 'expanded') collapse(); }); // click on the page
  document.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') collapse();
  });

  // ---- main -> view ----
  var pulseTimer = null;
  window.movy.onPush(function (msg) {
    if (msg.type === 'state') {
      state = msg.state;
      paintState();
      setShown(!!state.island.shown);
    } else if (msg.type === 'toggle') {
      toggle();
    } else if (msg.type === 'pulse') {
      var el = tilesEl.querySelector('[data-id="' + msg.id + '"]');
      if (!el) return;
      el.classList.add('pulse');
      clearTimeout(pulseTimer);
      pulseTimer = setTimeout(function () { el.classList.remove('pulse'); }, 3000);
      expand();
    }
  });

  window.movy.state().then(function (s) {
    if (!s) return;
    state = s;
    paintState();
    setShown(!!s.island.shown);
  });
})();
