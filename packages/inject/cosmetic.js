// Movy cosmetic filtering (shared by Android and Electron). The platform wraps this file as
//   (function(){ var MOVY_OPTS={...}; [var MOVY_BRIDGE=...; var MOVY_EVAL=...;] <this file> })();
// and runs it at document start in every frame.
//  - MOVY_OPTS    {cosmetic:boolean, tv?:boolean}
//  - MOVY_BRIDGE  {cosmetic(url)->json, hidden(classesJson, idsJson, exceptionsJson)->json}; Android leaves it
//                 undefined and exposes the same two methods as window.MovyAdblock (@JavascriptInterface).
//  - MOVY_EVAL    optional (code)->void that runs scriptlets in the page's main world; defaults to indirect eval
//                 (Electron runs this file in an isolated world, where plain eval would not reach the page).
var bridge = typeof MOVY_BRIDGE !== 'undefined' ? MOVY_BRIDGE : window.MovyAdblock;
var proto = location.protocol;
if (bridge && (proto === 'http:' || proto === 'https:') && !window.__movyCosmetic) {
  window.__movyCosmetic = true;
  var sheets = [];

  var addCss = function (css) {
    if (!css) return;
    try {
      var s = new CSSStyleSheet();
      s.replaceSync(css);
      document.adoptedStyleSheets = document.adoptedStyleSheets.concat([s]);
      return;
    } catch (e) { /* fall through to <style> */ }
    var el = document.createElement('style');
    el.textContent = css;
    var attach = function () { (document.head || document.documentElement).appendChild(el); };
    if (document.documentElement) attach(); else document.addEventListener('readystatechange', attach, { once: true });
  };

  var hideRule = function (selectors) {
    // One bad selector invalidates a whole rule, so build per-chunk and fall back to per-selector.
    var CHUNK = 200;
    for (var i = 0; i < selectors.length; i += CHUNK) {
      var part = selectors.slice(i, i + CHUNK);
      var css = part.join(',\n') + '{display:none!important}';
      try {
        var test = new CSSStyleSheet();
        test.replaceSync(css);
        if (test.cssRules.length === 1) { addCss(css); continue; }
      } catch (e) { /* invalid chunk */ }
      var ok = [];
      for (var j = 0; j < part.length; j++) {
        try { var t = new CSSStyleSheet(); t.replaceSync(part[j] + '{x:y}'); if (t.cssRules.length) ok.push(part[j]); } catch (e2) { /* skip */ }
      }
      if (ok.length) addCss(ok.join(',\n') + '{display:none!important}');
    }
  };

  if (MOVY_OPTS.tv) {
    addCss(':focus-visible{outline:3px solid #4da3ff!important;outline-offset:2px!important}');
  }

  if (MOVY_OPTS.cosmetic) {
    var res = {};
    try { res = JSON.parse(bridge.cosmetic(location.href)) || {}; } catch (e) { res = {}; }

    if (res.hide_selectors && res.hide_selectors.length) hideRule(res.hide_selectors);

    if (res.injected_script) {
      try {
        var code = res.injected_script;
        try {
          if (window.trustedTypes && trustedTypes.createPolicy) {
            code = trustedTypes.createPolicy('movy-scriptlets', { createScript: function (s) { return s; } }).createScript(code);
          }
        } catch (e) {}
        (typeof MOVY_EVAL === 'function' ? MOVY_EVAL : eval)(code);
      } catch (e) { /* blocked by page CSP or scriptlet error */ }
    }

    if (!res.generichide) {
      var exceptions = res.exceptions || [];
      var seenClasses = new Set();
      var seenIds = new Set();
      var pendingClasses = [];
      var pendingIds = [];
      var timer = 0;

      var note = function (el) {
        if (el.nodeType !== 1) return;
        var id = el.id;
        if (id && typeof id === 'string' && !seenIds.has(id)) { seenIds.add(id); pendingIds.push(id); }
        var cl = el.classList;
        if (cl) for (var i = 0; i < cl.length; i++) {
          var c = cl[i];
          if (!seenClasses.has(c)) { seenClasses.add(c); pendingClasses.push(c); }
        }
      };

      var flush = function () {
        timer = 0;
        if (!pendingClasses.length && !pendingIds.length) return;
        var cs = pendingClasses.splice(0, 4000);
        var is = pendingIds.splice(0, 4000);
        try {
          var sel = JSON.parse(bridge.hidden(JSON.stringify(cs), JSON.stringify(is), JSON.stringify(exceptions)));
          if (sel && sel.length) hideRule(sel);
        } catch (e) { /* ignore */ }
        if (pendingClasses.length || pendingIds.length) schedule();
      };

      var schedule = function () {
        if (!timer) timer = setTimeout(flush, 300);
      };

      var scanTree = function (root) {
        note(root);
        if (root.querySelectorAll) {
          var all = root.querySelectorAll('[class],[id]');
          for (var i = 0; i < all.length; i++) note(all[i]);
        }
      };

      var observer = new MutationObserver(function (muts) {
        for (var i = 0; i < muts.length; i++) {
          var m = muts[i];
          if (m.type === 'attributes') note(m.target);
          else for (var j = 0; j < m.addedNodes.length; j++) if (m.addedNodes[j].nodeType === 1) scanTree(m.addedNodes[j]);
        }
        if (pendingClasses.length || pendingIds.length) schedule();
      });
      observer.observe(document, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'id'] });

      var initial = function () {
        if (document.documentElement) scanTree(document.documentElement);
        schedule();
      };
      if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', initial, { once: true });
      else initial();
    }
  }
}
