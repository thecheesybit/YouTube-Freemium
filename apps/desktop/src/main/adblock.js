'use strict';
const { toAdblockType } = require('./request-types');

const FILTER = { urls: ['http://*/*', 'https://*/*', 'ws://*/*', 'wss://*/*'] };
const LOG_EVERY = 50;

/** URL of the top-level page that issued the request (the "source" for third-party checks). */
function sourceUrlOf(details, type) {
  if (type === 'document') return details.url;
  try {
    const top = details.frame?.top?.url;
    if (top) return top;
  } catch {
    /* frame already gone */
  }
  return details.referrer || details.webContents?.getURL?.() || details.url;
}

/**
 * Cancels / redirects ad and tracker requests on `ses` using the native engine (when `isEnabled()`).
 * Main-frame documents of allowed sites are never blocked.
 */
function installNetworkBlocking(ses, engine, sites, stats, log, isEnabled = () => true) {
  if (!engine) return;
  ses.webRequest.onBeforeRequest(FILTER, (details, callback) => {
    try {
      if (!isEnabled()) return callback({});
      const type = toAdblockType(details.resourceType);
      if (type === 'document' && sites && sites.isAllowed && sites.isAllowed(details.url)) return callback({});
      if (stats) stats.checked++;
      const r = engine.check(details.url, sourceUrlOf(details, type), type);
      if (!r.matched) return callback({});
      if (stats) stats.recordBlock(details.url, type, !!r.redirect);
      if (stats && (stats.blocked + stats.redirected) % LOG_EVERY === 0 && log) log(stats.summary());
      if (r.redirect) return callback({ redirectURL: r.redirect });
      return callback({ cancel: true });
    } catch (e) {
      if (log) log('[adblock] check failed:', e.message);
      return callback({});
    }
  });
}

module.exports = { installNetworkBlocking, sourceUrlOf };
