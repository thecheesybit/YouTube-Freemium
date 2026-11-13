'use strict';
// Host matching + URL helpers. Pure (no electron import) so they are unit-testable.

/** "*.x.com" matches x.com and every subdomain; other patterns match exactly. */
function hostMatches(pattern, host) {
  const h = String(host).toLowerCase();
  const p = String(pattern).toLowerCase();
  if (p.startsWith('*.')) {
    const base = p.slice(2);
    return h === base || h.endsWith('.' + base);
  }
  return h === p;
}

function parseUrl(url) {
  try {
    return new URL(url);
  } catch {
    return null;
  }
}

function isHttp(url) {
  const u = parseUrl(url);
  return !!u && (u.protocol === 'http:' || u.protocol === 'https:');
}

/** True when `url` is http(s) and its host matches one of `allowedHosts`. */
function isAllowedUrl(url, allowedHosts) {
  const u = parseUrl(url);
  if (!u || (u.protocol !== 'http:' && u.protocol !== 'https:')) return false;
  return allowedHosts.some((p) => hostMatches(p, u.hostname));
}

module.exports = { hostMatches, isHttp, isAllowedUrl, parseUrl };
