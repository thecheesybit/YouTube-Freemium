'use strict';
// Small persistent preferences (userData/prefs.json). Never cleared automatically. Writes are atomic (tmp + rename)
// and debounced; the in-memory copy is replaced, never mutated.
const crypto = require('node:crypto');
const fs = require('node:fs');
const path = require('node:path');

const DEFAULTS = Object.freeze({
  defaultSourceId: null,
  askEveryLaunch: false,
  lastSourceId: null,
  adblockEnabled: true,
  islandY: 0.4,
  instanceId: null,
});

class Prefs {
  constructor(dir, log = () => {}) {
    this.file = path.join(dir, 'prefs.json');
    this.log = log;
    this.timer = null;
    this.data = { ...DEFAULTS, ...this.read() };
  }

  read() {
    try {
      const o = JSON.parse(fs.readFileSync(this.file, 'utf8'));
      return o && typeof o === 'object' && !Array.isArray(o) ? o : {};
    } catch {
      return {};
    }
  }

  get(key) {
    return this.data[key];
  }

  set(key, value) {
    if (this.data[key] === value) return;
    this.data = { ...this.data, [key]: value };
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.flush(), 150);
  }

  flush() {
    clearTimeout(this.timer);
    this.timer = null;
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      const tmp = `${this.file}.tmp`;
      fs.writeFileSync(tmp, JSON.stringify(this.data));
      fs.renameSync(tmp, this.file);
    } catch (e) {
      this.log('[prefs] save failed:', e.message);
    }
  }

  /** Random per-install id used by the remote-config fetch. */
  instanceId() {
    let id = this.data.instanceId;
    if (!id) {
      id = crypto.randomBytes(11).toString('hex');
      this.set('instanceId', id);
    }
    return id;
  }
}

module.exports = { Prefs, DEFAULTS };
