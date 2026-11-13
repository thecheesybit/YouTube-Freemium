'use strict';
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { BrowserWindow, ipcMain } = require('electron');
const fs = require('node:fs');
const { uiDir, uiPreloadPath } = require('./paths');

class SplashTimeline {
  constructor({
    onIntro = () => {},
    onSpinner = () => {},
    onPhaseOneExit = () => {},
    onCredit = () => {},
    onFlip = () => {},
    onComplete = () => {},
    setTimeout = global.setTimeout,
    clearTimeout = global.clearTimeout
  } = {}) {
    this.onIntro = onIntro;
    this.onSpinner = onSpinner;
    this.onPhaseOneExit = onPhaseOneExit;
    this.onCredit = onCredit;
    this.onFlip = onFlip;
    this.onComplete = onComplete;
    this._setTimeout = setTimeout;
    this._clearTimeout = clearTimeout;
    this.timers = [];
    this.completed = false;
  }

  start() {
    this.onIntro();
    this.timers.push(this._setTimeout(() => this.onSpinner(), 400));
    this.timers.push(this._setTimeout(() => this.onPhaseOneExit(), 2000));
    this.timers.push(this._setTimeout(() => this.onCredit(), 2350));
    this.timers.push(this._setTimeout(() => this.onFlip(), 3150));
    this.timers.push(this._setTimeout(() => {
      this.completed = true;
      this.onComplete();
    }, 3500));
  }

  cancel() {
    for (const t of this.timers) this._clearTimeout(t);
    this.timers = [];
  }
}

function showSplashWindow({ onFinished, noSplash = false, iconPath }) {
  if (noSplash) {
    onFinished();
    return null;
  }

  const splash = new BrowserWindow({
    width: 600,
    height: 480,
    frame: false,
    transparent: false,
    backgroundColor: '#000000',
    center: true,
    alwaysOnTop: true,
    resizable: false,
    skipTaskbar: true,
    icon: iconPath,
    webPreferences: {
      preload: uiPreloadPath(),
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false
    }
  });

  let finished = false;
  const finish = () => {
    if (finished) return;
    finished = true;
    ipcMain.removeHandler('ytf:splash-done');
    if (!splash.isDestroyed()) splash.destroy();
    onFinished();
  };

  ipcMain.handleOnce('ytf:splash-done', () => finish());

  // Fail-safe timeout in case page script fails
  setTimeout(() => finish(), 4000);

  let hash = '';
  try {
    hash = '#' + encodeURIComponent('data:image/png;base64,' + fs.readFileSync(iconPath).toString('base64'));
  } catch {
    /* no icon file: the splash still runs without it */
  }
  const file = path.join(uiDir(), 'splash.html');
  splash.loadURL(pathToFileURL(file).href + hash).catch(() => finish());
  return splash;
}

module.exports = { SplashTimeline, showSplashWindow };
