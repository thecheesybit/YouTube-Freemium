'use strict';
// Tiny DOM + animation helpers shared by the island and the panel (classic script, exposes window.UI).
(function (root) {
  var TOKENS = {
    expand: Spring.spring(380, 0.78),
    collapse: Spring.spring(500, 0.9),
    press: Spring.spring(900, 0.7),
    soft: Spring.spring(420, 0.82),
    panel: Spring.spring(320, 0.86),
  };

  /** h('div.cls#id', {attr}, children...) */
  function h(tag, attrs) {
    var m = /^([a-z0-9]+)((?:[.#][\w-]+)*)$/i.exec(tag);
    var el = document.createElement(m ? m[1] : 'div');
    if (m && m[2]) {
      m[2].replace(/([.#])([\w-]+)/g, function (_, k, v) {
        if (k === '.') el.classList.add(v);
        else el.id = v;
        return '';
      });
    }
    if (attrs instanceof Node || Array.isArray(attrs) || typeof attrs === 'string') {
      append(el, attrs);
    } else if (attrs && typeof attrs === 'object') {
      Object.keys(attrs).forEach(function (k) {
        var v = attrs[k];
        if (v === null || v === undefined || v === false) return;
        if (k === 'text') el.textContent = v;
        else if (k === 'style') el.style.cssText = v;
        else if (k.slice(0, 2) === 'on') el.addEventListener(k.slice(2), v);
        else el.setAttribute(k, v === true ? '' : v);
      });
    }
    for (var i = 2; i < arguments.length; i++) append(el, arguments[i]);
    return el;
  }

  function append(el, c) {
    if (c === null || c === undefined || c === false) return;
    if (Array.isArray(c)) c.forEach(function (x) { append(el, x); });
    else el.appendChild(typeof c === 'string' ? document.createTextNode(c) : c);
  }

  /**
   * Plays keyframes with a spring token, then commits the final frame as inline style so the element rests there.
   * Returns a promise that resolves when done.
   */
  function play(el, frames, token, opts) {
    var o = opts || {};
    var anim = el.animate(frames, {
      duration: o.duration || token.duration,
      easing: token.css,
      delay: o.delay || 0,
      fill: 'both',
    });
    // A hidden/occluded view may not run frames, so a timer settles the promise too (the animation never gets stuck).
    var total = (o.duration || token.duration) + (o.delay || 0) + 60;
    var settle = new Promise(function (r) { setTimeout(r, total); });
    return Promise.race([anim.finished, settle]).then(function () {
      try {
        anim.commitStyles();
      } catch (e) { /* element detached */ }
      anim.cancel();
    }, function () { /* cancelled by a newer animation */ });
  }

  var wait = function (ms) { return new Promise(function (r) { setTimeout(r, ms); }); };

  /** Health colours / labels. */
  var HEALTH = { up: 'Online', slow: 'Slow', down: 'Offline', unknown: 'Not checked' };

  function gradient(accent) {
    return 'linear-gradient(180deg,' + accent + ',' + darken(accent, 0.25) + ')';
  }

  function darken(hex, amount) {
    var n = parseInt(hex.slice(1), 16);
    var k = 1 - amount;
    var r = Math.round(((n >> 16) & 255) * k);
    var g = Math.round(((n >> 8) & 255) * k);
    var b = Math.round((n & 255) * k);
    return 'rgb(' + r + ',' + g + ',' + b + ')';
  }

  /** YouTube vector marks on white rounded tile per spec */
  function logoSvg(id, size) {
    var h = size * 0.38;
    var cx = size / 2;
    var cy = size / 2;
    if (id === 'youtube-music') {
      var r = h * 0.80;
      var tri = r * 0.62;
      var tLeft = cx - tri * 0.38;
      var tTop = cy - tri * 0.52;
      var tBot = cy + tri * 0.52;
      var tRight = cx + tri * 0.62;
      return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">' +
        '<circle cx="' + cx + '" cy="' + cy + '" r="' + r + '" fill="#FF0033"/>' +
        '<circle cx="' + cx + '" cy="' + cy + '" r="' + (r * 0.70) + '" fill="none" stroke="#FFFFFF" stroke-width="' + (r * 0.115) + '"/>' +
        '<path d="M ' + tLeft + ' ' + tTop + ' L ' + tLeft + ' ' + tBot + ' L ' + tRight + ' ' + cy + ' Z" fill="#FFFFFF"/>' +
        '</svg>';
    } else {
      var w = h * 1.44;
      var ht = h * 1.02;
      var rx = ht * 0.30;
      var x0 = cx - w / 2;
      var y0 = cy - ht / 2;
      var tri2 = ht * 0.46;
      var tLeft2 = cx - tri2 * 0.38;
      var tTop2 = cy - tri2 * 0.52;
      var tBot2 = cy + tri2 * 0.52;
      var tRight2 = cx + tri2 * 0.62;
      return '<svg width="' + size + '" height="' + size + '" viewBox="0 0 ' + size + ' ' + size + '">' +
        '<rect x="' + x0 + '" y="' + y0 + '" width="' + w + '" height="' + ht + '" rx="' + rx + '" fill="#FF0000"/>' +
        '<path d="M ' + tLeft2 + ' ' + tTop2 + ' L ' + tLeft2 + ' ' + tBot2 + ' L ' + tRight2 + ' ' + cy + ' Z" fill="#FFFFFF"/>' +
        '</svg>';
    }
  }

  /** White rounded tile with real logo mark and health dot */
  function tile(source, size, withDot) {
    var svg = logoSvg(source.id, size);
    var el = h('span.tile', {
      style: 'width:' + size + 'px;height:' + size + 'px;background:#FFFFFF;border-radius:' + Math.round(size * 0.28) + 'px;display:flex;align-items:center;justify-content:center;position:relative;'
    });
    el.innerHTML = svg;
    if (withDot) {
      el.appendChild(h('span.dot.h-' + (source.health || 'up'), { 'data-health': source.id }));
    }
    return el;
  }

  function formatBytes(n) {
    if (n === null || n === undefined) return '…';
    var u = ['B', 'KB', 'MB', 'GB'];
    var i = 0;
    var v = n;
    while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
    return (i === 0 ? v : v.toFixed(v < 10 ? 1 : 0)) + ' ' + u[i];
  }

  root.UI = { h: h, play: play, wait: wait, T: TOKENS, HEALTH: HEALTH, gradient: gradient, tile: tile, formatBytes: formatBytes };
})(window);
