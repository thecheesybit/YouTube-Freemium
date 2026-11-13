'use strict';
// Decides which page-load failures deserve the branded error screen. Chromium error codes (net_error_list.h).

const ABORTED = -3; // navigation superseded or cancelled (normal)
const BLOCKED_BY_CLIENT = -20; // our own ad/navigation blocking
const OFFLINE_CODES = new Set([-106, -105, -109, -130, -137]); // internet disconnected, name not resolved, unreachable, proxy, name resolution failed
const FAILED_CODES = new Set([-2, -7, -100, -101, -102, -104, -110, -111, -118, -200, -201, -202, -324, -501]);

/**
 * @param {{errorCode:number, isMainFrame:boolean}} i
 * @returns {'ignore'|'offline'|'failed'|'blocked'}
 */
function classifyLoadFailure({ errorCode, isMainFrame }) {
  if (!isMainFrame) return 'ignore';
  if (errorCode === ABORTED || errorCode === 0) return 'ignore';
  if (errorCode === BLOCKED_BY_CLIENT) return 'blocked';
  if (OFFLINE_CODES.has(errorCode)) return 'offline';
  if (FAILED_CODES.has(errorCode)) return 'failed';
  return 'failed';
}

/** Short copy for the error screen. Never contains a URL. */
function describeFailure(kind, name) {
  if (kind === 'offline') return { title: 'You’re offline', body: `${name} can’t be reached. Check your internet connection.` };
  if (kind === 'blocked') return { title: 'Page blocked', body: `${name} blocked this page to keep you safe.` };
  return { title: `${name} isn’t responding`, body: 'Something went wrong while loading the page. Try again in a moment.' };
}

/** Tracks the current failure per source so switching views shows the right screen and a success clears it. */
class LoadErrors {
  constructor(onChange = () => {}) {
    this.byId = new Map();
    this.onChange = onChange;
  }

  fail(id, kind) {
    if (kind === 'ignore') return false;
    if (this.byId.get(id) === kind) return false;
    this.byId.set(id, kind);
    this.onChange(id, kind);
    return true;
  }

  clear(id) {
    if (!this.byId.delete(id)) return false;
    this.onChange(id, null);
    return true;
  }

  get(id) {
    return this.byId.get(id) ?? null;
  }
}

module.exports = { classifyLoadFailure, describeFailure, LoadErrors };
