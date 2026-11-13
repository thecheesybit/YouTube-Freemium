'use strict';
// Picture-in-Picture via Chromium's own floating window: frameless, always on top, resizable, with play/pause, +-10 s,
// "back to tab" and close. The page is never restyled or moved between windows, so leaving PiP restores it exactly
// (the previous implementation re-parented the view and isolated the video with CSS, which broke as soon as YouTube
// re-laid out its player in the small viewport).
const { pipBlocker } = require('../core/pip-rules');

const POLL_MS = 250;
const MINI_RESTORE_WAIT_MS = 10000;

// Runs in the page's main world with a user gesture: picks the playing main video and opens its PiP window.
const REQUEST_JS = `(async () => {
  try {
    if (document.pictureInPictureElement) return 'already';
    const vids = Array.from(document.querySelectorAll('video'));
    const main = document.querySelector('video.html5-main-video');
    const v = main && main.readyState > 0 ? main : vids.filter((x) => x.readyState > 0 && x.videoWidth > 0)
      .sort((a, b) => b.videoWidth * b.videoHeight - a.videoWidth * a.videoHeight)[0];
    if (!v) return 'novideo';
    v.disablePictureInPicture = false;
    await v.requestPictureInPicture();
    return 'ok';
  } catch (e) { return 'error:' + (e && e.name); }
})()`;

const EXIT_JS = `(async () => { try { if (document.pictureInPictureElement) await document.exitPictureInPicture(); return 'ok'; } catch (e) { return 'error:' + (e && e.name); } })()`;

class PipManager {
  /** @param {{mainWin:Electron.BrowserWindow, views:import('./views').Views, media:import('./media').MediaManager, getSetting:(k:string)=>any, log?:Function}} o */
  constructor({ mainWin, views, media, getSetting, log = () => {} }) {
    this.mainWin = mainWin;
    this.views = views;
    this.media = media;
    this.getSetting = getSetting;
    this.log = log;
    this.isInPip = false;
    this.busy = false;
    this.activeSourceId = null;
  }

  pathnameOf(view) {
    try {
      return new URL(view.webContents.getURL()).pathname;
    } catch {
      return '';
    }
  }

  /** @param {boolean} explicit the user asked (button/shortcut): the page decides whether a video is ready */
  blocker(explicit = false) {
    const view = this.views.activeView();
    if (!view) return 'no-view';
    if (this.busy) return 'busy';
    return pipBlocker({
      enabled: this.getSetting('pip') !== false,
      inPip: this.isInPip,
      sourceId: this.views.activeId,
      media: explicit ? { isVideo: true, playing: true } : this.media.getActiveState(),
      pathname: this.pathnameOf(view)
    });
  }

  canEnterPip() {
    return this.blocker() === null;
  }

  /** Opens the PiP window for the active view. Resolves true when the window is up. */
  async enter({ explicit = false } = {}) {
    const why = this.blocker(explicit);
    if (why) {
      const m = this.media.getActiveState();
      this.log(`[pip] not entered (${why}) playing=${!!m.playing} isVideo=${!!m.isVideo} src=${this.views.activeId}`);
      return false;
    }
    const sourceId = this.views.activeId;
    const wc = this.views.activeView().webContents;
    this.busy = true;
    try {
      // Tells the page it is in PiP (hides its PiP button, pauses ad UI); a mini player is turned into the real page first.
      const prep = await wc.executeJavaScript(`window.__ytf_setPip ? window.__ytf_setPip(true, true) : 'ok'`, true);
      if (prep === 'mini') await this.waitForPlayableVideo(wc);
      const result = await wc.executeJavaScript(REQUEST_JS, true);
      if (result !== 'ok' && result !== 'already') {
        this.log(`[pip] not entered (${result})`);
        await wc.executeJavaScript(`window.__ytf_setPip && window.__ytf_setPip(false, true)`, true).catch(() => {});
        return false;
      }
      this.isInPip = true;
      this.activeSourceId = sourceId;
      this.views.pill.sm.setPip(true);
      return true;
    } catch (e) {
      this.log(`[pip] failed: ${e && e.message}`);
      return false;
    } finally {
      this.busy = false;
    }
  }

  async waitForPlayableVideo(wc) {
    const until = Date.now() + MINI_RESTORE_WAIT_MS;
    while (Date.now() < until) {
      await new Promise((r) => setTimeout(r, POLL_MS));
      if (wc.isDestroyed()) return false;
      if (wc.isLoading()) continue;
      const ready = await wc.executeJavaScript(
        `(() => { const v = document.querySelector('video.html5-main-video'); return !!(v && v.readyState > 0 && v.videoWidth > 0); })()`
      ).catch(() => false);
      if (ready) return true;
    }
    return false;
  }

  /** Called when the page reports its video left PiP (window closed, "back to tab" or an exit from the page). */
  onPageLeft(sourceId) {
    if (!this.isInPip) return;
    this.isInPip = false;
    this.views.pill.sm.setPip(false);
    const view = this.views.siteViews.get(sourceId || this.activeSourceId);
    if (view && !view.webContents.isDestroyed()) {
      view.webContents.executeJavaScript(`window.__ytf_setPip && window.__ytf_setPip(false, true)`, true).catch(() => {});
    }
    this.restoreMainWindow();
  }

  restoreMainWindow() {
    const win = this.mainWin;
    if (!win || win.isDestroyed()) return;
    if (win.isMinimized()) win.restore();
    win.show();
    win.focus();
  }

  async exit() {
    if (!this.isInPip) return;
    const view = this.views.siteViews.get(this.activeSourceId);
    if (view && !view.webContents.isDestroyed()) {
      await view.webContents.executeJavaScript(EXIT_JS, true).catch(() => {});
    }
    this.onPageLeft(this.activeSourceId);
  }

  async toggle() {
    if (this.isInPip) await this.exit();
    else await this.enter({ explicit: true });
  }
}

module.exports = { PipManager };
