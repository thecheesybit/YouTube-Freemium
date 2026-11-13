// YouTube Freemium injected script: ad skip, Page Visibility override, PiP & MediaSession bridge
(function () {
  'use strict';

  const DEBUG = false;
  const IS_TOP = window.top === window;

  // A. No "Are you sure you want to leave this page?" prompt when switching apps/sources
  try {
    window.addEventListener('beforeunload', (e) => { e.stopImmediatePropagation(); }, true);
    Object.defineProperty(window, 'onbeforeunload', { get: () => null, set: () => {}, configurable: true });
  } catch (e) {}

  function ytfLog(m) { try { window.YouTubeFreemiumBridge.log(m); } catch (e) {} }


  // B. YouTube decides "we are in picture-in-picture" from outerWidth*outerHeight / screen area
  //    (< ~33%) and then pauses unless the video is Premium-backgroundable. Report the window as
  //    full-screen sized so it never pauses itself.
  try {
    Object.defineProperty(window, 'outerWidth', { get: () => screen.width, configurable: true });
    Object.defineProperty(window, 'outerHeight', { get: () => screen.height, configurable: true });
  } catch (e) {}


  // C. Remove ad data from YouTube's player responses so ads never load (no waiting to skip them).
  const AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  // Renderers YouTube uses for ads in feeds, search, the watch sidebar and the home masthead.
  const AD_ITEM_KEYS = ['adSlotRenderer', 'inFeedAdLayoutRenderer', 'promotedSparklesWebRenderer', 'promotedSparklesTextSearchRenderer',
    'promotedVideoRenderer', 'compactPromotedVideoRenderer', 'displayAdRenderer', 'bannerPromoRenderer', 'statementBannerRenderer',
    'brandVideoShelfRenderer', 'brandVideoSingletonRenderer', 'videoMastheadAdV3Renderer', 'searchPyvRenderer',
    'playerLegacyDesktopWatchAdsRenderer', 'actionCompanionAdRenderer', 'adBreakServiceRenderer', 'adSlotAndLayoutRenderer'];
  const AD_URL_RE = /\/youtubei\/v1\/(player|next|browse|search|guide|get_watch|reel\/reel_item_watch|reel\/reel_watch_sequence)/;

  function hasAdItem(e, depth) {
    if (!e || typeof e !== 'object') return false;
    for (const k of AD_ITEM_KEYS) if (k in e) return true;
    if (depth <= 0 || Array.isArray(e)) return false;
    for (const k in e) {
      const v = e[k];
      if (v && typeof v === 'object' && !Array.isArray(v) && hasAdItem(v, depth - 1)) return true;
    }
    return false;
  }

  // Walks a parsed response: drops ad keys, drops list items that are (or wrap) an ad renderer.
  function pruneAds(o, depth) {
    let changed = false;
    try {
      depth = depth === undefined ? 60 : depth;
      if (!o || typeof o !== 'object' || depth <= 0) return false;
      if (Array.isArray(o)) {
        for (let i = o.length - 1; i >= 0; i--) {
          if (hasAdItem(o[i], 3)) { o.splice(i, 1); changed = true; }
        }
        for (const it of o) if (pruneAds(it, depth - 1)) changed = true;
        return changed;
      }
      for (const k of AD_KEYS) {
        if (k in o) { delete o[k]; changed = true; }
      }
      for (const k of AD_ITEM_KEYS) {
        if (k in o) { delete o[k]; changed = true; }
      }
      for (const k in o) {
        const v = o[k];
        if (v && typeof v === 'object' && pruneAds(v, depth - 1)) changed = true;
      }
    } catch (e) {}
    return changed;
  }

  try {
    const origFetch = window.fetch;
    window.fetch = function (input) {
      const p = origFetch.apply(this, arguments);
      let url = '';
      try { url = typeof input === 'string' ? input : (input && input.url) || ''; } catch (e) {}
      if (!AD_URL_RE.test(url)) return p;
      return p.then(async (res) => {
        try {
          const o = JSON.parse(await res.clone().text());
          if (DEBUG) ytfLog('fetch ' + url.split('?')[0].slice(-30) + ' keys=' + AD_KEYS.filter((k) => k in o || (o.playerResponse && k in o.playerResponse)).join(','));
          if (pruneAds(o)) {
            if (DEBUG) ytfLog('pruned ads from ' + url.split('?')[0]);
            return new Response(JSON.stringify(o), { status: res.status, statusText: res.statusText, headers: res.headers });
          }
        } catch (e) {}
        return res;
      });
    };
    let initialResponse;
    Object.defineProperty(window, 'ytInitialPlayerResponse', {
      get: () => initialResponse,
      set: (v) => { pruneAds(v); initialResponse = v; },
      configurable: true
    });
    // The first page of a feed/search/watch page ships its data inline, not through fetch.
    let initialData;
    Object.defineProperty(window, 'ytInitialData', {
      get: () => initialData,
      set: (v) => { pruneAds(v); initialData = v; },
      configurable: true
    });
  } catch (e) {}


  try {
    const origOpen = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) {
      try { this.__ytf_url = String(u); } catch (e) {}
      return origOpen.apply(this, arguments);
    };
    const wanted = (x) => x.readyState === 4 && x.__ytf_url && AD_URL_RE.test(x.__ytf_url);
    for (const prop of ['responseText', 'response']) {
      const desc = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, prop);
      if (!desc || !desc.get) continue;
      Object.defineProperty(XMLHttpRequest.prototype, prop, {
        configurable: true,
        get: function () {
          const val = desc.get.call(this);
          if (!wanted(this)) return val;
          try {
            if (this.__ytf_done === undefined) {
              const raw = prop === 'responseText' || typeof val === 'string' ? val : JSON.stringify(val);
              const o = JSON.parse(raw);
              const changed = pruneAds(o);
              if (DEBUG) ytfLog('xhr ' + this.__ytf_url.split('?')[0].slice(-26) + ' pruned=' + changed);
              this.__ytf_done = changed ? o : null;
            }
            if (this.__ytf_done) {
              return typeof val === 'string' ? JSON.stringify(this.__ytf_done) : this.__ytf_done;
            }
          } catch (e) {}
          return val;
        }
      });
    }
  } catch (e) {}

  if (DEBUG) {
    try {
      const origPause = HTMLMediaElement.prototype.pause;
      HTMLMediaElement.prototype.pause = function () {
        ytfLog('pause() by ' + String(new Error().stack).split('\n').slice(2, 5).join(' | '));
        return origPause.apply(this, arguments);
      };
      document.addEventListener('pause', (e) => ytfLog('pause event bg=' + window.__ytf_bg + ' want=' + wantPlaying + ' touchAgo=' + (Date.now() - lastTouch) + ' ct=' + (e.target.currentTime|0)), true);
      document.addEventListener('play', () => ytfLog('play event'), true);
      for (const ev of ['loadstart', 'loadedmetadata', 'canplay', 'playing', 'waiting']) {
        document.addEventListener(ev, (e) => { if (e.target.tagName === 'VIDEO') ytfLog('T ' + ev + ' ' + Math.round(performance.now()) + 'ms ct=' + (e.target.currentTime | 0)); }, true);
      }
      for (const ev of ['visibilitychange', 'webkitvisibilitychange', 'pagehide', 'pageshow', 'freeze', 'resume', 'blur', 'focus', 'resize', 'fullscreenchange', 'webkitfullscreenchange', 'orientationchange', 'enterpictureinpicture', 'leavepictureinpicture']) {
        window.addEventListener(ev, (e) => ytfLog('event ' + ev + ' on ' + (e.target === window ? 'window' : e.target === document ? 'document' : e.target.tagName)), true);
        document.addEventListener(ev, (e) => ytfLog('event ' + ev + ' (doc) on ' + (e.target === window ? 'window' : e.target === document ? 'document' : e.target.tagName)), true);
      }
    } catch (e) {}
  }

  // 1. Page Visibility API override: keep playback alive with screen off or app backgrounded
  try {
    Object.defineProperty(document, 'hidden', { get: () => false, configurable: true });
    Object.defineProperty(document, 'visibilityState', { get: () => 'visible', configurable: true });
    Object.defineProperty(document, 'webkitVisibilityState', { get: () => 'visible', configurable: true });
    Object.defineProperty(document, 'webkitHidden', { get: () => false, configurable: true });
    Object.defineProperty(document, 'hasFocus', { value: () => true, configurable: true });
    for (const k of ['hidden', 'webkitHidden']) {
      Object.defineProperty(Document.prototype, k, { get: () => false, configurable: true });
    }
    for (const k of ['visibilityState', 'webkitVisibilityState']) {
      Object.defineProperty(Document.prototype, k, { get: () => 'visible', configurable: true });
    }
    Document.prototype.hasFocus = () => true;

    const stopEvent = (e) => {
      e.stopImmediatePropagation();
    };

    window.addEventListener('visibilitychange', stopEvent, true);
    window.addEventListener('webkitvisibilitychange', stopEvent, true);
    window.addEventListener('pagehide', stopEvent, true);
    window.addEventListener('blur', (e) => {
      if (e.target === window || e.target === document) e.stopImmediatePropagation();
    }, true);
  } catch (e) {
    // ignore
  }

  // 2. Selectors for ads, skip buttons, enforcement dialogs
  const SELECTORS = {
    skipButtons: [
      '.ytp-skip-ad-button',
      '.ytp-ad-skip-button-modern',
      '.ytp-ad-skip-button',
      '.ytp-ad-skip-button-slot',
      'button.ytp-ad-skip-button-modern',
      '.ytp-ad-skip-button-text',
      '.ytp-ad-skip-button-container button',
      '.ytp-skip-ad button',
      '.ytp-ad-skip-button-container .ytp-ad-skip-button-modern',
      'button[id^="skip-button"]',
      '.video-ads button[class*="skip"]'
    ],
    adPlayerIndicators: [
      '.ad-showing',
      '.ad-interrupting',
      '.video-ads .ytp-ad-player-overlay',
      '.ytp-ad-player-overlay-flyout-cta'
    ],
    adContainers: [
      '#masthead-ad',
      'ytd-ad-slot-renderer',
      'ytd-promoted-sparkles-web-renderer',
      'ytd-promoted-sparkles-text-search-renderer',
      'ytd-display-ad-renderer',
      '.ytp-ad-overlay-container',
      'ytm-promoted-sparkles-web-renderer',
      '#player-ads',
      'ytd-in-feed-ad-layout-renderer',
      'ytd-banner-promo-renderer',
      'ytd-statement-banner-renderer',
      'ytd-rich-item-renderer:has(ytd-ad-slot-renderer)',
      'ytd-rich-item-renderer:has(ytd-in-feed-ad-layout-renderer)',
      'ytd-rich-section-renderer:has(ytd-ad-slot-renderer)',
      'ytd-rich-section-renderer:has(ytd-statement-banner-renderer)',
      'ytd-item-section-renderer:has(> #contents > ytd-ad-slot-renderer)',
      'ytd-promoted-video-renderer',
      'ytd-compact-promoted-video-renderer',
      'ytd-search-pyv-renderer',
      'ytd-video-masthead-ad-v3-renderer',
      'ytd-brand-video-singleton-renderer',
      'ytd-brand-video-shelf-renderer',
      'ytd-primetime-promo-renderer',
      'ytd-action-companion-ad-renderer',
      'ytd-companion-slot-renderer',
      'ytd-player-legacy-desktop-watch-ads-renderer',
      'ytd-engagement-panel-section-list-renderer[target-id="engagement-panel-ads"]',
      '#sparkles-container',
      '.ytp-ad-overlay-slot',
      '.ytp-ad-image-overlay',
      '.ytp-ad-text-overlay',
      '.ytp-suggested-action-badge-expanded-content-container ~ .ytp-ad-message-container',
      'ytd-ad-inline-playback-meta-block',
      'ytmusic-statement-banner-renderer',
      'ytmusic-mealbar-promo-renderer',
      'ytmusic-upsell-dialog-renderer'
    ],
    enforcementDialogs: [
      'ytd-enforcement-message-view-model',
      'tp-yt-paper-dialog:has(#feedback)',
      'ytd-popup-container:has(ytd-enforcement-message-view-model)'
    ],
    musicConfirmButtons: [
      'ytmusic-you-there-renderer button',
      'yt-button-renderer.ytmusic-you-there-renderer',
      'tp-yt-paper-dialog .yt-button-renderer[dialog-confirm]'
    ]
  };

  // Inject CSS to hide static ad containers
  try {
    const style = document.createElement('style');
    style.id = 'ytf-ad-hide-css';
    style.textContent = '@keyframes ytfspin{to{transform:rotate(360deg)}} ' + SELECTORS.adContainers.join(', ') + ', ytm-open-app-button-renderer, .open-app-button, ytm-app-banner-renderer, ytm-mealbar-promo-renderer, ytm-upsell-dialog-renderer, a[href*="play.google.com/store/apps/details?id=com.google.android.youtube"] { display: none !important; }';
    (document.head || document.documentElement).appendChild(style);
  } catch (e) {}

  // 3. Ad skipper & Terms Enforcement bypass
  let adCheckInterval = null;
  const adState = { active: false, muted: false, rate: 1, since: 0 };
  let wantPlaying = false;
  let nativeGraceUntil = 0;
  let forcedWindowStart = 0;
  let forcedCount = 0;
  let bgTimer = null;

  // A real ad needs the player flagged .ad-showing / .ad-interrupting
  function isAdPlaying() {
    const p = document.querySelector('#movie_player, .html5-video-player');
    return !!(p && (p.classList.contains('ad-showing') || p.classList.contains('ad-interrupting')));
  }

  function findMainVideo() {
    return document.querySelector('video.html5-main-video') || document.querySelector('video');
  }


  // The "Skipping ads for you" notice is a native pill at the top centre of the screen.
  let adNotified = false;
  let adBeat = 0;
  function showAdCaption(on) {
    try {
      if (!IS_TOP) return;
      const now = Date.now();
      // while skipping, send a heartbeat every second so the native pill can time itself out
      if (on === adNotified && !(on && now - adBeat > 1000)) return;
      adNotified = on;
      adBeat = now;
      window.YouTubeFreemiumBridge.adSkipping(!!on);
    } catch (e) {}
  }

  // The pill must never outlive the ad: drop it as soon as the player is no longer in ad mode
  setInterval(() => {
    if (adNotified && !isAdPlaying()) {
      adState.active = false;
      showAdCaption(false);
    }
  }, 250);
  document.addEventListener('playing', () => { if (adNotified && !isAdPlaying()) showAdCaption(false); }, true);

  // If an ad refuses to be skipped for 4 s, reload the page once for that video (the fresh response has no ads).
  function reloadOnceForAd() {
    try {
      const id = videoIdOf(location.href) || location.pathname;
      const key = 'ytf_adreload_' + id;
      if (sessionStorage.getItem(key)) return;
      sessionStorage.setItem(key, '1');
      const v = findMainVideo();
      const saved = JSON.parse(localStorage.getItem('ytf_last') || 'null');
      if (saved && saved.id === videoIdOf(location.href)) saved.at = Date.now();
      location.reload();
    } catch (e) {}
  }

  // Some builds ignore a bare click(): send the full pointer sequence first.
  function pressSkip(btn) {
    try {
      for (const t of ['pointerdown', 'mousedown', 'pointerup', 'mouseup']) {
        btn.dispatchEvent(new MouseEvent(t, { bubbles: true, cancelable: true, view: window }));
      }
    } catch (e) {}
    btn.click();
  }

  function checkAndSkipAds() {
    try {
      // A. Click skip button if present
      for (const sel of SELECTORS.skipButtons) {
        const btn = document.querySelector(sel);
        if (btn && typeof btn.click === 'function') {
          pressSkip(btn);
          break;
        }
      }

      // B. Fast forward & mute ad video (restored as soon as the ad is gone)
      const isAdShowing = isAdPlaying();
      const video = findMainVideo();
      if (video) {
        if (isAdShowing) {
          if (!adState.active) {
            adState.since = Date.now();
            adState.active = true;
            adState.muted = video.muted;
            adState.rate = video.playbackRate || 1;
          }
          video.muted = true;
          video.playbackRate = 16.0;
          showAdCaption(true);
          if (Date.now() - adState.since > 4000) reloadOnceForAd();
          if (Number.isFinite(video.duration) && video.duration > 0 && video.duration < 1200) {
            video.currentTime = Math.max(video.duration - 0.1, 0);
          }
        } else if (adState.active) {
          adState.active = false;
          showAdCaption(false);
          video.muted = adState.muted;
          video.playbackRate = adState.rate;
        }
      }

      // C. Dismiss enforcement dialogs
      for (const sel of SELECTORS.enforcementDialogs) {
        const dialog = document.querySelector(sel);
        if (dialog) {
          dialog.remove();
          if (video && video.paused && !video.ended) {
            video.play().catch(() => {});
          }
        }
      }

      // D. Music "still listening" popup
      if (location.host.includes('music.youtube.com')) {
        for (const sel of SELECTORS.musicConfirmButtons) {
          const btn = document.querySelector(sel);
          if (btn && typeof btn.click === 'function') {
            btn.click();
            break;
          }
        }
      }
    } catch (e) {
      // keep quiet
    }
  }

  function startAdObserver() {
    checkAndSkipAds();

    const observer = new MutationObserver(() => {
      checkAndSkipAds();
      const isAdShowing = isAdPlaying();
      if (isAdShowing && !adCheckInterval) {
        adCheckInterval = setInterval(checkAndSkipAds, 100);
      } else if (!isAdShowing && adCheckInterval) {
        clearInterval(adCheckInterval);
        adCheckInterval = null;
      }
    });

    observer.observe(document.documentElement || document.body, {
      childList: true,
      subtree: true,
      attributes: true,
      attributeFilter: ['class', 'style']
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', startAdObserver);
  } else {
    startAdObserver();
  }
  // Safety net: poll even when no DOM mutation announces the ad (the skip button appears after a countdown).
  setInterval(checkAndSkipAds, 250);

  // 4. Media State & Metadata Bridge for PiP, MediaSession & PlaybackService
  let lastReportedState = {
    playing: false,
    isVideo: false,
    title: '',
    artist: '',
    artwork: '',
    duration: 0,
    position: 0,
    width: 0,
    height: 0,
    left: 0,
    top: 0,
    right: 0,
    bottom: 0
  };

  let interceptedMetadata = {
    title: '',
    artist: '',
    album: '',
    artwork: ''
  };

  // Intercept navigator.mediaSession.metadata
  try {
    if (window.navigator && window.navigator.mediaSession) {
      const origDescriptor = Object.getOwnPropertyDescriptor(
        Object.getPrototypeOf(window.navigator.mediaSession),
        'metadata'
      );
      Object.defineProperty(window.navigator.mediaSession, 'metadata', {
        set: function (val) {
          if (val) {
            interceptedMetadata.title = val.title || '';
            interceptedMetadata.artist = val.artist || '';
            interceptedMetadata.album = val.album || '';
            if (val.artwork && val.artwork.length > 0) {
              const best = val.artwork[val.artwork.length - 1];
              interceptedMetadata.artwork = (best && best.src) || '';
            }
          }
          if (origDescriptor && origDescriptor.set) {
            origDescriptor.set.call(this, val);
          }
          reportCurrentMediaState();
        },
        get: function () {
          return origDescriptor && origDescriptor.get ? origDescriptor.get.call(this) : null;
        },
        configurable: true
      });
    }
  } catch (e) {}

  function extractMetadata(video) {
    let title = interceptedMetadata.title;
    let artist = interceptedMetadata.artist;
    let artwork = interceptedMetadata.artwork;

    if (!title) {
      // DOM heuristics for YouTube & YouTube Music
      const ytTitle =
        document.querySelector('h1.ytd-watch-metadata') ||
        document.querySelector('h1.ytd-video-primary-info-renderer') ||
        document.querySelector('.ytmusic-player-bar .title') ||
        document.querySelector('yt-formatted-string.title');
      if (ytTitle && ytTitle.textContent) {
        title = ytTitle.textContent.trim();
      } else {
        title = document.title.replace(/\s*-\s*YouTube.*$/i, '').trim();
      }
    }

    if (!artist) {
      const ytArtist =
        document.querySelector('#owner #channel-name') ||
        document.querySelector('ytd-channel-name') ||
        document.querySelector('.ytmusic-player-bar .byline');
      if (ytArtist && ytArtist.textContent) {
        artist = ytArtist.textContent.trim();
      } else {
        artist = location.host.includes('music') ? 'YouTube Music' : 'YouTube';
      }
    }

    if (!artwork) {
      // Try og:image or video poster or album art
      const og = document.querySelector('meta[property="og:image"]');
      if (og && og.content) artwork = og.content;
      else if (video && video.poster) artwork = video.poster;
    }

    return { title: title || 'YouTube', artist: artist || 'YouTube', artwork: artwork || '' };
  }

  let lastSig = '';
  let lastSentAt = 0;

  function reportCurrentMediaState() {
    try {
      if (!IS_TOP) return;
      const video = findMainVideo();
      const bridge = window.YouTubeFreemiumBridge;
      if (!bridge) return;

      if (miniFront() && mini.box) {
        const r = mini.box.getBoundingClientRect();
        const playingMini = mini.state === 1 || mini.state === 3;
        const sigM = ['m', playingMini, mini.id, mini.title].join('|');
        const nowM = Date.now();
        if (sigM === lastSig && nowM - lastSentAt < 5000) return;
        lastSig = sigM;
        lastSentAt = nowM;
        if (DEBUG) ytfLog('report mini playing=' + playingMini + ' state=' + mini.state + ' t=' + (mini.t | 0));
        bridge.reportMediaState(playingMini, true, mini.title || 'YouTube', 'YouTube',
          'https://i.ytimg.com/vi/' + mini.id + '/hqdefault.jpg', mini.duration || 0, mini.t || 0, 16, 9,
          Math.max(0, Math.round(r.left)), Math.max(0, Math.round(r.top)), Math.max(0, Math.round(r.right)), Math.max(0, Math.round(r.bottom)));
        return;
      }

      const playing = video ? !video.paused && !video.ended : false;
      const isMusicHost = location.host.includes('music.youtube.com');
      const isVideo = video ? (video.videoWidth > 0 && !isMusicHost) : false;

      const meta = extractMetadata(video);
      const duration = (video && Number.isFinite(video.duration)) ? video.duration : 0;
      const position = (video && Number.isFinite(video.currentTime)) ? video.currentTime : 0;
      const width = video ? (video.videoWidth || 16) : 16;
      const height = video ? (video.videoHeight || 9) : 9;

      let rect = { left: 0, top: 0, right: 0, bottom: 0 };
      if (video) {
        const r = video.getBoundingClientRect();
        rect = {
          left: Math.max(0, Math.round(r.left)),
          top: Math.max(0, Math.round(r.top)),
          right: Math.max(0, Math.round(r.right)),
          bottom: Math.max(0, Math.round(r.bottom))
        };
      }

      const sig = [playing, isVideo, meta.title, meta.artist, width, height].join('|');
      const now = Date.now();
      if (sig === lastSig && now - lastSentAt < 5000) return;
      lastSig = sig;
      lastSentAt = now;

      bridge.reportMediaState(
        playing,
        isVideo,
        meta.title,
        meta.artist,
        meta.artwork,
        duration,
        position,
        width,
        height,
        rect.left,
        rect.top,
        rect.right,
        rect.bottom
      );
    } catch (e) {}
  }

  // Attach media listeners
  function attachMediaListeners(video) {
    if (!video || video.__ytf_hooked) return;
    video.__ytf_hooked = true;

    const events = ['play', 'pause', 'playing', 'ended', 'loadedmetadata'];
    for (const ev of events) {
      video.addEventListener(ev, reportCurrentMediaState, true);
    }
  }

  document.addEventListener('play', (e) => {
    if (e.target && e.target.tagName === 'VIDEO') {
      attachMediaListeners(e.target);
      reportCurrentMediaState();
    }
  }, true);

  setInterval(reportCurrentMediaState, 2000);

  // 5. PiP: show ONLY the video. Every sibling along the video's ancestor chain is hidden and the
  //    chain is stretched to the viewport, so nothing else of the page appears in the small window.
  let inPip = false;
  try { inPip = sessionStorage.getItem('ytf_inpip') === '1'; } catch (e) {}
  let pipSaved = [];

  // PiP isolation uses attributes + one stylesheet (not inline styles), so leaving PiP just removes
  // them and the page keeps whatever layout YouTube computed meanwhile (no stale restore, no half-black page).
  const PIP_CSS = 'html[data-ytf-pip-root]{overflow:hidden!important}' +
    '[data-ytf-pip]{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;' +
    'width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
    'padding:0!important;transform:none!important;translate:none!important;contain:none!important;filter:none!important;' +
    'overflow:hidden!important;background:#000!important;z-index:2147483000!important}' +
    'video[data-ytf-pip]{object-fit:contain!important}[data-ytf-hide]{display:none!important}';

  function pipMark(el, attr) {
    el.setAttribute(attr, '1');
    pipSaved.push({ el: el, attr: attr });
  }

  function pipEnter() {
    if (pipSaved.length) return;
    const v = findMainVideo();
    if (!v) return;
    // Fullscreen: the player already fills the window and the native fullscreen surface must stay
    // untouched; restyling or hiding anything here blanks the PiP window.
    if (document.fullscreenElement || document.webkitFullscreenElement) return;
    if (!document.getElementById('ytf-pip-css')) {
      const st = document.createElement('style');
      st.id = 'ytf-pip-css';
      st.textContent = PIP_CSS;
      (document.head || document.documentElement).appendChild(st);
    }
    let el = v;
    while (el && el !== document.documentElement) {
      pipMark(el, 'data-ytf-pip');
      const parent = el.parentElement;
      if (parent) {
        for (const sib of parent.children) {
          if (sib !== el && !/^(SCRIPT|STYLE|LINK)$/.test(sib.tagName) && sib.id !== 'ytf-pip-css') {
            pipMark(sib, 'data-ytf-hide');
          }
        }
      }
      el = parent;
    }
    pipMark(document.documentElement, 'data-ytf-pip-root');
  }

  function pipExit() {
    for (const it of pipSaved) it.el.removeAttribute(it.attr);
    pipSaved = [];
    const st = document.getElementById('ytf-pip-css');
    if (st) st.remove();
    window.dispatchEvent(new Event('resize'));
    setTimeout(() => window.dispatchEvent(new Event('resize')), 300);
  }

  // `native` (desktop): the host opens the browser's own PiP window for the video, so the page is never restyled.
  // Only the PiP flag, the resume guards and the mini-player conversion apply. Returns 'mini' when the mini player
  // was turned into the real watch page (the host retries once that page has a video), else 'ok'.
  let pipNative = false;
  window.__ytf_setPip = function (entering, native) {
    if (DEBUG) ytfLog('setPip ' + entering + ' native=' + !!native + ' fs=' + !!(document.fullscreenElement || document.webkitFullscreenElement) + ' path=' + location.pathname + ' video=' + !!findMainVideo() + ' mini=' + mini.active);
    inPip = !!entering;
    pipNative = !!native && !!entering;
    if (!native) { try { sessionStorage.setItem('ytf_inpip', entering ? '1' : '0'); } catch (e) {} }
    if (entering && restoreMiniToPage()) return native ? 'mini' : undefined;
    if (native) {
      nativeGraceUntil = Date.now() + 4000;
      setTimeout(window.__ytf_resumeIfWanted, 400);
      setTimeout(window.__ytf_resumeIfWanted, 1500);
      return 'ok';
    }
    nativeGraceUntil = Date.now() + 4000;
    setTimeout(window.__ytf_resumeIfWanted, 400);
    setTimeout(window.__ytf_resumeIfWanted, 1500);
    try {
      if (miniFront() || (!entering && mini.active && mini.box && mini.box.style.width === '100vw')) miniPip(!!entering);
      else if (entering) pipEnter(); else pipExit();
    } catch (e) {}
  };

  // 6. External Remote Actions (PiP buttons, Notification controls, Headset buttons)
  window.__ytf_play = function () {
    try {
      wantPlaying = true;
      const v = findMainVideo();
      if (miniFront()) miniCmd('playVideo'); else if (v) v.play();
    } catch (e) {}
  };

  window.__ytf_pause = function () {
    try {
      wantPlaying = false;
      const v = findMainVideo();
      if (miniFront()) miniCmd('pauseVideo'); else if (v) v.pause();
    } catch (e) {}
  };

  window.__ytf_seek = function (seconds) {
    try {
      const v = findMainVideo();
      if (v && Number.isFinite(v.currentTime)) {
        v.currentTime = Math.max(0, Math.min(v.currentTime + seconds, v.duration || 9999));
      }
    } catch (e) {}
  };

  window.__ytf_seekTo = function (seconds) {
    try {
      const v = findMainVideo();
      if (v && Number.isFinite(seconds)) {
        v.currentTime = Math.max(0, Math.min(seconds, v.duration || 9999));
      }
    } catch (e) {}
  };

  window.__ytf_next = function () {
    try {
      const nextBtn =
        document.querySelector('.ytp-next-button') ||
        document.querySelector('tp-yt-paper-icon-button.next-button') ||
        document.querySelector('.ytmusic-player-bar .next-button');
      if (nextBtn && typeof nextBtn.click === 'function') nextBtn.click();
    } catch (e) {}
  };

  window.__ytf_prev = function () {
    try {
      const prevBtn =
        document.querySelector('.ytp-prev-button') ||
        document.querySelector('tp-yt-paper-icon-button.previous-button') ||
        document.querySelector('.ytmusic-player-bar .previous-button');
      if (prevBtn && typeof prevBtn.click === 'function') prevBtn.click();
    } catch (e) {}
  };
  // 7. Keep playing while the app is in the background / screen is off.
  //    The native side sets __ytf_bg; any pause that the page (or the system) causes while
  //    in the background is undone unless the user paused via the notification / PiP buttons.
  window.__ytf_bg = false;

  window.__ytf_setBg = function (bg) {
    window.__ytf_bg = !!bg;
    try {
      if (IS_TOP && mini.active && mini.frame) mini.frame.contentWindow.postMessage(JSON.stringify({ ytf: 'bg', v: !!bg }), '*');
    } catch (e) {}
    nativeGraceUntil = Date.now() + 4000;
    if (bg && restoreMiniToPage()) return;
    if (bgTimer) { clearInterval(bgTimer); bgTimer = null; }
    if (bg) {
      window.__ytf_resumeIfWanted();
      let n = 0;
      bgTimer = setInterval(() => {
        window.__ytf_resumeIfWanted();
        if (++n > 8 || !window.__ytf_bg) { clearInterval(bgTimer); bgTimer = null; }
      }, 1000);
    }
  };

  if (!IS_TOP) {
    window.addEventListener('message', (e) => {
      let d = e.data;
      try { if (typeof d === 'string') d = JSON.parse(d); } catch (err) { return; }
      if (d && d.ytf === 'bg') window.__ytf_setBg(!!d.v);
    }, false);
  }

  window.__ytf_resumeIfWanted = function () {
    try {
      const v = findMainVideo();
      if (miniFront()) {
        if (wantPlaying && (mini.state === 2 || window.__ytf_bg)) miniCmd('playVideo');
      } else if (wantPlaying && v && v.paused && !v.ended) v.play().catch(() => {});
    } catch (e) {}
  };

  document.addEventListener('play', (e) => {
    if (e.target && e.target.tagName === 'VIDEO' && !adState.active) wantPlaying = true;
  }, true);

  let lastTouch = 0;
  for (const ev of ['touchstart', 'pointerdown', 'mousedown', 'keydown']) {
    window.addEventListener(ev, () => { lastTouch = Date.now(); }, true);
  }

  // A pause right after a finger touch is the user's; anything else while the app is in the
  // background (or right after a screen/PiP transition) is the system's and is undone.
  document.addEventListener('pause', (e) => {
    const v = e.target;
    if (!v || v.tagName !== 'VIDEO' || v.ended || adState.active) return;
    saveProgress(true);
    if (Date.now() - lastTouch < 1500 && !window.__ytf_bg) {
      wantPlaying = false;
      return;
    }
    if (wantPlaying && (window.__ytf_bg || Date.now() < nativeGraceUntil)) {
      const now = Date.now();
      if (now - forcedWindowStart > 5000) { forcedWindowStart = now; forcedCount = 0; }
      if (++forcedCount <= 2) {
        setTimeout(window.__ytf_resumeIfWanted, 200);
      }
    }
  }, true);

  // Remember playback position so switching YouTube <-> YouTube Music (or restarting) continues there.
  function videoIdOf(url) {
    try { return new URL(url).searchParams.get('v'); } catch (e) { return null; }
  }
  let lastSave = 0;
  function saveProgress(force) {
    try {
      const v = findMainVideo();
      const id = videoIdOf(location.href);
      if (!v || !id || adState.active || !Number.isFinite(v.duration) || v.duration < 30) return;
      const now = Date.now();
      if (!force && now - lastSave < 3000) return;
      lastSave = now;
      if (v.duration - v.currentTime < 10) {
        localStorage.removeItem('ytf_last');
      } else {
        localStorage.setItem('ytf_last', JSON.stringify({ id: id, t: Math.floor(v.currentTime), at: now }));
      }
    } catch (e) {}
  }
  document.addEventListener('timeupdate', () => { if (isAdPlaying()) checkAndSkipAds(); else saveProgress(false); }, true);
  for (const ev of ['loadstart', 'loadedmetadata', 'playing']) {
    document.addEventListener(ev, () => { if (isAdPlaying()) checkAndSkipAds(); }, true);
  }

  let restoredFor = '';
  function restoreProgress(v) {
    try {
      const id = videoIdOf(location.href);
      if (!id || restoredFor === id || adState.active || isAdPlaying()) return;
      restoredFor = id;
      if (/[?&]t=/.test(location.search) || v.currentTime > 3) return;
      const saved = JSON.parse(localStorage.getItem('ytf_last') || 'null');
      if (saved && saved.id === id && saved.t > 5 && Date.now() - saved.at < 12 * 3600 * 1000) {
        v.currentTime = saved.t;
      }
    } catch (e) {}
  }

  // 8. Playback speed up to 4x (YouTube mobile stops at 2x)
  const RATES = [1, 1.25, 1.5, 2, 3, 4];
  function savedRate() {
    try { return parseFloat(localStorage.getItem('ytf_rate')) || 1; } catch (e) { return 1; }
  }
  function saveRate(r) {
    try { localStorage.setItem('ytf_rate', String(r)); } catch (e) {}
  }
  function applyRate(v) {
    if (!v || adState.active) return;
    const r = savedRate();
    if (r !== 1 && v.playbackRate !== r) v.playbackRate = r;
  }

  document.addEventListener('loadedmetadata', (e) => {
    const v = e.target;
    if (!v || v.tagName !== 'VIDEO') return;
    restoreProgress(v);
    applyRate(v);
    setTimeout(() => applyRate(v), 800);
    setTimeout(() => applyRate(v), 2500);
  }, true);

  // The player's touch overlay sits above the chip, so taps are caught at document level
  // and matched against the chip's on-screen rectangle.
  let lastSpeedTap = 0;
  function controlHit(e, id) {
    const chip = document.getElementById(id);
    if (!chip || chip.style.display === 'none' || chip.dataset.on !== '1') return false;
    const pt = (e.changedTouches && e.changedTouches[0]) || e;
    const r = chip.getBoundingClientRect();
    const pad = 8;
    return pt.clientX >= r.left - pad && pt.clientX <= r.right + pad &&
           pt.clientY >= r.top - pad && pt.clientY <= r.bottom + pad;
  }
  function speedChipHit(e) { return controlHit(e, 'ytf-speed'); }
  function pipBtnHit(e) { return controlHit(e, 'ytf-pipbtn'); }

  // PiP button: leaves fullscreen if needed, then asks the app to enter the system PiP window
  function requestPip() {
    const fs = document.fullscreenElement || document.webkitFullscreenElement;
    try { if (fs) (document.exitFullscreen || document.webkitExitFullscreen).call(document); } catch (e) {}
    setTimeout(() => {
      try { window.YouTubeFreemiumBridge.enterPip(); } catch (e) {}
    }, fs ? 650 : 60);
  }

  function onSpeedTap(e) {
    const onPip = pipBtnHit(e);
    if (!onPip && !speedChipHit(e)) return;
    e.stopPropagation();
    e.preventDefault();
    if (e.type === 'click') return;
    const now = Date.now();
    if (now - lastSpeedTap < 350) return;
    lastSpeedTap = now;
    if (onPip) { requestPip(); chipUntil = Date.now() + 3500; return; }
    const chip = document.getElementById('ytf-speed');
    const vid = findMainVideo();
    const next = RATES[(RATES.indexOf(savedRate()) + 1) % RATES.length] || 1;
    saveRate(next);
    if (vid) vid.playbackRate = next;
    chip.textContent = next + 'x';
    chipUntil = Date.now() + 3500;
  }
  for (const ev of ['pointerup', 'touchend', 'click']) window.addEventListener(ev, onSpeedTap, true);
  for (const ev of ['pointerdown', 'touchstart', 'mousedown', 'mouseup']) {
    window.addEventListener(ev, (e) => { if (speedChipHit(e) || pipBtnHit(e)) e.stopPropagation(); }, true);
  }

  let chipUntil = Date.now() + 3500;
  const bumpChip = () => { chipUntil = Date.now() + 3500; };
  for (const ev of ['touchstart', 'pointerdown', 'click']) window.addEventListener(ev, bumpChip, true);
  document.addEventListener('play', bumpChip, true);

  function ensureSpeedChip() {
    if (!IS_TOP) return;
    if (!/^\/watch/.test(location.pathname)) {
      const stale = document.getElementById('ytf-speed');
      if (stale) { stale.style.display = 'none'; stale.dataset.on = '0'; }
      const staleBtn = document.getElementById('ytf-pipbtn');
      if (staleBtn) { staleBtn.style.display = 'none'; staleBtn.dataset.on = '0'; }
      return;
    }
    if (location.host.includes('music.youtube.com')) return;
    const v = findMainVideo();
    const player = v && (v.closest('.html5-video-player') || document.getElementById('movie_player'));
    let chip = document.getElementById('ytf-speed');
    if (!player || inPip || adState.active) {
      if (chip) { chip.style.display = 'none'; chip.dataset.on = '0'; }
      const pbOff = document.getElementById('ytf-pipbtn');
      if (pbOff) { pbOff.style.display = 'none'; pbOff.dataset.on = '0'; }
      return;
    }
    if (!chip) {
      chip = document.createElement('div');
      chip.id = 'ytf-speed';
      chip.style.cssText = 'position:absolute;top:10px;left:12px;z-index:2147483000;padding:6px 12px;' +
        'border-radius:16px;background:rgba(0,0,0,.6);color:#fff;font:600 14px Roboto,Arial,sans-serif;' +
        'cursor:pointer;user-select:none;-webkit-user-select:none;';
      const stop = (e) => { e.stopPropagation(); };
      chip.addEventListener('pointerdown', stop, true);
      chip.addEventListener('touchstart', stop, true);
    }
    if (chip.parentElement !== player) player.appendChild(chip);
    chip.style.display = '';
    const show = v.paused || Date.now() < chipUntil;
    chip.style.transition = 'opacity .25s';
    chip.style.opacity = show ? '1' : '0';
    chip.dataset.on = show ? '1' : '0';

    let pb = document.getElementById('ytf-pipbtn');
    if (!pb) {
      pb = document.createElement('div');
      pb.id = 'ytf-pipbtn';
      pb.textContent = 'PiP';
      pb.style.cssText = 'position:absolute;top:10px;z-index:2147483000;padding:6px 12px;border-radius:16px;' +
        'background:rgba(0,0,0,.6);color:#fff;font:600 14px Roboto,Arial,sans-serif;cursor:pointer;' +
        'user-select:none;-webkit-user-select:none;';
    }
    if (pb.parentElement !== player) player.appendChild(pb);
    pb.style.left = (12 + chip.offsetWidth + 8) + 'px';
    pb.style.display = '';
    pb.style.transition = 'opacity .25s';
    pb.style.opacity = show ? '1' : '0';
    pb.dataset.on = show ? '1' : '0';
    const label = savedRate() + 'x';
    if (chip.textContent !== label) chip.textContent = label;
  }

  // 9. Remove the "Open App" button / banners of the mobile site
  function hideOpenApp() {
    try {
      if (!IS_TOP) return;
      // returning from the mini player autoplays muted: switch the sound back on
      const unmute = document.querySelector('[class*="unmute"]');
      if (unmute && unmute.offsetParent !== null) {
        const mv = findMainVideo();
        if (mv && mv.muted && !adState.active) mv.muted = false;
        if (typeof unmute.click === 'function') unmute.click();
      }
      const nodes = document.querySelectorAll('a, button, ytm-button-renderer, yt-button-shape');
      for (const n of nodes) {
        const t = (n.textContent || '').trim().toLowerCase();
        if (t === 'open app' || t === 'open in app' || t === 'get the app') {
          const host = n.closest('ytm-button-renderer, yt-button-shape') || n;
          host.style.setProperty('display', 'none', 'important');
        }
      }
    } catch (e) {}
  }


  // 10. In-app mini player: Back during a video shrinks it to a floating player (like the YouTube app)
  //     while you keep browsing. It is the official embed continued from the same second.
  const mini = { active: false, id: '', t: 0, state: -1, duration: 0, title: '', since: 0, box: null, frame: null, saved: null };

  // true while the mini player (not a watch-page video) is the thing that is playing
  function miniFront() {
    return mini.active && (!/^\/watch/.test(location.pathname) || Date.now() - mini.since < 2500);
  }

  function miniCmd(func, args) {
    try {
      mini.frame.contentWindow.postMessage(JSON.stringify({ event: 'command', func: func, args: args || [] }), '*');
    } catch (e) {}
  }

  function closeMini() {
    try {
      if (mini.box) mini.box.remove();
    } catch (e) {}
    mini.active = false;
    mini.box = null;
    mini.frame = null;
    mini.state = -1;
    document.documentElement.style.removeProperty('--ytf-mini');
    reportCurrentMediaState();
  }

  function openMini(id, t, playing, title) {
    closeMini();
    const box = document.createElement('div');
    box.id = 'ytf-mini';
    box.style.cssText = 'position:fixed;right:12px;bottom:84px;width:min(46vw,360px);aspect-ratio:16/9;background:#000;' +
      'border-radius:12px;overflow:hidden;z-index:2147483600;box-shadow:0 6px 24px rgba(0,0,0,.6);';
    const frame = document.createElement('iframe');
    frame.src = 'https://www.youtube.com/embed/' + encodeURIComponent(id) + '?autoplay=' + (playing ? 1 : 0) +
      '&start=' + t + '&playsinline=1&enablejsapi=1&rel=0&modestbranding=1&origin=' + encodeURIComponent(location.origin);
    frame.allow = 'autoplay; encrypted-media; picture-in-picture; fullscreen';
    frame.style.cssText = 'position:absolute;left:0;top:0;width:100%;height:100%;border:0;background:#000;';
    const btn = (label, pos, fn) => {
      const b = document.createElement('div');
      const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
      svg.setAttribute('viewBox', '0 0 24 24');
      svg.setAttribute('width', '22');
      svg.setAttribute('height', '22');
      const path = document.createElementNS('http://www.w3.org/2000/svg', 'path');
      path.setAttribute('fill', '#fff');
      path.setAttribute('d', label);
      svg.appendChild(path);
      b.appendChild(svg);
      b.className = 'ytf-mini-btn';
      b.style.cssText = 'position:absolute;top:8px;' + pos + ';width:36px;height:36px;border-radius:18px;background:rgba(0,0,0,.7);' +
        'display:flex;align-items:center;justify-content:center;z-index:2;cursor:pointer;';
      let last = 0;
      for (const ev of ['click', 'touchend', 'pointerup']) {
        b.addEventListener(ev, (e) => {
          e.stopPropagation();
          e.preventDefault();
          const now = Date.now();
          if (now - last < 500) return;
          last = now;
          fn();
        }, true);
      }
      for (const ev of ['touchstart', 'pointerdown', 'mousedown']) {
        b.addEventListener(ev, (e) => { e.stopPropagation(); }, true);
      }
      return b;
    };
    box.appendChild(frame);
    box.appendChild(btn('M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z', 'left:8px', () => {
      const target = '/watch?v=' + encodeURIComponent(mini.id) + '&t=' + Math.floor(mini.t) + 's';
      closeMini();
      location.assign(target);
    }));
    box.appendChild(btn('M19 6.41 17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z', 'right:8px', () => { wantPlaying = false; closeMini(); }));
    document.documentElement.appendChild(box);
    Object.assign(mini, { active: true, id: id, t: t, state: playing ? 3 : 2, duration: 0, title: title || '', since: Date.now(), box: box, frame: frame });
    wantPlaying = !!playing;
    frame.addEventListener('load', () => {
      try { frame.contentWindow.postMessage(JSON.stringify({ event: 'listening', id: 1, channel: 'widget' }), '*'); } catch (e) {}
    });
    reportCurrentMediaState();
  }

  window.addEventListener('message', (e) => {
    if (!IS_TOP || !mini.active || !mini.frame || e.source !== mini.frame.contentWindow) return;
    let d = e.data;
    try { if (typeof d === 'string') d = JSON.parse(d); } catch (err) { return; }
    if (!d) return;
    if (DEBUG) ytfLog('mini msg ' + (d.event || '?') + ' ' + JSON.stringify(d.info === undefined ? '' : d.info).slice(0, 90));
    if (d.event === 'onReady') {
      try { mini.frame.contentWindow.postMessage(JSON.stringify({ event: 'listening', id: 1, channel: 'widget' }), '*'); } catch (err) {}
    } else if (d.event === 'onStateChange' && typeof d.info === 'number') {
      mini.state = d.info;
    } else if (d.info && typeof d.info === 'object') {
      const i = d.info;
      if (typeof i.currentTime === 'number') mini.t = i.currentTime;
      if (typeof i.playerState === 'number') mini.state = i.playerState;
      if (typeof i.duration === 'number' && i.duration > 0) mini.duration = i.duration;
      if (i.videoData && i.videoData.title) mini.title = i.videoData.title;
    } else {
      return;
    }
    // embeds sometimes sit at "unstarted/cued" with a big play button: start them if the user was watching
    if ((mini.state === -1 || mini.state === 5) && wantPlaying && Date.now() - (mini.kick || 0) > 1200) {
      mini.kick = Date.now();
      miniCmd('playVideo');
    }
    reportCurrentMediaState();
  }, false);

  // Backgrounded (home, screen off) or PiP: turn the mini player into the real watch page,
  // which keeps playing in the background and can use the native PiP window.
  function restoreMiniToPage() {
    if (!IS_TOP || !miniFront()) return false;
    const playing = mini.state === 1 || mini.state === 3;
    if (!playing) return false;
    const id = mini.id;
    const t = Math.floor(mini.t || 0);
    closeMini();
    wantPlaying = true;
    try { sessionStorage.setItem('ytf_unmute', String(Date.now())); } catch (e) {}
    location.assign('/watch?v=' + encodeURIComponent(id) + '&t=' + t + 's');
    return true;
  }

  window.__ytf_miniOnBack = function () {
    try {
      if (!IS_TOP || location.host.includes('music.youtube.com') || !/^\/watch/.test(location.pathname)) return false;
      const v = findMainVideo();
      const id = videoIdOf(location.href);
      if (DEBUG) ytfLog('miniOnBack v=' + !!v + ' id=' + id + ' ad=' + adState.active + ' fs=' + !!document.fullscreenElement + ' path=' + location.pathname + ' inPip=' + inPip + ' pipSaved=' + pipSaved.length);
      if (!v || !id || adState.active || document.fullscreenElement) return false;
      saveProgress(true);
      try {
        sessionStorage.setItem('ytf_mini_pending', JSON.stringify({ id: id, t: v.currentTime, playing: !v.paused, title: (document.title || '').replace(/\s*-\s*YouTube.*$/i, ''), at: Date.now() }));
        setTimeout(() => { try { sessionStorage.removeItem('ytf_mini_pending'); } catch (e) {} }, 4000);
      } catch (e) {}
      openMini(id, Math.floor(v.currentTime), !v.paused, (document.title || '').replace(/\s*-\s*YouTube.*$/i, ''));
      wantPlaying = !v.paused;
      try { v.pause(); } catch (e) {}
      if (history.length > 1) history.back(); else location.assign('/');
      return true;
    } catch (e) {
      return false;
    }
  };

  // History navigation after a page restore is a full page load: bring the mini player back on the new page.
  setTimeout(() => {
    if (!IS_TOP) return;
    try {
      const raw = sessionStorage.getItem('ytf_mini_pending');
      if (!raw) return;
      sessionStorage.removeItem('ytf_mini_pending');
      const d = JSON.parse(raw);
      if (!d || Date.now() - d.at > 15000 || /^\/watch/.test(location.pathname) || mini.active) return;
      const t = Math.floor(d.t + (d.playing ? (Date.now() - d.at) / 1000 : 0));
      openMini(d.id, t, !!d.playing, d.title);
    } catch (e) {}
  }, 500);

  // a new watch page replaces the mini player
  setInterval(() => {
    if (mini.active && Date.now() - mini.since > 2500 && /^\/watch/.test(location.pathname) && findMainVideo()) closeMini();
  }, 700);

  // PiP with a mini player: show only that player, full window
  function miniPip(entering) {
    if (!mini.box) return;
    if (entering) {
      mini.saved = mini.box.getAttribute('style');
      mini.box.style.cssText = 'position:fixed;left:0;top:0;right:0;bottom:0;width:100vw;height:100vh;background:#000;z-index:2147483600;';
      for (const b of mini.box.querySelectorAll('.ytf-mini-btn')) b.style.display = 'none';
      document.body.style.setProperty('display', 'none', 'important');
    } else {
      if (mini.saved) mini.box.setAttribute('style', mini.saved);
      for (const b of mini.box.querySelectorAll('.ytf-mini-btn')) b.style.display = 'flex';
      document.body.style.removeProperty('display');
    }
  }

  setInterval(() => {
    if (!IS_TOP) return;
    try {
      if (inPip && !pipNative && !pipSaved.length && !miniFront() && findMainVideo() && /^\/watch/.test(location.pathname)) pipEnter();
      const stamp = parseInt(sessionStorage.getItem('ytf_unmute') || '0', 10);
      if (stamp) {
        const v = findMainVideo();
        if (Date.now() - stamp > 12000) sessionStorage.removeItem('ytf_unmute');
        else if (v && v.muted && !adState.active) v.muted = false;
      }
    } catch (e) {}
  }, 400);
  setInterval(ensureSpeedChip, 300);
  setInterval(hideOpenApp, 900);
})();
