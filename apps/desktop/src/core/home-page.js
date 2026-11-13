'use strict';
// Island visibility rule (port of HomePageRule): is a URL the home page of one of the sources? The host must match the
// source's allowedHosts and the path must equal the start URL's path, or be "/" or empty. Query, hash and a trailing
// slash are ignored.
const { parseParts, hostMatches } = require('./url-parts');

/** Path without trailing slashes; "/" becomes "". */
function normalize(path) {
  let p = path ?? '';
  while (p.endsWith('/')) p = p.slice(0, -1);
  return p;
}

function hostAllowed(source, host) {
  return source.allowedHosts.some((p) => hostMatches(p, host)) || hostMatches(parseParts(source.url).host, host);
}

/** True when the URL is a web URL on one of the source's hosts. */
function belongsTo(source, url) {
  const page = parseParts(url);
  return page.isWeb && !!page.host && hostAllowed(source, page.host);
}

function isHome(source, url) {
  if (!belongsTo(source, url)) return false;
  const path = normalize(parseParts(url).path);
  return path === '' || path === normalize(parseParts(source.url).path);
}

const isHomeOfAny = (sources, url) => sources.some((s) => isHome(s, url));

/** Host plus normalised path: identifies a page for landing comparisons. */
function key(url) {
  const p = parseParts(url);
  return p.host + normalize(p.path);
}

module.exports = { isHome, isHomeOfAny, belongsTo, key };
