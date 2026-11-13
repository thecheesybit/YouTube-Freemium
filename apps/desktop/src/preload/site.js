'use strict';
// Sandboxed preload for every frame of the remote site (top frame and all subframes).
// Exposes window.YouTubeFreemiumBridge in the main world and injects youtube.js, scamguard.js, navguard.js, loading.js.
const { ipcRenderer, webFrame, contextBridge } = require('electron');

(function () {
  const proto = location.protocol;
  if (proto !== 'http:' && proto !== 'https:') return;

  // Build the bridge into the main world
  try {
    contextBridge.exposeInMainWorld('YouTubeFreemiumBridge', {
      log: (msg) => {
        try { ipcRenderer.send('ytf:log', String(msg)); } catch {}
      },
      adSkipping: (active) => {
        try { ipcRenderer.send('ytf:ad-skipping', !!active); } catch {}
      },
      reportMediaState: (isPlaying, isVideo, title, artist, artworkUrl, duration, position, w, h, left, top, right, bottom) => {
        try {
          ipcRenderer.send('ytf:media-state', {
            playing: !!isPlaying,
            isVideo: !!isVideo,
            title: String(title || ''),
            artist: String(artist || ''),
            artwork: String(artworkUrl || ''),
            duration: Number(duration) || 0,
            position: Number(position) || 0,
            width: Number(w) || 0,
            height: Number(h) || 0,
            rect: { left, top, right, bottom }
          });
        } catch {}
      },
      enterPip: () => {
        try { ipcRenderer.send('ytf:enter-pip'); } catch {}
      }
    });
  } catch {
    /* If contextBridge fails (e.g. contextIsolation false in tests), attach to window */
    window.YouTubeFreemiumBridge = {
      log: (m) => ipcRenderer.send('ytf:log', String(m)),
      adSkipping: (a) => ipcRenderer.send('ytf:ad-skipping', !!a),
      reportMediaState: (p, v, t, ar, art, d, pos, w, h, l, top, r, b) =>
        ipcRenderer.send('ytf:media-state', { playing: p, isVideo: v, title: t, artist: ar, artwork: art, duration: d, position: pos, width: w, height: h, rect: { left: l, top, right: r, bottom: b } }),
      enterPip: () => ipcRenderer.send('ytf:enter-pip')
    };
  }

  const boot = ipcRenderer.sendSync('ytf:boot');
  if (!boot) return;

  // Desktop: no scrollbars are ever drawn, but pages still scroll
  const NO_SCROLLBARS = '::-webkit-scrollbar{display:none!important;width:0!important;height:0!important}html,body,*{scrollbar-width:none!important}';
  try {
    webFrame.insertCSS(NO_SCROLLBARS);
  } catch {}

  const run = (code, vars = {}) => {
    try {
      new Function(...Object.keys(vars), code)(...Object.values(vars));
    } catch {}
  };

  /** Runs `code` in the page's main world */
  const runInPage = (code) => {
    try {
      const s = document.createElement('script');
      s.textContent = `(function(){try{${code}\n}catch(e){}\ndocument.currentScript.setAttribute('data-ytf','1');})();`;
      (document.head || document.documentElement).appendChild(s);
      const ok = s.getAttribute('data-ytf') === '1';
      s.remove();
      if (ok) return;
    } catch {}
    webFrame.executeJavaScript(`(function(){try{${code}\n}catch(e){}})();`).catch(() => {});
  };

  let isTop = false;
  try {
    isTop = window.self === window.top;
  } catch {}

  // The browser's PiP window opened/closed for this page's video (including its X and "back to tab" buttons).
  if (isTop) {
    for (const [type, on] of [['enterpictureinpicture', true], ['leavepictureinpicture', false]]) {
      window.addEventListener(type, () => {
        try { ipcRenderer.send('ytf:pip-state', on); } catch {}
      }, true);
    }
  }

  if (boot.loadingJs) run(boot.loadingJs);
  if (isTop && boot.scamguardJs) run(boot.scamguardJs);
  if (boot.navguardJs) runInPage(`window.YTF_NAV=${JSON.stringify(boot.nav || {})};${boot.navguardJs}`);

  // Authoritative YouTube script
  if (boot.youtubeJs) {
    runInPage(boot.youtubeJs);
  }

  // Cosmetic CSS injection
  if (boot.cosmetic && boot.cosmeticJs) {
    // Same contract as the Android shell: cosmetic hide rules from the engine plus scriptlets run in the page's main world.
    run(boot.cosmeticJs, {
      MOVY_OPTS: { cosmetic: true },
      MOVY_BRIDGE: {
        cosmetic: (url) => ipcRenderer.sendSync('movy:cosmetic', url),
        hidden: (classes, ids, exceptions) => {
          try {
            return ipcRenderer.sendSync('movy:hidden', JSON.parse(classes), JSON.parse(ids), JSON.parse(exceptions));
          } catch {
            return '[]';
          }
        }
      },
      MOVY_EVAL: runInPage
    });
  }
})();
