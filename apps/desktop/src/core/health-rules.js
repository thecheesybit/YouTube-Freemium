'use strict';
// Pure health rules: classify a probe result and pick a failover suggestion.
const UP_MAX_MS = 1500;
const SLOW_MAX_MS = 5000;

/** @param {number} httpStatus <= 0 when no response arrived  @param {number} elapsedMs time to the response head */
function classify(httpStatus, elapsedMs) {
  if (!(httpStatus > 0) || httpStatus >= 500) return 'down';
  if (elapsedMs <= UP_MAX_MS) return 'up';
  if (elapsedMs <= SLOW_MAX_MS) return 'slow';
  return 'down';
}

/** HEAD is retried as a ranged GET when the site refuses or does not implement it. */
const headNeedsFallback = (status) => !(status > 0) || status === 405 || status === 501 || status === 400;

/** First 'up' source in display order (excluding excludeId), else the first 'slow' one, else null. */
function suggest(ordered, statusOf, excludeId) {
  let slow = null;
  for (const s of ordered) {
    if (s.id === excludeId) continue;
    const st = statusOf(s.id);
    if (st === 'up') return s;
    if (st === 'slow' && !slow) slow = s;
  }
  return slow;
}

module.exports = { classify, headNeedsFallback, suggest, UP_MAX_MS, SLOW_MAX_MS };
