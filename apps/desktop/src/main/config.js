'use strict';
// Loads the generated app-config.json: static app facts + the default live config (source URLs obfuscated).
const fs = require('node:fs');
const path = require('node:path');
const { createObfuscator } = require('../core/obfuscator');
const live = require('../core/live-config');

const ALLOWED_SOURCE_IDS = new Set(['youtube', 'youtube-music']);

/** @returns {{app:object, obf:object, defaults:object}} */
function loadAppConfig(gen) {
  const raw = JSON.parse(fs.readFileSync(path.join(gen, 'app-config.json'), 'utf8'));
  const obf = createObfuscator(raw.obfKey);
  const baseDefaults = live.merge(null, raw.defaults, obf.decode);

  // Rejects extra sources (hard rule: exactly YouTube and YouTube Music)
  const sources = baseDefaults.sources.filter((s) => ALLOWED_SOURCE_IDS.has(s.id));
  if (sources.length === 0) {
    throw new Error('Config contains no valid sources for YouTube Freemium');
  }

  const defaults = { ...baseDefaults, sources };
  return { app: raw, obf, defaults };
}

module.exports = { loadAppConfig, ALLOWED_SOURCE_IDS };
