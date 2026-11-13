// Movy TV: profile CSS, the idle mode, the fullscreen policy, the media API and the layout width.
// Concatenated after tvnav-pure.js and tvnav.js (needs window.__movyTv). tv flavor only, every frame.
// - Idle: native calls __movyTv.idle(true|false); `html.movy-idle` hides profile header/bottomBar/player controls, the ring, the cursor.
// - Fullscreen: Play (OK on a profile playButton, or the first `playing` after a user key) goes fullscreen on the profile
//   player.fullscreenTarget, stays fullscreen until BACK (re-entered after a swap), and the site's own buttons are hidden.
// - Media: __movyTv.media(action, secs) and __movyTv.mediaStatus() (polled by the native player bar later).
// - Layout width: top frame forces <meta viewport width=profile.viewportWidth> (default 1280).
(function (root) {
  'use strict';
  var TV = root.__movyTv;
  var P = root.__movyTvPure;
  if (!TV || !P || TV.fsLoaded) return;
  TV.fsLoaded = true;

  var doc = document;
  var inFrame = root.parent !== root;
  var GENERIC_CONTROLS = '.vjs-control-bar,.jw-controlbar,.plyr__controls,.shaka-controls-container,[class*="controlbar" i],[class*="control-bar" i]';
  var bubbleTimer = 0;
  var lastSeekAt = 0;
  var lastKeyAt = 0;
  var wantFs = false;
  var userExitAt = 0;
  var fsTries = 0;
  var fsTimer = 0;
  var idleOn = false;
  var statusAt = 0;

  function fsElement() { return doc.fullscreenElement || doc.webkitFullscreenElement || null; }
  function up(msg) { try { root.parent.postMessage(msg, '*'); } catch (e) { /* ignore */ } }

  // ---------- profile CSS ----------

  function styleEl() {
    var st = doc.getElementById('__movy_tv_profile_style');
    if (!st) {
      st = doc.createElement('style');
      st.id = '__movy_tv_profile_style';
      (doc.head || doc.documentElement).appendChild(st);
    }
    return st;
  }

  function applyCss(PF) {
    var css = '';
    var hide = function (sel) { return sel ? sel + '{display:none!important}' : ''; };
    css += hide(PF.player.fullscreenButton) + hide(PF.player.overlayKill);
    var fade = [PF.header, PF.bottomBar, PF.player.controls, GENERIC_CONTROLS].filter(Boolean)
      .map(function (s) { return 'html.movy-idle ' + s.split(',').join(',html.movy-idle '); }).join(',');
    css += fade + '{opacity:0!important;pointer-events:none!important;transition:opacity .25s ease-out!important}';
    css += 'html.movy-idle,html.movy-idle *{cursor:none!important}';
    css += 'html.movy-idle .movy-tvf,html.movy-idle :focus{outline-color:transparent!important;box-shadow:none!important;scale:1!important}';
    css += 'html.movy-idle #__movy_tv_bubble{opacity:0!important}';
    try { styleEl().textContent = css; } catch (e) { /* head not ready yet: retried on DOMContentLoaded */ }
  }

  // ---------- idle ----------

  TV.idle = function (on) {
    idleOn = !!on;
    var h = doc.documentElement;
    if (h) { if (on) h.classList.add('movy-idle'); else h.classList.remove('movy-idle'); }
    if (on) hideBubble();
    TV.relayDown({ __movyTvIdle: idleOn });
    return idleOn;
  };
  TV.isIdle = function () { return idleOn; };

  // ---------- killOnTv ----------

  var killed = new WeakSet();
  function killHeavy(PF) {
    var sels = PF.killOnTv || [];
    for (var i = 0; i < sels.length; i++) {
      if (!TV.okSel(sels[i])) continue;
      var nodes = doc.querySelectorAll(sels[i]);
      for (var j = 0; j < nodes.length; j++) {
        var el = nodes[j];
        if (killed.has(el)) continue;
        killed.add(el);
        try {
          if (el.tagName === 'IFRAME') { el.setAttribute('data-movy-killed', el.getAttribute('src') || ''); el.src = 'about:blank'; }
          else if (el.tagName === 'VIDEO') { el.pause(); el.removeAttribute('autoplay'); el.removeAttribute('src'); el.load(); }
          el.style.setProperty('display', 'none', 'important');
        } catch (e) { /* ignore */ }
      }
    }
  }

  // ---------- layout width ----------

  var viewportWatch = null;
  function applyViewport(PF) {
    if (inFrame) return;
    var w = PF.viewportWidth || 1280;
    var content = 'width=' + w;
    var set = function () {
      var all = doc.querySelectorAll('meta[name=viewport]');
      for (var i = 0; i < all.length; i++) if (all[i].getAttribute('content') !== content) all[i].setAttribute('content', content); // the last one wins
      if (all.length) return true;
      if (!doc.head) return false;
      var m = doc.createElement('meta');
      m.name = 'viewport';
      m.content = content;
      doc.head.insertBefore(m, doc.head.firstChild);
      return true;
    };
    set();
    if (!viewportWatch && doc.documentElement) {
      // Debounced and bounded: a framework that re-asserts its own viewport meta must not start a mutation ping-pong.
      var fixes = 0, timer = 0;
      viewportWatch = new MutationObserver(function () {
        if (timer || fixes > 25) return;
        timer = setTimeout(function () { timer = 0; fixes++; set(); }, 120);
      });
      viewportWatch.observe(doc.documentElement, { childList: true, subtree: true, attributes: true, attributeFilter: ['content'] });
    }
  }

  TV.hooks.profile = function (PF) {
    applyCss(PF);
    killHeavy(PF);
    applyViewport(PF);
  };

  // ---------- media ----------

  function findVideo() {
    var fs = fsElement();
    if (fs) {
      if (fs.tagName === 'VIDEO') return fs;
      var v = fs.querySelector('video');
      if (v) return v;
    }
    var PF = TV.PF;
    if (PF && PF.player.video && TV.okSel(PF.player.video)) {
      var pv = doc.querySelector(PF.player.video);
      if (pv) return pv;
    }
    var vids = doc.getElementsByTagName('video');
    var best = null, bestArea = 0;
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect();
      var area = r.width * r.height;
      if (!vids[i].paused) area *= 4;
      if (area > bestArea && r.width > 80 && r.height > 60) { best = vids[i]; bestArea = area; }
    }
    return best;
  }

  /** Whether a <video> is the main player rather than decorative media (hero trailer, preview). */
  function isMainVideo(v) {
    var PF = TV.PF;
    if (PF && PF.player.video && TV.okSel(PF.player.video) && v.matches(PF.player.video)) return true;
    var r = v.getBoundingClientRect();
    if (inFrame) return r.width * r.height >= root.innerWidth * root.innerHeight * 0.4;
    return !(v.muted && v.loop) && r.width * r.height >= root.innerWidth * root.innerHeight * 0.5;
  }

  function hideBubble() {
    var b = doc.getElementById('__movy_tv_bubble');
    if (b && b.parentNode) b.parentNode.removeChild(b);
  }

  function bubble(text) {
    var host = fsElement() || doc.documentElement;
    var b = doc.getElementById('__movy_tv_bubble');
    if (!b) {
      b = doc.createElement('div');
      b.id = '__movy_tv_bubble';
      b.style.cssText = 'position:fixed;left:50%;bottom:12%;transform:translateX(-50%);padding:12px 26px;border-radius:999px;' +
        'background:rgba(20,20,22,.88);color:#fff;font:600 26px/1.2 system-ui,sans-serif;letter-spacing:-.01em;' +
        'z-index:2147483647;pointer-events:none;box-shadow:0 6px 24px rgba(0,0,0,.5);white-space:nowrap';
    }
    if (b.parentNode !== host) host.appendChild(b);
    b.textContent = text;
    b.style.opacity = '1';
    clearTimeout(bubbleTimer);
    bubbleTimer = setTimeout(function () { b.style.opacity = '0'; setTimeout(function () { if (b.parentNode) b.parentNode.removeChild(b); }, 200); }, 1100);
  }

  function seek(v, delta) {
    var t = P.seekTarget(v.currentTime, v.duration, delta);
    try { v.currentTime = t; } catch (e) { return; }
    bubble((delta < 0 ? '⏪ ' : '⏩ ') + (delta > 0 ? '+' : '−') + Math.abs(delta) + 's   ' + P.fmt(t) + ' / ' + P.fmt(v.duration));
  }

  function togglePlay(v, force) {
    var play = force === 'play' ? true : force === 'pause' ? false : v.paused;
    if (play) { var p = v.play(); if (p && p.catch) p.catch(function () {}); } else v.pause();
    bubble(play ? '▶ Play' : '⏸ Pause');
  }

  function wakeControls(v) {
    var host = fsElement() || (v && v.parentElement) || doc.body;
    var r = (v || host).getBoundingClientRect();
    var x = r.left + r.width / 2, y = r.top + r.height * 0.7;
    var tgt = doc.elementFromPoint(x, y) || host;
    var seq = ['mouseover', 'mousemove', 'pointermove'];
    for (var i = 0; i < seq.length; i++) {
      try { tgt.dispatchEvent(new MouseEvent(seq[i], { bubbles: true, clientX: x + (i % 2), clientY: y, view: root })); } catch (e) { /* ignore */ }
    }
  }

  /** Transport keys from the native shell: playpause | play | pause | seek (seconds). Relayed into iframes when this frame has no video. */
  TV.media = function (action, secs) {
    var v = findVideo();
    if (!v) { TV.relayDown({ __movyTvMedia: [action, secs] }); return false; }
    if (action === 'seek') seek(v, secs || 10);
    else togglePlay(v, action === 'playpause' ? null : action);
    return true;
  };

  /** Status of this frame's main video, or null. Aggregated upwards by postMessage (see TV.lastStatus). */
  TV.status = function () {
    var v = findVideo();
    if (!v) return null;
    return { paused: v.paused, currentTime: v.currentTime || 0, duration: isFinite(v.duration) ? v.duration : 0, muted: v.muted,
      ended: v.ended, fullscreen: !!fsElement(), inFrame: inFrame, title: doc.title || '' };
  };
  TV.lastStatus = null;
  TV.mediaStatus = function () { return TV.lastStatus || TV.status(); };

  function publishStatus(force) {
    var now = Date.now();
    if (!force && now - statusAt < 900) return;
    statusAt = now;
    var st = TV.status();
    if (!st) return;
    if (inFrame) up({ __movyTvStatus: st });
    else receiveStatus(st);
  }

  function receiveStatus(st) {
    TV.lastStatus = st;
    try { if (root.MovyTvNative && root.MovyTvNative.onMedia) root.MovyTvNative.onMedia(JSON.stringify(st)); } catch (e) { /* ignore */ }
  }

  // ---------- fullscreen policy ----------

  /** The element to fullscreen in THIS document, or null. */
  function fsTarget() {
    var PF = TV.PF;
    if (PF) {
      var sels = [PF.player.fullscreenTarget, PF.player.frame, PF.player.root];
      for (var i = 0; i < sels.length; i++) {
        if (!sels[i] || !TV.okSel(sels[i])) continue;
        var el = doc.querySelector(sels[i]);
        if (el) return el;
      }
    }
    var big = TV.bigIframe();
    if (big) return big;
    return null;
  }

  function videoContainer() {
    var v = findVideo();
    if (!v) return null;
    var p = v, r = v.getBoundingClientRect(), n = 0;
    while (p.parentElement && n++ < 3) {
      var pr = p.parentElement.getBoundingClientRect();
      if (pr.width * pr.height > r.width * r.height * 1.6) break;
      p = p.parentElement;
    }
    return p;
  }

  function requestFs(el) {
    var fn = el.requestFullscreen || el.webkitRequestFullscreen;
    if (!fn) return Promise.reject(new Error('unsupported'));
    var res = fn.call(el);
    return res && res.then ? res : Promise.resolve();
  }

  function nativeUserExit() {
    try { return !!(root.MovyTvNative && root.MovyTvNative.userLeftFullscreen && root.MovyTvNative.userLeftFullscreen()); } catch (e) { return false; }
  }

  function requestGesture() {
    try { if (root.MovyTvNative && root.MovyTvNative.gesture) root.MovyTvNative.gesture(); } catch (e) { /* ignore */ }
  }

  /** Top frame: enter fullscreen on the profile target (inside a key handler when possible). */
  function userJustLeft() { return Date.now() - userExitAt < 3000 || nativeUserExit(); }

  function ensureFs(why) {
    if (inFrame) { up({ __movyTvFs: why || 'child' }); return; }
    if (fsElement()) return;
    if (why !== 'api' && why !== 'activate' && userJustLeft()) return; // BACK left fullscreen: stay out
    var el = fsTarget() || videoContainer();
    clearTimeout(fsTimer);
    if (!el) {
      if (fsTries++ < 8) fsTimer = setTimeout(function () { ensureFs(why); }, 500);
      return;
    }
    requestFs(el).then(function () { fsTries = 0; }, function () {
      if (fsTries++ >= 4) return;
      requestGesture(); // a synthetic key from native grants the transient user activation
      fsTimer = setTimeout(function () { ensureFs('retry'); }, 220);
    });
  }

  TV.enterFullscreen = function () { wantFs = true; fsTries = 0; ensureFs('api'); };
  TV.userExitFs = function () {
    userExitAt = Date.now();
    wantFs = false;
    TV.relayDown({ __movyTvUserExit: 1 });
  };
  TV.wantsFullscreen = function () { return wantFs; };

  TV.hooks.beforeActivate = function (el) {
    lastKeyAt = Date.now();
    var PF = TV.PF;
    if (!PF || inFrame) return;
    var isPlay = (PF.playButtons && (el.matches(PF.playButtons) || el.closest(PF.playButtons)))
      || (PF.player.frame && TV.okSel(PF.player.frame) && el.closest(PF.player.frame));
    if (isPlay) { wantFs = true; fsTries = 0; userExitAt = 0; }
  };
  TV.hooks.afterActivate = function () {
    if (wantFs && !inFrame && !fsElement()) {
      // inside the key handler: counts as a user gesture; the target may not exist yet (route change), so retry shortly
      var el = fsTarget();
      if (el && findVideoPlaying()) ensureFs('activate');
    }
  };

  function findVideoPlaying() {
    var v = findVideo();
    return !!(v && !v.paused && v.readyState > 2);
  }

  function onPlaying(e) {
    var v = e.target;
    if (!v || v.tagName !== 'VIDEO' || !isMainVideo(v)) return;
    publishStatus(true);
    if (inFrame) { up({ __movyTvPlaying: 1 }); return; }
    onChildPlaying();
  }

  function onChildPlaying() {
    var recent = Date.now() - lastKeyAt < 25000;
    if (userJustLeft() || (!wantFs && !recent)) return;
    wantFs = true;
    fsTries = 0;
    ensureFs('playing');
  }

  root.addEventListener('playing', onPlaying, true);
  ['timeupdate', 'pause', 'ended', 'loadedmetadata'].forEach(function (t) {
    root.addEventListener(t, function (e) { if (e.target && e.target.tagName === 'VIDEO' && isMainVideo(e.target)) publishStatus(t !== 'timeupdate'); }, true);
  });

  doc.addEventListener('fullscreenchange', function () {
    if (inFrame) return;
    if (fsElement()) { fsTries = 0; return; }
    if (Date.now() - userExitAt < 2500 || nativeUserExit()) { wantFs = false; return; }
    if (wantFs) setTimeout(function () { if (wantFs && !fsElement() && !nativeUserExit()) ensureFs('lost'); }, 250); // swapped by the site: re-enter
  });

  // ---------- keys inside video ----------

  TV.hooks.key = function (e, dir, a) {
    lastKeyAt = Date.now();
    if (!inFrame && wantFs && !fsElement() && findVideoPlaying()) ensureFs('key');
    if (idleOn) TV.idle(false);
    var fs = fsElement();
    var video = (fs || inFrame) ? findVideo() : null;
    if (!video) return false;
    var free = !a || a === video || a === fs || a.tagName === 'VIDEO' || a === doc.body;
    if (!free) return false;
    if (e.keyCode === 13 || e.keyCode === 23) { e.preventDefault(); e.stopPropagation(); if (!e.repeat) togglePlay(video); return true; }
    if (dir === 'left' || dir === 'right') {
      e.preventDefault(); e.stopPropagation();
      var now = Date.now();
      if (now - lastSeekAt > 180) { lastSeekAt = now; seek(video, dir === 'left' ? -10 : 10); }
      return true;
    }
    if (dir === 'up' || dir === 'down') {
      e.preventDefault(); e.stopPropagation();
      wakeControls(video);
      if (!TV.move(dir) && inFrame) up({ __movyTvExit: dir });
      return true;
    }
    return false;
  };

  // ---------- messages ----------

  root.addEventListener('message', function (e) {
    var d = e.data;
    if (!d || typeof d !== 'object') return;
    if (d.__movyTvMedia && e.source === root.parent) { TV.media(d.__movyTvMedia[0], d.__movyTvMedia[1]); return; }
    if (d.__movyTvIdle !== undefined && e.source === root.parent) { TV.idle(d.__movyTvIdle); return; }
    if (d.__movyTvUserExit && e.source === root.parent) { userExitAt = Date.now(); wantFs = false; TV.relayDown(d); return; }
    if (d.__movyTvPlaying) { if (inFrame) up(d); else onChildPlaying(); return; }
    if (d.__movyTvFs) { if (inFrame) up(d); else ensureFs('child'); return; }
    if (d.__movyTvStatus) { if (inFrame) up(d); else receiveStatus(d.__movyTvStatus); }
  });

  function init() {
    if (TV.PF) { applyCss(TV.PF); killHeavy(TV.PF); applyViewport(TV.PF); }
  }
  doc.addEventListener('DOMContentLoaded', init);
  root.addEventListener('load', function () { init(); setTimeout(init, 1200); });
  var killTimer = 0;
  new MutationObserver(function () {
    if (killTimer) return;
    killTimer = setTimeout(function () { killTimer = 0; if (TV.PF) killHeavy(TV.PF); }, 600);
  }).observe(doc.documentElement, { childList: true, subtree: true });
  init();
})(typeof window !== 'undefined' ? window : {});
