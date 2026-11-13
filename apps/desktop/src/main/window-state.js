'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { screen } = require('electron');

const DEFAULT = { width: 1280, height: 800 };

function load(dir) {
  try {
    const s = JSON.parse(fs.readFileSync(path.join(dir, 'window-state.json'), 'utf8'));
    if (!Number.isFinite(s.width) || !Number.isFinite(s.height)) return { ...DEFAULT };
    if (Number.isFinite(s.x) && Number.isFinite(s.y)) {
      const d = screen.getDisplayMatching({ x: s.x, y: s.y, width: s.width, height: s.height });
      const a = d.workArea;
      const visible = s.x + s.width > a.x + 40 && s.x < a.x + a.width - 40 && s.y >= a.y - 10 && s.y < a.y + a.height - 40;
      if (!visible) return { width: Math.max(480, s.width), height: Math.max(320, s.height), maximized: !!s.maximized };
    }
    return { ...s, width: Math.max(480, s.width), height: Math.max(320, s.height) };
  } catch {
    return { ...DEFAULT };
  }
}

/** Persists bounds (debounced) and the maximized flag. */
function track(win, dir) {
  let timer = null;
  const save = () => {
    clearTimeout(timer);
    timer = null;
    if (win.isDestroyed() || win.isFullScreen() || win.isMinimized()) return;
    const state = { ...win.getNormalBounds(), maximized: win.isMaximized() };
    try {
      fs.mkdirSync(dir, { recursive: true });
      fs.writeFileSync(path.join(dir, 'window-state.json'), JSON.stringify(state));
    } catch {
      /* best effort */
    }
  };
  const later = () => {
    clearTimeout(timer);
    timer = setTimeout(save, 500);
  };
  win.on('resize', later);
  win.on('move', later);
  win.on('maximize', later);
  win.on('unmaximize', later);
  win.on('close', save);
}

module.exports = { load, track };
