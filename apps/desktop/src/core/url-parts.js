'use strict';
// Pure URL helpers (no electron import).

/** @returns {{scheme:string, host:string, path:string, isWeb:boolean}} never throws; empty parts for garbage. */
function parseParts(url) {
  try {
    const u = new URL(String(url ?? ''));
    const scheme = u.protocol.slice(0, -1).toLowerCase();
    return { scheme, host: u.hostname.toLowerCase(), path: u.pathname || '', isWeb: scheme === 'http' || scheme === 'https' };
  } catch {
    return { scheme: '', host: '', path: '', isWeb: false };
  }
}

/** "*.x.com" matches x.com and subdomains; other patterns match exactly. */
function hostMatches(pattern, host) {
  const h = String(host).toLowerCase();
  const p = String(pattern).toLowerCase();
  if (p.startsWith('*.')) {
    const base = p.slice(2);
    return h === base || h.endsWith('.' + base);
  }
  return h === p;
}

module.exports = { parseParts, hostMatches };
