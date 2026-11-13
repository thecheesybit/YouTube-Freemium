'use strict';
// Spring physics as a CSS `linear()` easing: a damped spring (unit mass) sampled once, so every animation keeps real
// overshoot and settle behaviour instead of a fixed cubic-bezier. Same stiffness / damping-ratio parameters as the
// Android SpringAnimation constants in UiTokens. Loadable as a browser script (window.Spring) and as a CommonJS module.
(function (root) {
  var MAX_MS = 1600;
  var STEP_MS = 8;
  var EPS = 0.0015;

  /** Position of a unit-step spring at time t (seconds). */
  function position(stiffness, ratio, t) {
    var w0 = Math.sqrt(stiffness);
    if (ratio >= 1) return 1 - Math.exp(-w0 * t) * (1 + w0 * t);
    var wd = w0 * Math.sqrt(1 - ratio * ratio);
    var decay = Math.exp(-ratio * w0 * t);
    return 1 - decay * (Math.cos(wd * t) + ((ratio * w0) / wd) * Math.sin(wd * t));
  }

  /** @returns {{css:string, duration:number, samples:number[]}} duration in ms (time until settled within EPS). */
  function spring(stiffness, ratio) {
    var samples = [];
    var settledAt = MAX_MS;
    for (var ms = 0; ms <= MAX_MS; ms += STEP_MS) {
      samples.push(position(stiffness, ratio, ms / 1000));
    }
    for (var i = samples.length - 1; i >= 0; i--) {
      if (Math.abs(1 - samples[i]) > EPS) {
        settledAt = Math.min(MAX_MS, (i + 1) * STEP_MS);
        break;
      }
      settledAt = i * STEP_MS;
    }
    var n = Math.max(2, Math.ceil(settledAt / STEP_MS));
    var kept = samples.slice(0, n + 1);
    kept[kept.length - 1] = 1;
    var css = 'linear(' + kept.map(function (v) { return Math.round(v * 1000) / 1000; }).join(', ') + ')';
    return { css: css, duration: Math.max(STEP_MS * 2, settledAt), samples: kept };
  }

  var api = { spring: spring, position: position };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else root.Spring = api;
})(typeof window !== 'undefined' ? window : globalThis);
