'use strict';
// A real-Chrome user agent: drop the "Electron/x" token and the app-name token Electron inserts.

function escapeRegExp(s) {
  return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

/** @param {string} ua session.defaultUserAgent  @param {string[]} appTokens names to strip (app name, package name) */
function chromeUserAgent(ua, appTokens = []) {
  let out = (ua || '').replace(/\s+Electron\/\S+/g, '');
  for (const name of appTokens) {
    if (name) out = out.replace(new RegExp(`\\s+${escapeRegExp(name)}\\/\\S+`, 'gi'), '');
  }
  // Safety net: any other "name/version" token directly before Chrome/.
  out = out.replace(/\s+[^\s/]+\/\S+(?=\s+Chrome\/)/g, '');
  return out.replace(/\s{2,}/g, ' ').trim();
}

/** Installs onBeforeSendHeaders interceptor to strip X-Requested-With and maintain consistent UA & sec-ch-ua */
function installUserAgentHeaders(sess, userAgent) {
  sess.webRequest.onBeforeSendHeaders((details, callback) => {
    const headers = { ...details.requestHeaders };
    headers['User-Agent'] = userAgent;
    delete headers['X-Requested-With'];
    delete headers['x-requested-with'];
    callback({ requestHeaders: headers });
  });
}

module.exports = { chromeUserAgent, installUserAgentHeaders };
