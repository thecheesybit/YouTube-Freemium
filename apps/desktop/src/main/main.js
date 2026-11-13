'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { app, session } = require('electron');
const { genDir } = require('./paths');
const { initLog, log } = require('./log');
const { loadAppConfig } = require('./config');
const { NavRules } = require('../core/nav-rules');
const live = require('../core/live-config');
const { Prefs } = require('../core/prefs');
const { chromeUserAgent, installUserAgentHeaders } = require('./user-agent');
const { loadEngine } = require('./engine');
const { BlockStats } = require('./stats');
const { installNetworkBlocking } = require('./adblock');
const { installCosmeticIpc } = require('./cosmetic-ipc');
const { installPermissions } = require('./permissions');
const { installDownloads } = require('./downloads');
const { createShell } = require('./shell');
const { showSplashWindow } = require('./splash');
const { saveSessionCookies, restoreSessionCookies } = require('./cookie-store');
const { load: loadWindowState } = require('./window-state');
const { DiskAssetCache, classifyRequest, cacheableResponse } = require('./cache-policy');

// Flags to prevent renderer backgrounding and media throttling
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');
app.commandLine.appendSwitch('disable-backgrounding-occluded-windows');
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');

const gen = genDir();
const { app: appCfg, defaults, obf } = loadAppConfig(gen);

// Stable per-app user data folder
// YTF_USER_DATA lets tests and dev runs use an isolated profile (and a separate single-instance lock).
const userDir = process.env.YTF_USER_DATA || path.join(app.getPath('appData'), 'YouTube Freemium');
app.setPath('userData', userDir);

// Persistent session partition for shared Google sign-in
const PARTITION = 'persist:ytfreemium';

if (!app.requestSingleInstanceLock()) {
  app.quit();
} else {
  startApp();
}

function startApp() {
  let mainWin = null;
  let shellInstance = null;

  app.on('second-instance', (_e, argv) => {
    if (!mainWin) return;
    if (mainWin.isMinimized()) mainWin.restore();
    mainWin.show();
    mainWin.focus();

    // Check if deep link URL was passed in argv
    const url = argv.find((a) => /(?:youtube\.com|youtu\.be|music\.youtube\.com)/.test(a));
    if (url && shellInstance) {
      if (url.includes('music.youtube.com')) {
        shellInstance.actions.switchSource('youtube-music');
      } else {
        shellInstance.actions.switchSource('youtube');
      }
      const active = shellInstance.views.activeView();
      if (active) active.webContents.loadURL(url);
    }
  });

  app.on('window-all-closed', () => {
    log('[quit] all windows closed');
    if (process.platform !== 'darwin') app.quit();
  });

  app.whenReady().then(async () => {
    initLog(userDir);
    log(`[start] ${appCfg.appName} v${appCfg.version} (build ${appCfg.build})`);

    const prefs = new Prefs(userDir, log);
    const ses = session.fromPartition(PARTITION);

    const rules = new NavRules({
      startUrl: defaults.sources[0].url,
      allowedHosts: live.allAllowedHosts(defaults),
      authHosts: defaults.authHosts,
      externalLinks: appCfg.externalLinks
    });
    rules.update({
      allowedHosts: live.allAllowedHosts(defaults),
      startHosts: live.startHosts(defaults),
      authHosts: defaults.authHosts
    });

    const stats = new BlockStats();

    // Clean Chrome UA without Electron/ or app markers
    const rawUa = ses.getUserAgent();
    const cleanUa = appCfg.userAgent || chromeUserAgent(rawUa, [app.getName(), appCfg.appName, 'movy', 'electron']);
    ses.setUserAgent(cleanUa);
    app.userAgentFallback = cleanUa;
    installUserAgentHeaders(ses, cleanUa);

    // Setup Disk Asset Cache (mirrors CachePolicy.java)
    const cacheDir = path.join(userDir, 'asset-cache');
    const assetCache = new DiskAssetCache(cacheDir, {
      maxBytes: (appCfg.cache?.maxMb || 100) * 1024 * 1024,
      ttlMs: (appCfg.cache?.maxAgeDays || 14) * 24 * 60 * 60 * 1000
    });

    // Intercept cacheable static CSS/JS/fonts under /s/
    ses.webRequest.onResponseStarted((details) => {
      try {
        const kind = classifyRequest(details.method, details.url, details.responseHeaders);
        if (kind !== 'NONE' && details.statusCode === 200) {
          // If response has length, note it
          const len = parseInt(details.responseHeaders['content-length']?.[0] || '0', 10);
          if (cacheableResponse(kind, details.statusCode, details.responseHeaders, len || 1024)) {
            // cacheable
          }
        }
      } catch {}
    });

    // Load native Adblock engine
    let engine = null;
    if (appCfg.adblock?.enabled) {
      try {
        engine = loadEngine(gen);
      } catch (e) {
        log('[adblock] native engine failed to load:', e.message);
      }
    }
    log(`[adblock] engine ${engine ? 'loaded' : 'not loaded'}`);

    const isAdblockEnabled = () => prefs.get('adblockEnabled') !== false;
    const sitesAdapter = {
      isAllowed: (url) => {
        try {
          const u = new URL(url);
          return rules.isAllowedHost(u.hostname);
        } catch {
          return false;
        }
      },
      isAllowedHost: (h) => rules.isAllowedHost(h)
    };

    if (engine) {
      installNetworkBlocking(ses, engine, sitesAdapter, stats, log, isAdblockEnabled);
    }
    installPermissions(ses, rules);

    const read = (f) => {
      try { return fs.readFileSync(path.join(gen, f), 'utf8'); } catch { return ''; }
    };
    installCosmeticIpc({
      engine,
      cosmetic: appCfg.adblock?.cosmetic ?? true,
      isEnabled: isAdblockEnabled,
      assets: {
        cosmeticJs: read('cosmetic.js'),
        loadingJs: read('loading.js'),
        scamguardJs: read('scamguard.js'),
        navguardJs: read('navguard.js')
      },
      navConfig: () => ({ allow: rules.allowedPatterns(), auth: rules.authPatterns() }),
      isSite: () => true,
      stats
    });

    await restoreSessionCookies(ses, sitesAdapter, userDir, log);

    const isEngineBlocked = (url, source) =>
      !!engine && isAdblockEnabled() && engine.check(url, source || url, 'document').matched;

    const windowState = loadWindowState(userDir);
    const iconPath = path.join(gen, 'icon.png');

    shellInstance = createShell({
      appCfg,
      defaults,
      prefs,
      rules,
      stats,
      ses,
      gen,
      userDir,
      icon: iconPath,
      adblockConfigured: !!engine,
      isEngineBlocked,
      log,
      windowState,
      cache: assetCache
    });
    mainWin = shellInstance.win;

    installDownloads(ses, mainWin, log);

    // Eager cookie flush
    const flushCookies = () => ses.cookies.flushStore().catch(() => {});
    mainWin.on('blur', flushCookies);

    let quitting = false;
    app.on('before-quit', (e) => {
      if (quitting) return;
      e.preventDefault();
      quitting = true;
      app.isQuitting = true;
      Promise.all([
        saveSessionCookies(ses, sitesAdapter, userDir, log),
        ses.cookies.flushStore()
      ])
        .catch(() => {})
        .finally(() => app.quit());
    });

    // Start views loading behind the scenes
    shellInstance.start();

    // Check splash arguments or settings
    const noSplashArg = process.argv.includes('--no-splash');
    const showIntroSetting = shellInstance.settings.get('showIntro') !== false;

    // Maximize before the first show so the window never appears at the wrong size and then jumps.
    const reveal = () => {
      if (windowState.maximized) mainWin.maximize();
      mainWin.show();
    };
    if (noSplashArg || !showIntroSetting) {
      reveal();
    } else {
      showSplashWindow({ iconPath, onFinished: reveal });
    }
  });
}
