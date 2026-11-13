'use strict';
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { WebContentsView } = require('electron');
const { uiDir, uiPreloadPath } = require('./paths');

const RED = '#FF0033';
const PREFS = { sandbox: true, contextIsolation: true, nodeIntegration: false };
const dataUrl = (html) => 'data:text/html;charset=utf-8,' + encodeURIComponent(html);

class PillStateMachine {
  constructor({
    onShow,
    onHide,
    minVisibleMs = 1400,
    watchdogMs = 3500,
    now = () => Date.now(),
    setTimeout = global.setTimeout,
    clearTimeout = global.clearTimeout
  } = {}) {
    this.onShow = onShow;
    this.onHide = onHide;
    this.minVisibleMs = minVisibleMs;
    this.watchdogMs = watchdogMs;
    this.now = now;
    this._setTimeout = setTimeout;
    this._clearTimeout = clearTimeout;

    this.visible = false;
    this.shownAt = 0;
    this.watchdogTimer = null;
    this.pendingHideTimer = null;
    this.inPip = false;
  }

  setPip(inPip) {
    this.inPip = !!inPip;
    if (this.inPip && this.visible) {
      this.forceHide();
    }
  }

  show() {
    if (this.inPip) return;
    this.resetWatchdog();
    if (this.pendingHideTimer) {
      this._clearTimeout(this.pendingHideTimer);
      this.pendingHideTimer = null;
    }
    if (this.visible) return;
    this.visible = true;
    this.shownAt = this.now();
    this.onShow?.();
  }

  heartbeat() {
    if (this.inPip) return;
    if (!this.visible) {
      this.show();
    } else {
      this.resetWatchdog();
    }
  }

  resetWatchdog() {
    if (this.watchdogTimer) this._clearTimeout(this.watchdogTimer);
    this.watchdogTimer = this._setTimeout(() => this.hide(), this.watchdogMs);
  }

  hide() {
    if (this.watchdogTimer) {
      this._clearTimeout(this.watchdogTimer);
      this.watchdogTimer = null;
    }
    if (!this.visible || this.pendingHideTimer) return;
    const elapsed = this.now() - this.shownAt;
    const remaining = Math.max(0, this.minVisibleMs - elapsed);
    if (remaining <= 0) {
      this.visible = false;
      this.onHide?.();
    } else {
      this.pendingHideTimer = this._setTimeout(() => {
        this.pendingHideTimer = null;
        this.visible = false;
        this.onHide?.();
      }, remaining);
    }
  }

  forceHide() {
    if (this.watchdogTimer) {
      this._clearTimeout(this.watchdogTimer);
      this.watchdogTimer = null;
    }
    if (this.pendingHideTimer) {
      this._clearTimeout(this.pendingHideTimer);
      this.pendingHideTimer = null;
    }
    this.visible = false;
    this.onHide?.();
  }
}

/** Thin red progress bar at the top edge; only visible while a navigation runs. */
function createProgressBar() {
  const view = new WebContentsView({ webPreferences: PREFS });
  view.setBackgroundColor('#00000000');
  view.setVisible(false);
  const html =
    `<body style="margin:0;background:transparent;overflow:hidden"><div id=b style="height:3px;width:0;background:${RED};` +
    `transition:width .25s ease-out,opacity .3s;opacity:0"></div></body>`;
  const ready = view.webContents.loadURL(dataUrl(html)).catch(() => {});
  let timer = null;
  let p = 0;
  const paint = (width, opacity) =>
    ready.then(() => view.webContents.executeJavaScript(`b.style.width='${width}%';b.style.opacity=${opacity}`)).catch(() => {});

  return {
    view,
    start() {
      clearInterval(timer);
      p = 0.08;
      view.setVisible(true);
      paint(8, 1);
      timer = setInterval(() => {
        p += (0.9 - p) * 0.07;
        paint(Math.round(p * 100), 1);
      }, 220);
    },
    done() {
      clearInterval(timer);
      timer = null;
      paint(100, 1);
      setTimeout(() => paint(100, 0), 150);
      setTimeout(() => {
        if (!timer && !view.webContents.isDestroyed()) view.setVisible(false);
      }, 500);
    },
  };
}

/** Skipping ads pill overlay view */
function createAdPillView() {
  const view = new WebContentsView({
    webPreferences: {
      sandbox: true,
      contextIsolation: true,
      nodeIntegration: false
    }
  });
  view.setBackgroundColor('#00000000');
  view.setVisible(false);

  const file = path.join(uiDir(), 'pill.html');
  const ready = view.webContents.loadURL(pathToFileURL(file).href).catch(() => {});

  const sm = new PillStateMachine({
    onShow: () => {
      view.setVisible(true);
      ready.then(() => view.webContents.executeJavaScript('showPill()')).catch(() => {});
    },
    onHide: () => {
      ready.then(() => view.webContents.executeJavaScript('hidePill()')).catch(() => {});
      setTimeout(() => {
        if (!sm.visible && !view.webContents.isDestroyed()) {
          view.setVisible(false);
        }
      }, 300);
    }
  });

  return { view, sm };
}

module.exports = { PillStateMachine, createProgressBar, createAdPillView };
