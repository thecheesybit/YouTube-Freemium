'use strict';
const fs = require('node:fs');
const path = require('node:path');

const DEFAULTS = {
  pip: true,
  backgroundPlay: true,
  keepInTray: true,
  allowBoth: false,
  showIntro: true,
  adblock: true,
  cosmetic: true,
  startWithWindows: false,
  hardwareAcceleration: true
};

class SettingsStore {
  constructor(userDir) {
    this.file = path.join(userDir, 'settings.json');
    this.data = { ...DEFAULTS };
    this.load();
  }

  load() {
    try {
      if (fs.existsSync(this.file)) {
        const parsed = JSON.parse(fs.readFileSync(this.file, 'utf8'));
        this.data = { ...DEFAULTS, ...parsed };
      }
    } catch {
      this.data = { ...DEFAULTS };
    }
  }

  save() {
    try {
      fs.mkdirSync(path.dirname(this.file), { recursive: true });
      fs.writeFileSync(this.file, JSON.stringify(this.data, null, 2));
    } catch {}
  }

  get(key) {
    return this.data[key] !== undefined ? this.data[key] : DEFAULTS[key];
  }

  set(key, val) {
    this.data[key] = val;
    this.save();
  }

  all() {
    return { ...this.data };
  }
}

module.exports = { SettingsStore, DEFAULTS };
