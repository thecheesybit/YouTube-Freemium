'use strict';
// Per-view back/forward state. Each site view keeps its own history (YouTube and YouTube Music are independent);
// this reads it into a plain, comparable snapshot and runs back/forward without ever throwing.

const EMPTY = Object.freeze({ canGoBack: false, canGoForward: false });

/** @param {{navigationHistory?:{canGoBack:Function,canGoForward:Function}}|null} wc */
function readHistory(wc) {
  try {
    const h = wc && !(wc.isDestroyed && wc.isDestroyed()) ? wc.navigationHistory : null;
    if (!h) return EMPTY;
    return { canGoBack: !!h.canGoBack(), canGoForward: !!h.canGoForward() };
  } catch {
    return EMPTY;
  }
}

/** Goes back/forward when possible; returns whether a navigation was started. */
function step(wc, direction) {
  const state = readHistory(wc);
  const h = wc.navigationHistory;
  if (direction === 'back' && state.canGoBack) {
    h.goBack();
    return true;
  }
  if (direction === 'forward' && state.canGoForward) {
    h.goForward();
    return true;
  }
  return false;
}

/** Tracks the snapshot of the active view and reports only real changes. */
class NavHistory {
  constructor(onChange = () => {}) {
    this.onChange = onChange;
    this.last = EMPTY;
  }

  refresh(wc) {
    const next = readHistory(wc);
    if (next.canGoBack === this.last.canGoBack && next.canGoForward === this.last.canGoForward) return false;
    this.last = next;
    this.onChange(next);
    return true;
  }

  get state() {
    return this.last;
  }
}

module.exports = { EMPTY, readHistory, step, NavHistory };
