// Music Player skin for music.youtube.com only. Visual layer: restyles YouTube Music's own UI with the
// "Music Player" design system tokens and icons. It never touches playback, ads, or any other host.
(function () {
  if (location.hostname !== 'music.youtube.com' || window.__mpSkin) return;
  window.__mpSkin = true;

  // Design-system icons (24px grid, 1.75 stroke, round caps). "f:" paths are filled.
  var ICONS = {
    play: ['f:M8 5.6v12.8a1 1 0 0 0 1.5.86l10.3-6.4a1 1 0 0 0 0-1.72L9.5 4.74A1 1 0 0 0 8 5.6z'],
    pause: ['f:M7 5h3.2v14H7zM13.8 5H17v14h-3.2z'],
    next: ['f:M5 6.2v11.6a.8.8 0 0 0 1.24.66L14.5 13v5h2.5V6h-2.5v5L6.24 5.54A.8.8 0 0 0 5 6.2z'],
    prev: ['f:M19 6.2v11.6a.8.8 0 0 1-1.24.66L9.5 13v5H7V6h2.5v5l8.26-5.46A.8.8 0 0 1 19 6.2z'],
    shuffle: ['M4 7h3.5c4.5 0 4.5 10 9 10H20', 'M4 17h3.5c1.6 0 2.6-1.3 3.4-3', 'M13.1 10c.8-1.7 1.8-3 3.4-3H20', 'M17.5 4.5 20 7l-2.5 2.5', 'M17.5 14.5 20 17l-2.5 2.5'],
    repeat: ['M5 11V9.5A2.5 2.5 0 0 1 7.5 7H19', 'M16.5 4.5 19 7l-2.5 2.5', 'M19 13v1.5a2.5 2.5 0 0 1-2.5 2.5H5', 'M7.5 19.5 5 17l2.5-2.5'],
    search: ['M11 4.5a6.5 6.5 0 1 1 0 13 6.5 6.5 0 0 1 0-13z', 'M16 16l4 4'],
    more: ['f:M5.5 10.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM12 10.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3zM18.5 10.5a1.5 1.5 0 1 1 0 3 1.5 1.5 0 0 1 0-3z'],
    menu: ['M4.5 7h15', 'M4.5 12h15', 'M4.5 17h15'],
    down: ['M6 9.5l6 6 6-6']
  };
  function mask(name) {
    var body = ICONS[name].map(function (d) {
      return d.indexOf('f:') === 0 ? '<path d="' + d.slice(2) + '" fill="#000"/>'
        : '<path d="' + d + '" fill="none" stroke="#000" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/>';
    }).join('');
    return 'url("data:image/svg+xml,' + encodeURIComponent('<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24">' + body + '</svg>') + '")';
  }
  // Hide YouTube's glyph and paint ours in currentColor, so state colours still apply.
  function icon(sel, name) {
    return sel + ' yt-icon>*,' + sel + ' .yt-icon-shape>*{opacity:0!important}' +
      sel + ' yt-icon{background:currentColor!important;-webkit-mask:' + mask(name) + ' center/contain no-repeat;mask:' + mask(name) + ' center/contain no-repeat}';
  }

  var T = {
    canvas: '#0b0a0e', surface: '#161519', raised: '#222126', sunken: '#08070a', line: '#2e2d33', lineStrong: '#6f6d77',
    ink: '#f5f4f7', muted: '#a7a5ae', subtle: '#8f8d97', lime: '#d4f06c', onLime: '#141317',
    glass: 'rgba(255,255,255,0.08)', glassStrong: 'rgba(255,255,255,0.16)', glassStroke: 'rgba(255,255,255,0.14)', glassDark: 'rgba(16,15,19,0.66)',
    sans: '"Manrope", system-ui, -apple-system, "Segoe UI", sans-serif', display: '"Unbounded", "Arial Black", system-ui, sans-serif'
  };
  var ROOTS = 'html[data-mp-skin],html[data-mp-skin] ytmusic-app,html[data-mp-skin] ytmusic-app-layout,html[data-mp-skin] ytmusic-player-page,html[data-mp-skin] ytmusic-player-bar,html[data-mp-skin] ytmusic-popup-container';
  var BAR = 'html[data-mp-skin] ytmusic-player-bar';
  var MINI = 'html[data-mp-skin] ytmusic-app-layout:not([player-page-open]) ytmusic-player-bar';

  var css =
    '@import url("https://fonts.googleapis.com/css2?family=Manrope:wght@400;500;600;700;800&family=Unbounded:wght@600;700;800&display=swap");' +
    // Tokens -> YouTube Music theme variables
    ROOTS + '{' +
      '--ytmusic-general-background-a:' + T.canvas + '!important;--ytmusic-general-background-c:' + T.canvas + '!important;' +
      '--ytmusic-background:' + T.canvas + '!important;--ytmusic-brand-background-solid:' + T.canvas + '!important;' +
      '--ytmusic-player-page-background:' + T.canvas + '!important;--ytmusic-nav-bar:' + T.canvas + '!important;' +
      '--ytmusic-player-bar-background:' + T.surface + '!important;--ytmusic-player-bar-container-color:' + T.surface + '!important;' +
      '--ytmusic-text-primary:' + T.ink + '!important;--ytmusic-text-secondary:' + T.muted + '!important;--ytmusic-text-disabled:' + T.subtle + '!important;' +
      '--ytmusic-overlay-text-primary:' + T.ink + '!important;--ytmusic-overlay-text-secondary:' + T.muted + '!important;' +
      '--ytmusic-player-bar-ink-color:' + T.ink + '!important;--ytmusic-icon-inactive:' + T.muted + '!important;--ytmusic-inactive-tab:' + T.muted + '!important;' +
      '--ytmusic-call-to-action:' + T.lime + '!important;--ytmusic-brand-link-text:' + T.lime + '!important;--ytmusic-themed-blue:' + T.lime + '!important;' +
      '--ytmusic-playback-progress-color:' + T.lime + '!important;--ytmusic-play-pause-button-background:' + T.lime + '!important;' +
      '--ytmusic-play-button-background-color:' + T.lime + '!important;--ytmusic-play-button-icon-color:' + T.onLime + '!important;' +
      '--ytmusic-divider:' + T.line + '!important;--ytmusic-guide-divider:' + T.line + '!important;' +
      '--ytmusic-badge-chip-background:' + T.raised + '!important;--ytmusic-explore-chip-background:' + T.raised + '!important;' +
      '--ytmusic-toggle-button-chip-active-background:' + T.lime + '!important;--ytmusic-search-background:' + T.raised + '!important;' +
      '--ytmusic-menu-popup-background:' + T.raised + '!important;--ytmusic-dialog-background-color:' + T.raised + '!important;' +
      '--ytmusic-dropdown-background:' + T.raised + '!important;--ytmusic-inverted-background:' + T.ink + '!important;' +
      '--ytmusic-standard-text-font-family:' + T.sans + '!important;--ytmusic-display-text-font-family:' + T.display + '!important;' +
      '--yt-spec-base-background:' + T.canvas + '!important;--yt-spec-raised-background:' + T.surface + '!important;' +
      '--yt-spec-menu-background:' + T.raised + '!important;--yt-spec-text-primary:' + T.ink + '!important;--yt-spec-text-secondary:' + T.muted + '!important;' +
      '--yt-spec-call-to-action:' + T.lime + '!important;--yt-spec-10-percent-layer:' + T.line + '!important;' +
      '--paper-slider-active-color:' + T.lime + '!important;--paper-slider-knob-color:' + T.lime + '!important;--paper-slider-container-color:' + T.sunken + '!important;' +
      '--paper-progress-active-color:' + T.lime + '!important;--paper-progress-secondary-color:' + T.lineStrong + '!important;--paper-progress-container-color:' + T.sunken + '!important;' +
    '}' +
    'html[data-mp-skin],html[data-mp-skin] body{background:' + T.canvas + '!important}' +
    'html[data-mp-skin] body,html[data-mp-skin] ytmusic-app{font-family:' + T.sans + '!important}' +
    // Typography: Unbounded for screen / shelf / player titles, tight tracking on big type
    'html[data-mp-skin] ytmusic-carousel-shelf-basic-header-renderer .title,html[data-mp-skin] h2.title,html[data-mp-skin] ytmusic-responsive-header-renderer h1,' +
    'html[data-mp-skin] ytmusic-player-page .title.ytmusic-player-controls,html[data-mp-skin] ytmusic-player-page yt-formatted-string.title' +
      '{font-family:' + T.display + '!important;font-weight:700!important;letter-spacing:-0.02em!important}' +
    'html[data-mp-skin] ytmusic-carousel-shelf-basic-header-renderer .strapline{font-size:11px!important;font-weight:700!important;letter-spacing:.08em!important;color:' + T.subtle + '!important}' +
    // Shapes: pills for chips and buttons, design-system radii for artwork
    'html[data-mp-skin] ytmusic-chip-cloud-chip-renderer a,html[data-mp-skin] ytmusic-chip-cloud-chip-renderer .gradient-box,html[data-mp-skin] ytmusic-chip-cloud-chip-renderer button,' +
    'html[data-mp-skin] yt-button-shape button,html[data-mp-skin] .yt-spec-button-shape-next{border-radius:999px!important}' +
    'html[data-mp-skin] ytmusic-chip-cloud-chip-renderer[is-selected] a,html[data-mp-skin] ytmusic-chip-cloud-chip-renderer[chip-style=STYLE_PRIMARY] a' +
      '{background:' + T.lime + '!important;color:' + T.onLime + '!important}' +
    'html[data-mp-skin] ytmusic-two-row-item-renderer ytmusic-thumbnail-renderer,html[data-mp-skin] ytmusic-two-row-item-renderer .image-wrapper,html[data-mp-skin] ytmusic-two-row-item-renderer img' +
      '{border-radius:28px!important;overflow:hidden}' +
    'html[data-mp-skin] ytmusic-two-row-item-renderer[aspect-ratio*=CIRCLE] img,html[data-mp-skin] ytmusic-two-row-item-renderer[has-circle-cropped-thumbnail] img{border-radius:50%!important}' +
    'html[data-mp-skin] ytmusic-responsive-list-item-renderer ytmusic-thumbnail-renderer,html[data-mp-skin] ytmusic-responsive-list-item-renderer img,' +
    'html[data-mp-skin] ytmusic-player-queue-item img{border-radius:12px!important;overflow:hidden}' +
    'html[data-mp-skin] ytmusic-play-button-renderer{--ytmusic-play-button-background-color:' + T.lime + ';--ytmusic-play-button-icon-color:' + T.onLime + '}' +
    // Popups and sheets: raised surface, sheet radius
    'html[data-mp-skin] tp-yt-paper-dialog,html[data-mp-skin] ytmusic-menu-popup-renderer,html[data-mp-skin] tp-yt-paper-listbox' +
      '{background:' + T.raised + '!important;border-radius:28px!important}' +
    'html[data-mp-skin] ytmusic-search-box #input-box,html[data-mp-skin] ytmusic-search-box .search-box{border-radius:999px!important}' +
    // Expanded player: large artwork card, lime progress, lime play disc
    'html[data-mp-skin] ytmusic-player-page #song-image img,html[data-mp-skin] ytmusic-player-page #song-image,html[data-mp-skin] ytmusic-player-page ytmusic-player:not([video-mode]) #thumbnail' +
      '{border-radius:28px!important;overflow:hidden}' +
    'html[data-mp-skin] tp-yt-paper-slider#progress-bar,html[data-mp-skin] #progress-bar tp-yt-paper-progress,' + BAR + ' #progress-bar{--paper-slider-active-color:' + T.lime + '!important;--paper-slider-knob-color:' + T.lime + '!important;--paper-slider-secondary-color:' + T.lineStrong + '!important;--paper-slider-container-color:' + T.sunken + '!important}' +
    BAR + ' #play-pause-button{background:' + T.lime + '!important;color:' + T.onLime + '!important;border-radius:50%!important;box-shadow:0 0 20px rgba(212,240,108,0.55)}' +
    BAR + ' #play-pause-button button{color:' + T.onLime + '!important}' +
    BAR + '[repeat-mode=ALL] .repeat yt-icon,' + BAR + '[repeat-mode=ONE] .repeat yt-icon{background:' + T.lime + '!important}' +
    // Icons from the design system
    icon(BAR + ' .previous-button', 'prev') + icon(BAR + ' .next-button', 'next') +
    icon(BAR + ' .repeat', 'repeat') + icon(BAR + ' .shuffle', 'shuffle') + icon(BAR + ' .expand-shuffle', 'shuffle') +
    icon('html[data-mp-skin]:not([data-mp-playing]) ytmusic-player-bar #play-pause-button', 'play') +
    icon('html[data-mp-skin][data-mp-playing] ytmusic-player-bar #play-pause-button', 'pause') +
    icon('html[data-mp-skin] ytmusic-nav-bar ytmusic-search-box #icon-button, html[data-mp-skin] ytmusic-nav-bar .search-icon', 'search') +
    icon('html[data-mp-skin] ytmusic-nav-bar #guide-button', 'menu') +
    // Mini player: a floating glass pill above the content
    MINI + '{background:' + T.glassDark + '!important;-webkit-backdrop-filter:blur(24px) saturate(160%);backdrop-filter:blur(24px) saturate(160%);' +
      'border:1px solid ' + T.glassStroke + ';border-radius:28px!important;box-shadow:inset 0 1px 0 rgba(255,255,255,0.22),0 24px 48px rgba(0,0,0,0.55)!important;' +
      'margin:0 8px 8px!important;width:calc(100% - 16px)!important;overflow:hidden}' +
    MINI + ' .thumbnail-image-wrapper img,' + MINI + ' img.image{border-radius:12px!important}' +
    MINI + ' .title{font-weight:700!important}' +
    // Motion: press feedback only, nothing bounces
    'html[data-mp-skin] yt-icon-button,html[data-mp-skin] ytmusic-two-row-item-renderer,html[data-mp-skin] ytmusic-chip-cloud-chip-renderer{transition:transform 160ms ease}' +
    'html[data-mp-skin] yt-icon-button:active,html[data-mp-skin] ytmusic-two-row-item-renderer:active,html[data-mp-skin] ytmusic-chip-cloud-chip-renderer:active{transform:scale(.96)}' +
    'html[data-mp-skin] :focus-visible{outline:2px solid ' + T.lime + '!important;outline-offset:2px}';

  function install() {
    var root = document.documentElement;
    if (!root) return void setTimeout(install, 0);
    root.setAttribute('data-mp-skin', '');
    var s = document.createElement('style');
    s.id = 'mp-skin';
    s.textContent = css;
    (document.head || root).appendChild(s);
  }
  install();

  // Play/pause glyph follows the real media element, not a localized button label.
  function sync(e) {
    if (!e.target || e.target.tagName !== 'VIDEO') return;
    document.documentElement.toggleAttribute('data-mp-playing', !e.target.paused);
  }
  ['play', 'playing', 'pause', 'ended', 'emptied'].forEach(function (t) { document.addEventListener(t, sync, true); });
})();
