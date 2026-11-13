// Movy scam-overlay guard. Runs at document start in the top frame of every Android shell page.
// Removes the "Confirm you're not a robot" fake-captcha / QR modals that ad networks inject: a top-level position:fixed
// element covering >= 80% of the viewport that is either a blank (src-less) iframe, an iframe[srcdoc][sandbox~allow-modals],
// or a container holding one or a QR-style canvas / data-URI image, at an extreme z-index. Real players are never touched:
// they have an http(s) src, contain a <video>, or do not combine full-screen cover with such a z-index.
// Cosmetic CSS cannot do this job: the scam sets display:block !important inline. The decision is a pure function,
// exported for the node unit tests when loaded as a CommonJS module.
(function (root) {
  'use strict';

  var Z_EXTREME = 2147483000;
  var COVER = 0.8;

  /**
   * info: { fixed, vw, vh, w, h, z, tag, src, hasSrcdoc, sandbox, hasVideo, hasBlankFrame, hasSrcdocSandboxFrame, hasQr, captchaText }
   * Returns true when the element is a scam overlay that must be removed.
   */
  function isScamOverlay(info) {
    if (!info || !info.fixed || info.hasVideo) return false;
    if (!(info.vw > 0 && info.vh > 0)) return false;
    if (info.w < info.vw * COVER || info.h < info.vh * COVER) return false;
    var z = info.z || 0;
    var self = info.tag === 'IFRAME';
    var blank = self ? isBlankSrc(info.src) && !info.hasSrcdoc : !!info.hasBlankFrame;
    var srcdocSandbox = self ? !!(info.hasSrcdoc && /allow-modals/.test(info.sandbox || '')) : !!info.hasSrcdocSandboxFrame;
    if (srcdocSandbox) return z >= 1000;
    if (z < Z_EXTREME) return false;
    return blank || !!info.hasQr || !!info.captchaText;
  }

  function isBlankSrc(src) {
    return !src || src === 'about:blank' || /^javascript:/i.test(src);
  }

  var api = { isScamOverlay: isScamOverlay, isBlankSrc: isBlankSrc };
  if (typeof module === 'object' && module && module.exports) { module.exports = api; return; }
  if (typeof window === 'undefined' || window.__movyScamGuard || root.top !== root) return;
  root.__movyScamGuard = true;

  var CAPTCHA_RE = /not a robot|verify you are human|scan (this )?(the )?qr/i;

  function describe(el) {
    var cs = root.getComputedStyle(el);
    var r = el.getBoundingClientRect();
    var tag = el.tagName;
    var info = {
      fixed: cs.position === 'fixed', vw: root.innerWidth, vh: root.innerHeight, w: r.width, h: r.height,
      z: parseInt(cs.zIndex, 10) || 0, tag: tag, src: tag === 'IFRAME' ? (el.getAttribute('src') || '') : '',
      hasSrcdoc: tag === 'IFRAME' && el.hasAttribute('srcdoc'), sandbox: el.getAttribute && el.getAttribute('sandbox') || '',
      hasVideo: false, hasBlankFrame: false, hasSrcdocSandboxFrame: false, hasQr: false, captchaText: false
    };
    var doc = null;
    if (tag === 'IFRAME') { try { doc = el.contentDocument; } catch (e) { doc = null; } }
    var scope = doc || el;
    try {
      info.hasVideo = !!scope.querySelector('video');
      if (tag !== 'IFRAME') {
        var frames = el.querySelectorAll('iframe');
        for (var i = 0; i < frames.length; i++) {
          var f = frames[i];
          if (isBlankSrc(f.getAttribute('src')) && !f.hasAttribute('srcdoc')) info.hasBlankFrame = true;
          if (f.hasAttribute('srcdoc') && /allow-modals/.test(f.getAttribute('sandbox') || '')) info.hasSrcdocSandboxFrame = true;
        }
      }
      info.hasQr = !!scope.querySelector('canvas,img[src^="data:image"],svg[viewBox]');
      info.captchaText = CAPTCHA_RE.test((scope.body || scope).innerText || '');
    } catch (e) { /* cross-origin: leave defaults */ }
    return info;
  }

  function report(el) {
    try { if (root.MovyAdblock && root.MovyAdblock.killed) root.MovyAdblock.killed('popup', 'overlay'); } catch (e) { /* ignore */ }
    if (el.parentNode) el.parentNode.removeChild(el);
  }

  function check(el) {
    if (!el || el.nodeType !== 1 || !el.isConnected) return;
    var t = el.tagName;
    if (t === 'SCRIPT' || t === 'STYLE' || t === 'LINK' || t === 'META') return;
    try { if (isScamOverlay(describe(el))) report(el); } catch (e) { /* ignore */ }
  }

  function sweep(parent) {
    var kids = parent.children;
    for (var i = kids.length - 1; i >= 0; i--) check(kids[i]);
  }

  // The scam sizes and styles the element after inserting it, and may toggle it: re-check a few times.
  function watch(el) {
    [30, 250, 900, 2500].forEach(function (ms) { setTimeout(function () { check(el); }, ms); });
  }

  function start() {
    var de = document.documentElement;
    if (!de) return;
    new MutationObserver(function (muts) {
      for (var i = 0; i < muts.length; i++) {
        var m = muts[i];
        if (m.type === 'attributes') { if (m.target.parentNode === document.body || m.target.parentNode === de) check(m.target); continue; }
        for (var j = 0; j < m.addedNodes.length; j++) {
          var n = m.addedNodes[j];
          if (n.nodeType === 1 && (m.target === document.body || m.target === de)) { check(n); watch(n); }
        }
      }
    }).observe(de, { childList: true, subtree: true, attributes: true, attributeFilter: ['style', 'class'] });
    if (document.body) sweep(document.body);
  }

  if (document.documentElement) start();
  else document.addEventListener('readystatechange', start, { once: true });
})(typeof window !== 'undefined' ? window : {});
