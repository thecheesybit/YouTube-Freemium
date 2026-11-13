'use strict';
// The live (remotely overridable) configuration, a port of the Android LiveConfig. Immutable: `merge` returns a new
// frozen object; invalid input throws and callers keep the previous config. Every remote field is optional.
const { parseParts } = require('./url-parts');

const PALETTE = ['#E50914', '#22C55E', '#FACC15', '#3B82F6', '#A855F7', '#F97316'];
const ID = /^[a-z0-9-]+$/;
const HOST = /^(\*\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$/;
const COLOR = /^#[0-9a-fA-F]{6}$/;
const MAX_TEXT = 200;

const bad = (what) => new Error(`invalid config: ${what}`);

function text(o, key, max) {
  const v = o[key];
  if (typeof v !== 'string') throw bad(`${key} must be a string`);
  const s = v.trim();
  if (s.length > max) throw bad(`${key} too long`);
  return s;
}

function nonNegInt(o, key) {
  const v = o[key];
  if (typeof v !== 'number' || !Number.isInteger(v) || v < 0 || v > 2147483647) throw bad(`${key} out of range`);
  return v;
}

function hostList(a, withPath) {
  if (!Array.isArray(a)) throw bad('host list must be an array');
  return a.map((v) => {
    if (typeof v !== 'string') throw bad('host must be a string');
    const h = v.trim().toLowerCase();
    const hostPart = withPath && h.includes('/') ? h.slice(0, h.indexOf('/')) : h;
    if (h.length > 253 || !HOST.test(hostPart)) throw bad('host pattern');
    return h;
  });
}

/** Obfuscated host list (build output): every entry decoded with the build key. */
function encList(a, decode) {
  if (!Array.isArray(a)) throw bad('host list must be an array');
  return a.map((v) => decode(String(v)));
}

function parseSources(a, decode) {
  if (!Array.isArray(a) || a.length === 0) throw bad('sources empty');
  const ids = new Set();
  return a.map((s, i) => {
    if (!s || typeof s !== 'object') throw bad('source');
    const id = text(s, 'id', 40);
    if (!ID.test(id) || ids.has(id)) throw bad('source id');
    ids.add(id);
    const name = text(s, 'name', 40);
    if (!name) throw bad('source name');
    const url = s.urlEnc !== undefined ? decode(text(s, 'urlEnc', 2000)) : text(s, 'url', 2000);
    const p = parseParts(url);
    if (!p.isWeb || !p.host) throw bad('source url');
    let allowedHosts = [p.host, `*.${p.host}`];
    if (s.allowedHostsEnc !== undefined) allowedHosts = hostList(encList(s.allowedHostsEnc, decode), false);
    else if (s.allowedHosts !== undefined) allowedHosts = hostList(s.allowedHosts, false);
    if (allowedHosts.length === 0) throw bad('source hosts');
    let accent = PALETTE[i % PALETTE.length];
    if (s.accent !== undefined) {
      accent = text(s, 'accent', 7);
      if (!COLOR.test(accent)) throw bad('source accent');
    }
    const enabled = s.enabled === undefined ? true : s.enabled === true;
    return Object.freeze({ id, name, url, allowedHosts: Object.freeze(allowedHosts), accent: accent.toUpperCase(), enabled });
  });
}

const EMPTY = Object.freeze({
  contentVersion: 0,
  sources: [],
  recommendedSource: '',
  tagline: '',
  credit: '',
  splashMinMs: 600,
  chooser: { title: 'Choose your source', subtitle: '', cta: 'Continue', recommendedLabel: 'Recommended', alwaysLabel: 'Always open this source' },
  islandVisibility: 'home',
  minBuild: 0,
  updateMessage: '',
  extraAllowedHosts: [],
  authHosts: [],
  dohHosts: [],
});

/** Applies `json` (string or object) over `base` (null = empty defaults, sources required). */
function merge(base, json, decode) {
  let o = json;
  if (typeof json === 'string') {
    try {
      o = JSON.parse(json);
    } catch {
      throw bad('invalid JSON');
    }
  }
  if (!o || typeof o !== 'object' || Array.isArray(o)) throw bad('config is not an object');
  const from = base ?? EMPTY;
  const d = { ...from, chooser: { ...from.chooser } };
  if (o.contentVersion !== undefined) d.contentVersion = nonNegInt(o, 'contentVersion');
  if (o.sources !== undefined) d.sources = parseSources(o.sources, decode);
  if (o.recommendedSource !== undefined) d.recommendedSource = text(o, 'recommendedSource', 40);
  if (o.tagline !== undefined) d.tagline = text(o, 'tagline', 120);
  if (o.credit !== undefined) d.credit = text(o, 'credit', 60);
  const splash = o.splash;
  if (splash && typeof splash === 'object') {
    if (splash.tagline !== undefined) d.tagline = text(splash, 'tagline', 120);
    if (splash.credit !== undefined) d.credit = text(splash, 'credit', 60);
    if (splash.minMs !== undefined) d.splashMinMs = Math.min(nonNegInt(splash, 'minMs'), 3000);
  }
  const ch = o.chooser;
  if (ch && typeof ch === 'object') {
    if (ch.title !== undefined) d.chooser.title = text(ch, 'title', MAX_TEXT);
    if (ch.subtitle !== undefined) d.chooser.subtitle = text(ch, 'subtitle', MAX_TEXT);
    if (ch.cta !== undefined) d.chooser.cta = text(ch, 'cta', 40);
    if (ch.recommendedLabel !== undefined) d.chooser.recommendedLabel = text(ch, 'recommendedLabel', 40);
    if (ch.alwaysLabel !== undefined) d.chooser.alwaysLabel = text(ch, 'alwaysLabel', 80);
  }
  if (o.island && typeof o.island === 'object' && o.island.visibility !== undefined) {
    const v = text(o.island, 'visibility', 10);
    if (v !== 'home' && v !== 'always') throw bad('island.visibility');
    d.islandVisibility = v;
  }
  if (o.minBuild !== undefined) d.minBuild = nonNegInt(o, 'minBuild');
  if (o.updateMessage !== undefined) d.updateMessage = text(o, 'updateMessage', 400);
  if (o.extraAllowedHosts !== undefined) d.extraAllowedHosts = hostList(o.extraAllowedHosts, false);
  if (o.authHosts !== undefined) d.authHosts = hostList(o.authHosts, true);
  if (o.dohHostsEnc !== undefined) d.dohHosts = hostList(encList(o.dohHostsEnc, decode), false);
  else if (o.dohHosts !== undefined) d.dohHosts = hostList(o.dohHosts, false);
  return validate(d);
}

function validate(d) {
  if (!d.sources.some((s) => s.enabled)) throw bad('no enabled source');
  const rec = d.recommendedSource || d.sources.find((s) => s.enabled).id;
  if (!d.sources.some((s) => s.id === rec)) throw bad('recommendedSource is not a source');
  return Object.freeze({ ...d, recommendedSource: rec, chooser: Object.freeze(d.chooser) });
}

const enabledSources = (c) => c.sources.filter((s) => s.enabled);
const find = (c, id) => (id ? c.sources.find((s) => s.id === id) ?? null : null);

/** Recommended source when enabled, else the first enabled one. */
function recommended(c) {
  const r = find(c, c.recommendedSource);
  return r && r.enabled ? r : enabledSources(c)[0];
}

/** Union of all enabled sources' hosts plus extraAllowedHosts. */
const allAllowedHosts = (c) => [...new Set([...enabledSources(c).flatMap((s) => s.allowedHosts), ...c.extraAllowedHosts])];

const startHosts = (c) => new Set(enabledSources(c).map((s) => parseParts(s.url).host));

module.exports = { merge, enabledSources, find, recommended, allAllowedHosts, startHosts, PALETTE };
