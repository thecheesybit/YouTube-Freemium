'use strict';
// Watches one site view's load lifecycle and reports real failures (offline, DNS, blocked, crashed renderer) to
// LoadErrors so the shell can show its branded error screen. Everything else (aborted/superseded navigations, failing
// subframes and subresources) is ignored. Logs carry only the error code and kind, never a URL.
const { classifyLoadFailure } = require('../core/load-errors');

const CRASH_RELOAD_GAP_MS = 10000;

/**
 * @param {{id:string, wc:Electron.WebContents, errors:import('../core/load-errors').LoadErrors, log:Function, now?:()=>number}} o
 */
function guardLoads({ id, wc, errors, log, now = () => Date.now() }) {
  let failedThisLoad = false;
  let lastCrashReload = 0;

  wc.on('did-start-navigation', (_e, _url, isInPlace, isMainFrame) => {
    if (isMainFrame && !isInPlace) failedThisLoad = false;
  });

  wc.on('did-fail-load', (_e, errorCode, errorDescription, _url, isMainFrame) => {
    const kind = classifyLoadFailure({ errorCode, isMainFrame });
    if (kind === 'ignore') return;
    failedThisLoad = true;
    log(`[load] ${id} failed (${errorDescription || errorCode}) -> ${kind}`);
    errors.fail(id, kind);
  });

  // The error page Chromium commits after a failure also "finishes loading"; only a clean load clears the error.
  wc.on('did-finish-load', () => {
    if (!failedThisLoad) errors.clear(id);
  });

  wc.on('render-process-gone', (_e, details) => {
    log(`[load] ${id} renderer gone (${details.reason})`);
    if (details.reason === 'clean-exit') return;
    const t = now();
    if (t - lastCrashReload < CRASH_RELOAD_GAP_MS) {
      errors.fail(id, 'failed');
      return;
    }
    lastCrashReload = t;
    setTimeout(() => {
      if (!wc.isDestroyed()) wc.reload();
    }, 400);
  });

  wc.on('unresponsive', () => log(`[load] ${id} unresponsive`));
}

module.exports = { guardLoads };
