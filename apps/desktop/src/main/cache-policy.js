'use strict';
// Port of CachePolicy.java: strictly caches only versioned JS, CSS and fonts under /s/
// NEVER caches images, audio, video, media segments, range or auth requests.
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const Kind = {
  NONE: 'NONE',
  IMAGE: 'IMAGE',
  FONT: 'FONT',
  STATIC: 'STATIC'
};

const DAY_MS = 24 * 60 * 60 * 1000;
const MAX_ENTRY_BYTES = 12 * 1024 * 1024;
const DEFAULT_MAX_BYTES = 100 * 1024 * 1024;
const DEFAULT_TTL_MS = 14 * DAY_MS;

const EXT_REGEX = /\.([a-z0-9]{2,5})$/i;
const HASHED_REGEX = /[.\-_][A-Za-z0-9_-]{8,}\.(js|mjs|css)$/i;
const VERSIONED_QUERY = /(^|&)(v|ver|version|hash|h)=[^&]+/i;
const SIGNED_QUERY = /(^|&)(token|sig|signature|expires|x-amz-[a-z-]+|policy|key-pair-id)=/i;
const YT_STATIC = /^https?:\/\/[^/]*(youtube\.com|ytimg\.com|gstatic\.com)\/s\//i;

const IMAGE_EXT = new Set(['png', 'jpg', 'jpeg', 'gif', 'webp', 'avif', 'svg', 'ico', 'bmp']);
const FONT_EXT = new Set(['woff', 'woff2', 'ttf', 'otf', 'eot']);
const MEDIA_EXT = new Set(['m3u8', 'ts', 'm4s', 'mp4', 'webm', 'mkv', 'mp3', 'aac', 'm4a', 'mpd', 'flv', 'key', 'vtt', 'srt']);

function getHeader(headers, name) {
  if (!headers) return null;
  const target = name.toLowerCase();
  for (const [k, v] of Object.entries(headers)) {
    if (k.toLowerCase() === target) {
      return Array.isArray(v) ? v[0] : v;
    }
  }
  return null;
}

function classifyRequest(method, url, headers = {}, allowImages = false) {
  if (!method || method.toUpperCase() !== 'GET' || !url) return Kind.NONE;
  const lower = url.toLowerCase();
  if (!lower.startsWith('https://') && !lower.startsWith('http://')) return Kind.NONE;

  if (getHeader(headers, 'range') || getHeader(headers, 'authorization')) {
    return Kind.NONE;
  }

  let cleanPath = lower;
  let query = '';
  const qIdx = lower.indexOf('?');
  if (qIdx >= 0) {
    cleanPath = lower.substring(0, qIdx);
    query = url.substring(qIdx + 1);
  }
  const hIdx = cleanPath.indexOf('#');
  if (hIdx >= 0) cleanPath = cleanPath.substring(0, hIdx);

  if (SIGNED_QUERY.test(query)) return Kind.NONE;

  const m = cleanPath.match(EXT_REGEX);
  const ext = m ? m[1].toLowerCase() : null;

  if (ext && MEDIA_EXT.has(ext)) return Kind.NONE;
  if (ext && IMAGE_EXT.has(ext)) return allowImages ? Kind.IMAGE : Kind.NONE;
  if (ext && FONT_EXT.has(ext)) return Kind.FONT;

  const accept = getHeader(headers, 'accept');
  if (accept && accept.toLowerCase().startsWith('image/')) {
    return allowImages ? Kind.IMAGE : Kind.NONE;
  }

  if ((ext === 'js' || ext === 'mjs' || ext === 'css') &&
      (YT_STATIC.test(lower) || HASHED_REGEX.test(cleanPath) || VERSIONED_QUERY.test(query))) {
    return Kind.STATIC;
  }

  return Kind.NONE;
}

function cacheableResponse(kind, status, headers = {}, size = 0) {
  if (kind === Kind.NONE || status !== 200) return false;
  if (size <= 0 || size > MAX_ENTRY_BYTES) return false;

  const cc = (getHeader(headers, 'cache-control') || '').toLowerCase();
  if (cc.includes('no-store') || cc.includes('private')) return false;

  const vary = (getHeader(headers, 'vary') || '').trim();
  if (vary === '*') return false;

  const ct = (getHeader(headers, 'content-type') || '').toLowerCase();
  if (ct.startsWith('text/html') || ct.includes('json') || ct.startsWith('video/') ||
      ct.startsWith('audio/') || ct.includes('mpegurl') || ct.includes('event-stream')) {
    return false;
  }

  if (kind === Kind.IMAGE && !(ct.startsWith('image/') || ct === '' || ct.includes('octet-stream'))) {
    return false;
  }
  if (kind === Kind.STATIC && !(ct.includes('javascript') || ct.includes('css') || ct.includes('ecmascript'))) {
    return false;
  }
  if (kind === Kind.FONT && !(ct.startsWith('font/') || ct.includes('font') || ct.includes('octet-stream'))) {
    return false;
  }

  return true;
}

function isExpired(storedAt, now, ttlMs = DEFAULT_TTL_MS) {
  return storedAt <= 0 || now - storedAt >= ttlMs || storedAt - now > DAY_MS;
}

class DiskAssetCache {
  constructor(cacheDir, { maxBytes = DEFAULT_MAX_BYTES, ttlMs = DEFAULT_TTL_MS } = {}) {
    this.dir = cacheDir;
    this.maxBytes = maxBytes;
    this.ttlMs = ttlMs;
    this.index = new Map(); // key -> { file, size, storedAt, accessedAt }
    this.totalSize = 0;
    this.init();
  }

  init() {
    try {
      fs.mkdirSync(this.dir, { recursive: true });
      const idxFile = path.join(this.dir, 'index.json');
      if (fs.existsSync(idxFile)) {
        const list = JSON.parse(fs.readFileSync(idxFile, 'utf8'));
        const now = Date.now();
        for (const it of list) {
          if (!isExpired(it.storedAt, now, this.ttlMs) && fs.existsSync(it.file)) {
            this.index.set(it.key, it);
            this.totalSize += it.size;
          } else {
            try { fs.unlinkSync(it.file); } catch {}
          }
        }
      }
    } catch {}
  }

  saveIndex() {
    try {
      const list = Array.from(this.index.values());
      fs.writeFileSync(path.join(this.dir, 'index.json'), JSON.stringify(list));
    } catch {}
  }

  keyOf(url) {
    return crypto.createHash('sha256').update(url).digest('hex');
  }

  evictLRU(requiredBytes) {
    const entries = Array.from(this.index.values()).sort((a, b) => a.accessedAt - b.accessedAt);
    while ((this.totalSize + requiredBytes > this.maxBytes) && entries.length > 0) {
      const oldest = entries.shift();
      this.index.delete(oldest.key);
      this.totalSize -= oldest.size;
      try { fs.unlinkSync(oldest.file); } catch {}
    }
  }

  get(url) {
    const key = this.keyOf(url);
    const it = this.index.get(key);
    if (!it) return null;
    const now = Date.now();
    if (isExpired(it.storedAt, now, this.ttlMs)) {
      this.index.delete(key);
      this.totalSize -= it.size;
      try { fs.unlinkSync(it.file); } catch {}
      return null;
    }
    it.accessedAt = now;
    try {
      return fs.readFileSync(it.file);
    } catch {
      return null;
    }
  }

  set(url, buffer, kind) {
    if (!buffer || buffer.length > MAX_ENTRY_BYTES) return;
    const key = this.keyOf(url);
    const size = buffer.length;
    this.evictLRU(size);
    const file = path.join(this.dir, `${key}.bin`);
    try {
      fs.writeFileSync(file, buffer);
      const now = Date.now();
      const existing = this.index.get(key);
      if (existing) this.totalSize -= existing.size;
      const entry = { key, file, size, storedAt: now, accessedAt: now, kind };
      this.index.set(key, entry);
      this.totalSize += size;
      this.saveIndex();
    } catch {}
  }

  clear() {
    this.index.clear();
    this.totalSize = 0;
    try {
      const files = fs.readdirSync(this.dir);
      for (const f of files) fs.unlinkSync(path.join(this.dir, f));
    } catch {}
  }
}

module.exports = {
  Kind,
  classifyRequest,
  cacheableResponse,
  isExpired,
  DiskAssetCache,
  DAY_MS,
  MAX_ENTRY_BYTES,
  DEFAULT_MAX_BYTES,
  DEFAULT_TTL_MS
};
