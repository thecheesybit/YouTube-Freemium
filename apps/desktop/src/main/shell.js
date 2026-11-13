'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { BrowserWindow, ipcMain, app } = require('electron');
const { Views } = require('./views');
const { MediaManager } = require('./media');
const { PipManager } = require('./pip');
const { TrayManager } = require('./tray');
const { SettingsStore } = require('./settings-store');
const { createUiBridge } = require('./ui-bridge');
const { createNavigationPolicy } = require('./navigation');
const { installShortcuts } = require('./shortcuts');
const { installMenu } = require('./menu');
const { guardLoads } = require('./load-guard');
const { uiPreloadPath } = require('./paths');
const { track: trackWindowState } = require('./window-state');
const { SourceRepository } = require('../core/repository');
const { CHROME_H } = require('../core/layout');
const { NavHistory, readHistory, step } = require('../core/nav-history');
const { LoadErrors, describeFailure } = require('../core/load-errors');

const SECOND_VIEW_DELAY_MS = 5000;

function createShell({ appCfg, defaults, prefs, rules, stats, ses, gen, userDir, icon, isEngineBlocked, log, windowState, cache }) {
  const settings = new SettingsStore(userDir);

  const win = new BrowserWindow({
    x: windowState.x,
    y: windowState.y,
    width: windowState.width || 1280,
    height: windowState.height || 800,
    minWidth: 480,
    minHeight: 320,
    title: 'YouTube Freemium',
    backgroundColor: '#0F0F0F',
    icon,
    titleBarStyle: 'hidden',
    // The native window buttons live in the chrome strip (same height), never over the page.
    titleBarOverlay: { color: '#0F0F0F', symbolColor: '#FFFFFF', height: CHROME_H },
    show: false,
    webPreferences: { preload: uiPreloadPath(), sandbox: true, contextIsolation: true, nodeIntegration: false }
  });
  // The window title follows the playing media, never the shell page's own <title>.
  win.webContents.on('page-title-updated', (e) => e.preventDefault());

  trackWindowState(win, userDir);

  const repo = new SourceRepository(prefs, defaults);
  const views = new Views({ win, ses, background: '#0F0F0F', prefs, sources: defaults.sources });

  const tray = new TrayManager({
    iconPath: icon,
    win,
    views,
    media: null,
    onSwitch: (id) => actions.switchSource(id),
    onQuit: () => actions.quit()
  });
  const media = new MediaManager({
    win,
    views,
    getSetting: (k) => settings.get(k),
    onTrayUpdate: () => tray.update()
  });
  tray.media = media;
  tray.update();

  const pip = new PipManager({ mainWin: win, views, media, getSetting: (k) => settings.get(k), log });

  const ui = { panel: null, error: null, storageBytes: 0, islandShown: true };
  const navHistory = new NavHistory(() => bridge.pushState());
  const loadErrors = new LoadErrors(() => syncError());

  const activeContents = () => views.activeView()?.webContents ?? null;
  const sourceName = (id) => repo.byId(id)?.name ?? '';
  const otherSource = () => repo.enabledSources().find((s) => s.id !== views.activeId) ?? null;

  /** Navigation snapshot of the active view for the chrome (history is per view). */
  const navSnapshot = () => {
    const h = readHistory(activeContents());
    return { ...h, sourceName: sourceName(views.activeId), fullscreen: views.isFullscreen() };
  };
  const syncNav = () => {
    navHistory.refresh(activeContents());
    bridge.pushState();
  };

  /** Shows the branded error screen for the active view when it has a failure, hides it when not (and no other panel). */
  function syncError() {
    const kind = loadErrors.get(views.activeId);
    if (kind) {
      const other = otherSource();
      ui.error = {
        kind,
        name: sourceName(views.activeId),
        ...describeFailure(kind, sourceName(views.activeId)),
        suggestionId: other?.id ?? null,
        suggestionName: other?.name ?? null
      };
    } else {
      ui.error = null;
    }
    syncPanel();
  }
  function syncPanel() {
    if (ui.panel || ui.error) views.showPanel();
    else views.hidePanel();
    bridge.pushState();
  }

  const actions = {
    switchSource: (id) => {
      views.switchTo(id);
      repo.switchTo(id);
      navHistory.refresh(activeContents());
      syncError();
      tray.update();
    },
    toggleSource: () => actions.switchSource(views.activeId === 'youtube' ? 'youtube-music' : 'youtube'),
    togglePip: () => pip.toggle(),
    openSettings: () => {
      ui.panel = 'settings';
      syncPanel();
    },
    openAbout: () => actions.openSettings(), // About lives inside Settings
    closePanel: () => {
      ui.panel = null;
      syncPanel();
    },
    setSetting: (k, v) => {
      settings.set(k, v);
      if (k === 'adblock') prefs.set('adblockEnabled', !!v);
      tray.update();
    },
    clearCache: () => {
      ses.clearCache().catch(() => {});
      if (cache && cache.clear) cache.clear();
    },
    clearData: () => {
      ses.clearStorageData().catch(() => {});
      if (cache && cache.clear) cache.clear();
      app.relaunch();
      app.quit();
    },
    updateLists: () => {},
    islandExpanded: () => {},
    goBack: async () => {
      const wc = activeContents();
      if (!wc) return;
      // On a watch page Back first shrinks the video into the in-app mini player (page script decides).
      try {
        if (await wc.executeJavaScript('window.__ytf_miniOnBack ? window.__ytf_miniOnBack() : false')) return syncNav();
      } catch {
        /* page not ready: fall through to plain history */
      }
      step(wc, 'back');
    },
    goForward: () => {
      const wc = activeContents();
      if (wc) step(wc, 'forward');
    },
    goHome: () => {
      const src = repo.active();
      const wc = activeContents();
      if (src && wc) wc.loadURL(src.url).catch(() => {});
    },
    reload: () => {
      const wc = activeContents();
      if (wc) wc.reload();
    },
    hardReload: () => {
      const wc = activeContents();
      if (wc) wc.reloadIgnoringCache();
    },
    /** Retry from the error screen: reload the failing page (the screen stays until a load succeeds). */
    retry: () => {
      const wc = activeContents();
      if (!wc) return;
      if (wc.getURL()) wc.reload();
      else wc.loadURL(repo.active()?.url ?? defaults.sources[0].url).catch(() => {});
    },
    quit: () => {
      app.isQuitting = true;
      app.quit();
    }
  };

  const bridge = createUiBridge({
    views,
    repo,
    prefs,
    health: { status: () => 'up' },
    stats,
    app: appCfg,
    gen,
    settings,
    ui,
    extra: () => ({ nav: navSnapshot(), error: ui.error, panel: ui.panel || (ui.error ? 'error' : null) }),
    actions,
    log
  });

  const navPolicy = createNavigationPolicy({
    rules,
    blockPopups: true,
    isEngineBlocked,
    stats,
    loadMain: (url) => activeContents()?.loadURL(url).catch(() => {}),
    log
  });

  for (const [sourceId, view] of views.siteViews.entries()) {
    const wc = view.webContents;
    navPolicy.attach(wc);
    installShortcuts(wc, win, actions, true);
    guardLoads({ id: sourceId, wc, errors: loadErrors, log });

    wc.on('did-start-loading', () => {
      if (views.activeId === sourceId) views.bar.start();
    });
    wc.on('did-stop-loading', () => {
      if (views.activeId === sourceId) views.bar.done();
    });
    for (const ev of ['did-navigate', 'did-navigate-in-page', 'did-finish-load']) {
      wc.on(ev, () => {
        if (views.activeId === sourceId) syncNav();
      });
    }
    wc.on('page-title-updated', () => {
      if (views.activeId === sourceId && !media.getActiveState().title) win.setTitle(`${wc.getTitle()} - YouTube Freemium`);
    });

    // Page fullscreen (video): the window goes fullscreen and the page gets the whole window, chrome strip hidden.
    let weSetFullscreen = false;
    wc.on('enter-html-full-screen', () => {
      if (!win.isFullScreen()) {
        weSetFullscreen = true;
        win.setFullScreen(true);
      }
      views.setHtmlFullscreen(true);
      bridge.pushState();
    });
    wc.on('leave-html-full-screen', () => {
      views.setHtmlFullscreen(false);
      if (weSetFullscreen) win.setFullScreen(false);
      weSetFullscreen = false;
      bridge.pushState();
    });
  }
  win.on('enter-full-screen', () => bridge.pushState());
  win.on('leave-full-screen', () => bridge.pushState());

  installShortcuts(win.webContents, win, actions, false);
  installMenu(win, actions);

  // Mouse side buttons and browser keys arrive as Windows app commands.
  win.on('app-command', (_e, command) => {
    if (command === 'browser-backward') actions.goBack();
    else if (command === 'browser-forward') actions.goForward();
  });

  // Read inject files once
  const read = (f) => {
    try {
      return fs.readFileSync(path.join(gen, f), 'utf8');
    } catch {
      return '';
    }
  };
  const injectAssets = {
    loadingJs: read('loading.js'),
    scamguardJs: read('scamguard.js'),
    navguardJs: read('navguard.js'),
    cosmeticJs: read('cosmetic.js'),
    youtubeJs: read('youtube.js'),
    selectorsJson: read('youtube-selectors.json')
  };

  const sourceOf = (sender) => {
    for (const [id, v] of views.siteViews.entries()) if (v.webContents.id === sender.id) return id;
    return null;
  };

  // IPC handlers for site preload and page scripts
  ipcMain.on('ytf:boot', (event) => {
    event.returnValue = {
      ...injectAssets,
      cosmetic: settings.get('cosmetic') !== false,
      nav: { allow: rules.allowedPatterns(), auth: rules.authPatterns() }
    };
  });
  ipcMain.on('ytf:log', (_event, msg) => log('[page]', msg));
  ipcMain.on('ytf:ad-skipping', (_event, active) => {
    views.pill.sm.heartbeat();
    if (active) views.pill.sm.show();
    else views.pill.sm.hide();
  });
  ipcMain.on('ytf:media-state', (event, mediaState) => {
    media.updateMediaState(sourceOf(event.sender) ?? views.activeId, mediaState);
  });
  ipcMain.on('ytf:enter-pip', () => {
    pip.enter({ explicit: true });
  });
  ipcMain.on('ytf:pip-state', (event, on) => {
    const id = sourceOf(event.sender);
    if (!id) return;
    if (on) {
      pip.isInPip = true;
      pip.activeSourceId = id;
      views.pill.sm.setPip(true);
    } else {
      pip.onPageLeft(id);
    }
  });

  // Close hides to the tray only while something plays
  win.on('close', (e) => {
    if (app.isQuitting) return;
    if (media.isAnyPlaying() && settings.get('keepInTray') !== false) {
      e.preventDefault();
      win.hide();
    }
  });

  // Background state for the page's resume guards; minimizing a playing video opens its PiP window
  win.on('blur', () => {
    if (win.isMinimized() || !win.isVisible()) media.setBg(true);
  });
  win.on('minimize', () => {
    media.setBg(true);
    if (pip.canEnterPip()) pip.enter();
  });
  win.on('restore', () => media.setBg(false));
  win.on('focus', () => media.setBg(false));

  return {
    win,
    views,
    media,
    pip,
    tray,
    repo,
    settings,
    actions,
    bridge,
    start: () => {
      // The visible source loads first; the other one preloads once it settles (or after a short delay),
      // so the two page loads never compete at startup.
      const initial = repo.preselected() || defaults.sources[0];
      actions.switchSource(initial.id);
      const load = (id) => {
        const src = repo.byId(id);
        if (src) views.siteViews.get(id)?.webContents.loadURL(src.url).catch(() => {});
      };
      load(initial.id);
      const rest = [...views.siteViews.keys()].filter((id) => id !== initial.id);
      let started = false;
      const startRest = () => {
        if (started) return;
        started = true;
        rest.forEach(load);
      };
      views.siteViews.get(initial.id)?.webContents.once('did-stop-loading', () => setTimeout(startRest, 800));
      setTimeout(startRest, SECOND_VIEW_DELAY_MS);
    }
  };
}

module.exports = { createShell };
