'use strict';
const { powerSaveBlocker, globalShortcut, app } = require('electron');

class MediaManager {
  /** @param {{views:import('./views').Views, getSetting:(k:string)=>any, onTrayUpdate?:Function}} o */
  constructor({ win, views, getSetting, onTrayUpdate }) {
    this.win = win;
    this.views = views;
    this.getSetting = getSetting;
    this.onTrayUpdate = onTrayUpdate;

    this.powerBlockerId = null;
    this.powerBlockerTimer = null;
    this.states = new Map(); // sourceId -> state
    this.activeSource = null;

    this.registerShortcuts();
  }

  registerShortcuts() {
    try {
      globalShortcut.register('MediaPlayPause', () => this.togglePlayPause());
      globalShortcut.register('MediaNextTrack', () => this.next());
      globalShortcut.register('MediaPreviousTrack', () => this.previous());
      globalShortcut.register('MediaStop', () => this.pause());
    } catch {
      /* global shortcuts best effort */
    }
  }

  unregisterShortcuts() {
    try {
      globalShortcut.unregister('MediaPlayPause');
      globalShortcut.unregister('MediaNextTrack');
      globalShortcut.unregister('MediaPreviousTrack');
      globalShortcut.unregister('MediaStop');
    } catch {}
  }

  isAnyPlaying() {
    for (const s of this.states.values()) {
      if (s.playing) return true;
    }
    return false;
  }

  getActiveState() {
    return this.states.get(this.views.activeId) || { playing: false, title: '', artist: '' };
  }

  updateMediaState(sourceId, state) {
    const prevState = this.states.get(sourceId);
    this.states.set(sourceId, state);

    if (state.playing && (!prevState || !prevState.playing)) {
      // Audio arbitration: pause other sources if allowBoth is off
      const allowBoth = this.getSetting('allowBoth') === true;
      if (!allowBoth) {
        for (const [id, v] of this.views.siteViews.entries()) {
          if (id !== sourceId) {
            v.webContents.executeJavaScript('window.__ytf_pause?.()').catch(() => {});
          }
        }
      }
    }

    if (this.isAnyPlaying()) {
      this.acquirePowerBlocker();
    } else {
      this.scheduleReleasePowerBlocker();
    }

    // Update window title if from active source
    if (this.views.activeId === sourceId) {
      if (state.title) {
        this.win.setTitle(`${state.title} - YouTube Freemium`);
      } else {
        this.win.setTitle('YouTube Freemium');
      }
    }

    if (this.onTrayUpdate) this.onTrayUpdate();
  }

  acquirePowerBlocker() {
    if (this.powerBlockerTimer) {
      clearTimeout(this.powerBlockerTimer);
      this.powerBlockerTimer = null;
    }
    if (this.powerBlockerId === null || !powerSaveBlocker.isStarted(this.powerBlockerId)) {
      try {
        this.powerBlockerId = powerSaveBlocker.start('prevent-app-suspension');
      } catch {}
    }
  }

  scheduleReleasePowerBlocker() {
    if (this.powerBlockerTimer) return;
    this.powerBlockerTimer = setTimeout(() => {
      this.powerBlockerTimer = null;
      if (!this.isAnyPlaying() && this.powerBlockerId !== null) {
        try {
          if (powerSaveBlocker.isStarted(this.powerBlockerId)) {
            powerSaveBlocker.stop(this.powerBlockerId);
          }
        } catch {}
        this.powerBlockerId = null;
      }
    }, 2 * 60 * 1000); // 2 minutes
  }

  play() {
    const active = this.views.activeView();
    if (active) active.webContents.executeJavaScript('window.__ytf_play?.()').catch(() => {});
  }

  pause() {
    const active = this.views.activeView();
    if (active) active.webContents.executeJavaScript('window.__ytf_pause?.()').catch(() => {});
  }

  togglePlayPause() {
    const state = this.getActiveState();
    if (state.playing) this.pause();
    else this.play();
  }

  seek(seconds) {
    const active = this.views.activeView();
    if (active) active.webContents.executeJavaScript(`window.__ytf_seek?.(${Number(seconds)})`).catch(() => {});
  }

  seekTo(seconds) {
    const active = this.views.activeView();
    if (active) active.webContents.executeJavaScript(`window.__ytf_seekTo?.(${Number(seconds)})`).catch(() => {});
  }

  next() {
    const active = this.views.activeView();
    if (active) active.webContents.executeJavaScript('window.__ytf_next?.()').catch(() => {});
  }

  previous() {
    const active = this.views.activeView();
    if (active) active.webContents.executeJavaScript('window.__ytf_prev?.()').catch(() => {});
  }

  setBg(isBg) {
    for (const v of this.views.siteViews.values()) {
      v.webContents.executeJavaScript(`window.__ytf_setBg?.(${!!isBg})`).catch(() => {});
    }
  }

  destroy() {
    this.unregisterShortcuts();
    if (this.powerBlockerId !== null) {
      try { powerSaveBlocker.stop(this.powerBlockerId); } catch {}
    }
  }
}

module.exports = { MediaManager };
