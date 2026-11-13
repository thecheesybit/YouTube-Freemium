// Movy navigation guard. Dependency-free; runs at document start in every frame.
// Kills ad redirects at the script level: off-site window.open (stub window), off-site anchor clicks,
// invisible click-jack overlays (removed, click re-sent to what is underneath), meta refresh and
// target=_blank forms. Native code (NavRules) is the authority for real navigations; this layer keeps pages calm.
// Host lists come from window.MovyAdblock.navConfig() (Android) or window.MOVY_NAV (other shells).
(function () {
  if (window.__movyNavGuard) return;
  window.__movyNavGuard = true;

  var bridge = window.MovyAdblock;
  var cfg = null;
  var redispatching = false;

  function loadCfg() {
    if (cfg) return cfg;
    var raw = null;
    try {
      raw = window.MOVY_NAV || (bridge && bridge.navConfig && JSON.parse(bridge.navConfig()));
    } catch (e) { /* fall through */ }
    cfg = raw && raw.allow ? raw : { allow: [], auth: [], empty: true };
    return cfg;
  }

  function report(kind, target) {
    try { if (bridge && bridge.killed) bridge.killed(kind, String(target)); } catch (e) { /* ignore */ }
  }

  function hostMatches(pattern, host) {
    if (pattern.indexOf('*.') === 0) {
      var base = pattern.slice(2);
      return host === base || host.slice(-(base.length + 1)) === '.' + base;
    }
    return host === pattern;
  }

  function listHas(list, u, withPaths) {
    for (var i = 0; i < list.length; i++) {
      var p = String(list[i]).toLowerCase();
      var slash = p.indexOf('/');
      var hp = slash < 0 ? p : p.slice(0, slash);
      if (!hostMatches(hp, u.hostname)) continue;
      if (slash < 0 || (withPaths && u.pathname.toLowerCase().indexOf(p.slice(slash)) === 0)) return true;
    }
    return false;
  }

  function parse(href) {
    try { return new URL(href, document.baseURI); } catch (e) { return null; }
  }

  /** True when the URL leaves the site: a web URL on a host that is neither allowed nor a sign-in host. */
  function isOffSite(href, allowAuth) {
    var u = parse(href);
    if (!u) return false;
    if (u.protocol !== 'http:' && u.protocol !== 'https:') {
      return u.protocol !== 'javascript:' && u.protocol !== 'about:' && u.protocol !== 'blob:';
    }
    var c = loadCfg();
    if (c.empty) return false; // no config: do not guess
    if (listHas(c.allow, u, false)) return false;
    return !(allowAuth && listHas(c.auth, u, true));
  }

  // ---- window.open ----
  function noop() {}
  function makeStub() {
    var loc = { assign: noop, replace: noop, reload: noop, toString: function () { return ''; } };
    ['href', 'hash', 'search', 'pathname', 'host', 'hostname', 'origin', 'protocol'].forEach(function (k) {
      try { Object.defineProperty(loc, k, { get: function () { return ''; }, set: noop, enumerable: true }); } catch (e) { /* ignore */ }
    });
    var doc = { write: noop, writeln: noop, open: noop, close: noop, addEventListener: noop, removeEventListener: noop,
      createElement: function (t) { return document.createElement(t); }, body: null, head: null, title: '' };
    var stub = { closed: false, opener: null, name: '', location: loc, document: doc, focus: noop, blur: noop,
      postMessage: noop, addEventListener: noop, removeEventListener: noop, print: noop, stop: noop,
      close: function () { stub.closed = true; }, innerWidth: 800, innerHeight: 600 };
    return stub;
  }

  var realOpen = window.open;
  window.open = function (url) {
    var target = url == null ? '' : String(url);
    if (target !== '' && target !== 'about:blank' && isOffSite(target, false)) {
      report('popup', target);
      return makeStub();
    }
    return realOpen.apply(window, arguments);
  };

  // ---- anchors ----
  function anchorOf(el) {
    while (el && el.nodeType === 1) {
      if (el.tagName === 'A' && el.getAttribute('href') != null) return el;
      el = el.parentElement;
    }
    return null;
  }

  function leavesSite(a) {
    var href = a.href;
    if (!href || !isOffSite(href, true)) return false;
    if (window.top === window) return true;
    var t = (a.target || '').toLowerCase();
    return t === '_blank' || t === '_top' || t === '_parent';
  }

  var proto = window.HTMLAnchorElement && HTMLAnchorElement.prototype;
  if (proto && proto.click) {
    var realClick = proto.click;
    proto.click = function () {
      if (leavesSite(this)) { report('navigation', this.href); return; }
      return realClick.apply(this, arguments);
    };
  }

  // ---- click-jack overlays ----
  function coverage(r) {
    var w = Math.max(0, Math.min(r.right, innerWidth) - Math.max(r.left, 0));
    var h = Math.max(0, Math.min(r.bottom, innerHeight) - Math.max(r.top, 0));
    return (w * h) / Math.max(1, innerWidth * innerHeight);
  }

  function coversMedia(r) {
    var m = document.querySelectorAll('iframe,video');
    for (var i = 0; i < m.length; i++) {
      var b = m[i].getBoundingClientRect();
      var area = b.width * b.height;
      if (area < 1) continue;
      var w = Math.max(0, Math.min(r.right, b.right) - Math.max(r.left, b.left));
      var h = Math.max(0, Math.min(r.bottom, b.bottom) - Math.max(r.top, b.top));
      if ((w * h) / area > 0.3) return true;
    }
    return false;
  }

  // Never treat the site's own player or controls as a click-jack: media, iframes, buttons, labelled/role elements
  // and anything containing them are spared (a transparent player click-catcher is legitimate).
  var PLAYERISH = 'video,iframe,audio,button,input,select,textarea,[role],[aria-label],[tabindex],[data-player],[class*="player" i],[id*="player" i]';
  function isPlayerish(el) {
    try {
      return el.matches(PLAYERISH) || !!el.querySelector(PLAYERISH);
    } catch (e) { return true; }
  }

  function isInvisibleOverlay(el, isAnchor) {
    if (isPlayerish(el)) return false;
    var cs = getComputedStyle(el);
    if (cs.position !== 'fixed' && cs.position !== 'absolute') return false;
    var transparent = parseFloat(cs.opacity) < 0.1 || cs.visibility === 'hidden';
    if (!transparent) {
      var bg = cs.backgroundColor;
      var clear = bg === 'transparent' || /rgba\(.*,\s*0\)$/.test(bg);
      var noMedia = cs.backgroundImage === 'none' && !el.querySelector('img,svg,video,canvas');
      var noText = (el.textContent || '').replace(/\s+/g, '') === '';
      transparent = clear && noMedia && noText;
    }
    if (!transparent) return false;
    if (!isAnchor) {
      var p = el.parentElement;
      if (p !== document.body && p !== document.documentElement) return false; // spare players' own click-catchers
    }
    var r = el.getBoundingClientRect();
    return coverage(r) > 0.3 || coversMedia(r);
  }

  function resend(ev, el) {
    var x = ev.clientX, y = ev.clientY;
    var under = document.elementFromPoint(x, y);
    if (!under || under === el) return;
    redispatching = true;
    try {
      under.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true, view: window,
        clientX: x, clientY: y, screenX: ev.screenX, screenY: ev.screenY, button: 0 }));
    } finally { redispatching = false; }
  }

  function swallow(ev) {
    ev.preventDefault();
    ev.stopImmediatePropagation();
  }

  function onPointer(ev) {
    if (redispatching || !ev.target || !ev.target.nodeType) return;
    var a = anchorOf(ev.target);
    if (!a || !leavesSite(a)) return;
    swallow(ev);
    if (ev.type === 'click') {
      report('navigation', a.href);
      if (isInvisibleOverlay(a, true)) {
        a.parentNode && a.parentNode.removeChild(a);
        resend(ev, a);
      }
    }
  }

  function onClickNonAnchor(ev) {
    return;
  }

  ['click', 'auxclick', 'pointerup'].forEach(function (t) {
    document.addEventListener(t, onPointer, true);
  });
  document.addEventListener('click', onClickNonAnchor, true);

  // ---- forms ----
  document.addEventListener('submit', function (ev) {
    var f = ev.target;
    if (!f || f.tagName !== 'FORM') return;
    var t = (f.target || '').toLowerCase();
    if ((t === '_blank' || t === '_top' || t === '_parent') && isOffSite(f.action || '', false)) {
      swallow(ev);
      report('navigation', f.action);
    }
  }, true);

  // ---- meta refresh ----
  function checkMeta(m) {
    if (!m.httpEquiv || m.httpEquiv.toLowerCase() !== 'refresh') return;
    var match = /url\s*=\s*['"]?([^'"]+)/i.exec(m.content || '');
    if (match && isOffSite(match[1].trim(), false)) {
      report('navigation', match[1]);
      m.parentNode && m.parentNode.removeChild(m);
    }
  }

  function startMetaWatch() {
    var root = document.documentElement || document;
    var existing = document.getElementsByTagName('meta');
    for (var i = 0; i < existing.length; i++) checkMeta(existing[i]);
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var added = muts[i].addedNodes;
        for (var j = 0; j < added.length; j++) if (added[j].tagName === 'META') checkMeta(added[j]);
      }
    }).observe(root, { childList: true, subtree: true });
  }
  if (document.documentElement) startMetaWatch();
  else document.addEventListener('readystatechange', startMetaWatch, { once: true });
})();
