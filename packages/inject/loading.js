// Movy video buffering overlay. Dependency-free; runs at document start in every frame
// (Android: WebViewCompat.addDocumentStartJavaScript, Electron: preload via contextBridge-free main-world injection).
// One thin red rotating arc per <video>, shown after a 120 ms debounce while it is loading/buffering/seeking.
(function () {
  if (window.__movyLoading) return;
  window.__movyLoading = true;

  var RED = '#E50914';
  var SIZE = 48;
  var DEBOUNCE_MS = 120;
  var FADE_MS = 180;
  var SHOW = { loadstart: 1, waiting: 1, stalled: 1, seeking: 1 };
  var HIDE = { playing: 1, canplay: 1, seeked: 1, pause: 1, error: 1, ended: 1, emptied: 1 };

  var states = new WeakMap(); // video -> { el, timer, wanted, shown }
  var visible = []; // states currently shown or fading in
  var rafId = 0;

  function makeEl() {
    var ns = 'http://www.w3.org/2000/svg';
    var el = document.createElement('div');
    var s = el.style;
    s.cssText = 'position:fixed;left:0;top:0;width:' + SIZE + 'px;height:' + SIZE + 'px;margin:0;padding:0;border:0;' +
      'z-index:2147483647;pointer-events:none;opacity:0;transition:opacity ' + FADE_MS + 'ms ease;contain:strict;';
    var svg = document.createElementNS(ns, 'svg');
    svg.setAttribute('viewBox', '0 0 48 48');
    svg.setAttribute('width', String(SIZE));
    svg.setAttribute('height', String(SIZE));
    var c = document.createElementNS(ns, 'circle');
    c.setAttribute('cx', '24');
    c.setAttribute('cy', '24');
    c.setAttribute('r', '20');
    c.setAttribute('fill', 'none');
    c.setAttribute('stroke', RED);
    c.setAttribute('stroke-width', '3');
    c.setAttribute('stroke-linecap', 'round');
    c.setAttribute('stroke-dasharray', '38 90');
    svg.appendChild(c);
    el.appendChild(svg);
    try {
      svg.animate([{ transform: 'rotate(0deg)' }, { transform: 'rotate(360deg)' }], { duration: 900, iterations: Infinity });
    } catch (e) { /* no Web Animations: static arc */ }
    return el;
  }

  function stateOf(v) {
    var st = states.get(v);
    if (!st) {
      st = { v: v, el: null, timer: 0, shown: false, hideTimer: 0 };
      states.set(v, st);
    }
    return st;
  }

  function host() {
    return document.fullscreenElement || document.webkitFullscreenElement || document.documentElement;
  }

  function place(st) {
    var r = st.v.getBoundingClientRect();
    var ok = r.width > 40 && r.height > 40 && st.v.isConnected;
    st.el.style.visibility = ok ? 'visible' : 'hidden';
    if (ok) {
      st.el.style.transform = 'translate(' + Math.round(r.left + r.width / 2 - SIZE / 2) + 'px,' +
        Math.round(r.top + r.height / 2 - SIZE / 2) + 'px)';
    }
  }

  function tick() {
    rafId = 0;
    for (var i = visible.length - 1; i >= 0; i--) {
      var st = visible[i];
      // Self-heal: playback data available again but no event reached us.
      if (st.shown && st.v.readyState >= 3 && !st.v.seeking) { hide(st.v); continue; }
      var h = host();
      if (st.el.parentNode !== h) h.appendChild(st.el);
      place(st);
    }
    if (visible.length) schedule();
  }

  function schedule() {
    if (!rafId) rafId = requestAnimationFrame(tick);
  }

  function show(v) {
    var st = stateOf(v);
    if (st.shown || st.timer) return;
    st.timer = setTimeout(function () {
      st.timer = 0;
      if (v.readyState >= 3 && !v.seeking) return;
      if (!st.el) st.el = makeEl();
      clearTimeout(st.hideTimer);
      var h = host();
      if (st.el.parentNode !== h) h.appendChild(st.el);
      place(st);
      void st.el.offsetWidth; // start the fade from opacity 0
      st.el.style.opacity = '1';
      st.shown = true;
      if (visible.indexOf(st) < 0) visible.push(st);
      schedule();
    }, DEBOUNCE_MS);
  }

  function hide(v) {
    var st = states.get(v);
    if (!st) return;
    clearTimeout(st.timer);
    st.timer = 0;
    if (!st.shown) return;
    st.shown = false;
    st.el.style.opacity = '0';
    var i = visible.indexOf(st);
    if (i >= 0) visible.splice(i, 1);
    st.hideTimer = setTimeout(function () {
      if (!st.shown && st.el && st.el.parentNode) st.el.parentNode.removeChild(st.el);
    }, FADE_MS + 40);
  }

  // Media events do not bubble, but they do pass the capture phase, so one document listener covers
  // videos added later and videos inside shadow-less subtrees.
  function onMedia(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    if (SHOW[e.type]) {
      if (e.type === 'loadstart' && v.preload === 'none' && v.paused && !v.autoplay) return;
      show(v);
    } else if (HIDE[e.type]) {
      hide(v);
    }
  }
  var types = Object.keys(SHOW).concat(Object.keys(HIDE));
  for (var i = 0; i < types.length; i++) document.addEventListener(types[i], onMedia, true);

  // Fullscreen guard. On Android WebView (some GPUs/drivers) a video that is the only thing on screen gets promoted to a
  // hardware overlay that sits behind the window; with our native fullscreen host it renders black (audio keeps playing)
  // as soon as the player controls auto-hide. A near-invisible layer above the video keeps it composited in the page.
  var guard = null;
  function guardHost() {
    var h = document.fullscreenElement || document.webkitFullscreenElement;
    var t = h && h.tagName;
    return h && t !== "VIDEO" && t !== "IFRAME" && t !== "EMBED" && t !== "OBJECT" ? h : null;
  }
  function syncGuard() {
    var h = guardHost();
    if (!h) {
      if (guard && guard.parentNode) guard.parentNode.removeChild(guard);
      return;
    }
    if (!guard) {
      guard = document.createElement("div");
      var s = guard.style;
      s.setProperty("position", "fixed");
      s.setProperty("left", "0");
      s.setProperty("top", "0");
      s.setProperty("right", "0");
      s.setProperty("bottom", "0");
      s.setProperty("z-index", "2147483647");
      s.setProperty("pointer-events", "none");
      s.setProperty("background", "rgba(0,0,0,0.012)");
    }
    if (guard.parentNode !== h) h.appendChild(guard);
  }
  document.addEventListener("fullscreenchange", syncGuard);
  document.addEventListener("webkitfullscreenchange", syncGuard);
  setInterval(function () { if (guard) syncGuard(); else if (guardHost()) syncGuard(); }, 1000); // re-attach if the page re-rendered its tree

  window.addEventListener('scroll', schedule, true);
  window.addEventListener('resize', schedule);
  document.addEventListener('fullscreenchange', schedule);
  document.addEventListener('webkitfullscreenchange', schedule);
})();
