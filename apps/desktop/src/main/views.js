'use strict';
// Manages the layers of the main window:
// - Two persistent site WebContentsViews (YouTube and YouTube Music)
// - Panel view (Settings, Chooser, Error, About)
// - Island view (Source switcher on right edge)
// - Pill view (Skipping ads indicator)
// - Progress bar view (3px red line at top)
const path = require('node:path');
const { pathToFileURL } = require('node:url');
const { WebContentsView } = require('electron');
const { uiDir, uiPreloadPath, preloadPath } = require('./paths');
const { createProgressBar, createAdPillView } = require('./overlays');
const { computeLayout, ISLAND } = require('../core/layout');

const ISLAND_COLLAPSED = Object.freeze({ w: ISLAND.collapsedW, h: ISLAND.collapsedH });
const ISLAND_EXPANDED_W = ISLAND.expandedW;

const uiPrefs = () => ({
  backgroundThrottling: false,
  preload: uiPreloadPath(),
  sandbox: true,
  contextIsolation: true,
  nodeIntegration: false
});

class Views {
  /** @param {{win:Electron.BrowserWindow, ses:Electron.Session, background:string, prefs:object, sources:Array}} o */
  constructor({ win, ses, background, prefs, sources = [] }) {
    this.win = win;
    this.prefs = prefs;
    this.panelShown = false;
    this.islandMode = 'collapsed';
    this.islandHeight = 220;
    this.dragTop = null;
    this.activeId = null;
    this.htmlFullscreen = false; // a page element (the video) is fullscreen inside a site view

    // Site views keyed by source ID
    this.siteViews = new Map();
    for (const src of sources) {
      const view = new WebContentsView({
        webPreferences: {
          session: ses,
          preload: preloadPath(),
          contextIsolation: true,
          sandbox: true,
          nodeIntegration: false,
          nodeIntegrationInSubFrames: true,
          webSecurity: true,
          allowRunningInsecureContent: false,
          autoplayPolicy: 'no-user-gesture-required',
          backgroundThrottling: false
        }
      });
      view.setBackgroundColor(background || '#0F0F0F');
      view.setVisible(false);
      this.siteViews.set(src.id, view);
      win.contentView.addChildView(view);
    }

    // The shell chrome (back/forward, drag area) is the window's own page; the site views sit below it.
    win.webContents.loadURL(pathToFileURL(path.join(uiDir(), 'chrome.html')).href).catch(() => {});
    this.panel = this.makeUi('panel.html', '#00000000');
    this.island = this.makeUi('island.html', '#00000000');
    this.pill = createAdPillView();
    this.bar = createProgressBar();

    win.contentView.addChildView(this.island);
    win.contentView.addChildView(this.pill.view);
    win.contentView.addChildView(this.bar.view);

    for (const ev of ['resize', 'maximize', 'unmaximize', 'restore', 'enter-full-screen', 'leave-full-screen']) {
      win.on(ev, () => this.layout());
    }
    this.layout();
  }

  makeUi(file, background) {
    const view = new WebContentsView({ webPreferences: uiPrefs() });
    view.setBackgroundColor(background);
    view.webContents.loadURL(pathToFileURL(path.join(uiDir(), file)).href).catch(() => {});
    return view;
  }

  uiContents() {
    return [this.panel.webContents, this.island.webContents, this.win.webContents];
  }

  size() {
    const { width, height } = this.win.contentView.getBounds();
    return { width, height };
  }

  isFullscreen() {
    return this.win.isFullScreen() || this.htmlFullscreen;
  }

  setHtmlFullscreen(on) {
    this.htmlFullscreen = !!on;
    this.layout();
  }

  currentLayout() {
    const { width, height } = this.size();
    return computeLayout({
      width,
      height,
      fullscreen: this.isFullscreen(),
      islandMode: this.islandMode,
      islandHeight: this.islandHeight,
      dragTop: this.dragTop
    });
  }

  layout() {
    const l = this.currentLayout();
    for (const view of this.siteViews.values()) view.setBounds(l.site);
    this.panel.setBounds(l.panel);
    this.bar.view.setBounds(l.bar);
    this.pill.view.setBounds(l.pill);
    this.applyIsland(l);
  }

  showPanel() {
    if (this.panelShown) return;
    this.panelShown = true;
    this.win.contentView.addChildView(this.panel);
    this.win.contentView.addChildView(this.island);
    this.win.contentView.addChildView(this.pill.view);
  }

  hidePanel() {
    if (!this.panelShown) return;
    this.panelShown = false;
    this.win.contentView.removeChildView(this.panel);
  }

  setIslandMode(mode, height) {
    this.islandMode = mode;
    if (Number.isFinite(height)) this.islandHeight = Math.max(120, Math.min(900, Math.round(height)));
    this.applyIsland();
  }

  setIslandDragTop(top) {
    this.dragTop = Number.isFinite(top) ? top : null;
    this.applyIsland();
  }

  applyIsland(layout = this.currentLayout()) {
    if (!layout.island) {
      this.island.setVisible(false);
      return;
    }
    this.island.setVisible(true);
    this.island.setBounds(layout.island);
  }

  activeView() {
    return this.siteViews.get(this.activeId) || null;
  }

  switchTo(id) {
    if (this.activeId === id) return;
    const nextView = this.siteViews.get(id);
    if (!nextView) return;

    const prevView = this.activeView();
    if (prevView) {
      prevView.setVisible(false);
    }

    this.activeId = id;
    nextView.setVisible(true);
    this.win.contentView.addChildView(nextView, 0); // Keep at bottom
    this.layout();
  }

  destroy() {
    // cleanup
  }
}

module.exports = { Views, ISLAND_COLLAPSED, ISLAND_EXPANDED_W };
