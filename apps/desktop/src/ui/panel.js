'use strict';
// Panel renderer: splash, first-launch chooser, Settings and the "isn't responding" panel. One layer, one mode at a
// time, driven by main's state snapshot (state.panel). Fades between modes; nothing here knows a URL.
(function () {
  var h = UI.h;
  var play = UI.play;
  var T = UI.T;
  document.documentElement.style.setProperty('--spring-soft', T.soft.css);

  var rootEl = document.getElementById('root');
  var state = null;
  var mode = null; // what is currently rendered
  var settings = null;
  var chosen = null;
  var always = true;
  var fading = null;

  function clear() {
    rootEl.textContent = '';
    rootEl.classList.remove('scrimmed', 'out');
    settings = null;
  }

  // ---- splash ----
  function renderSplash(s) {
    clear();
    rootEl.style.background = '#000';
    var spin = h('div.spin');
    var foot = h('div.foot', null, h('div', { text: s.credit ? 'by ' + s.credit : '' }),
      h('div', { text: 'v' + s.app.version + ' (build ' + s.app.build + ')' + (s.app.cfg ? ' · cfg ' + s.app.cfg : '') }));
    var logo = s.icon ? h('img.logo', { src: s.icon, alt: '' }) : h('div.logo');
    var name = h('div.name', { text: s.app.name });
    var tag = h('div.tag', { text: s.tagline });
    rootEl.appendChild(h('div.splash', null, logo, name, tag, spin, foot));
    play(logo, [{ opacity: 0, transform: 'scale(0.92)' }, { opacity: 1, transform: 'scale(1)' }], T.expand);
    [name, tag, foot].forEach(function (el, i) {
      play(el, [{ opacity: 0 }, { opacity: 1 }], T.soft, { delay: 60 + i * 50, duration: 400 });
    });
    setTimeout(function () { spin.classList.add('on'); }, 400); // the spinner only shows when loading takes longer
  }

  // ---- chooser ----
  function renderChooser(s) {
    clear();
    rootEl.style.background = '#000';
    if (chosen === null || !s.sources.some(function (x) { return x.id === chosen; })) {
      chosen = s.preselectedId;
      always = !s.askEveryLaunch;
    }
    var list = h('div.group');
    var rows = s.sources.map(function (x) {
      var check = h('span.check');
      var dot = h('span.dot.plain');
      var stx = h('span');
      var status = h('span.status', null, dot, stx);
      var el = h('button.row.pick', { role: 'radio', onclick: function () { chosen = x.id; refresh(); } },
        UI.tile(x, 44, false),
        h('span.label', null, h('span', { text: x.name }), x.id === s.recommendedId ? h('span.pill', { text: s.chooser.recommendedLabel }) : null),
        status, check);
      return { el: el, id: x.id, check: check, dot: dot, stx: stx, x: x };
    });
    rows.forEach(function (r) { list.appendChild(r.el); });
    var sw = h('span.switch', { role: 'switch' });
    var alwaysRow = h('button.row.plain', { role: 'switch', onclick: function () { always = !always; refresh(); } },
      h('span.label', { text: s.chooser.alwaysLabel }), sw);
    var cta = h('button.cta', { text: s.chooser.cta, onclick: submit });
    var col = h('div.col', null, h('h1', { text: s.chooser.title }), h('div.sub', { text: s.chooser.subtitle }), list,
      h('div.group.nobadge', { style: 'margin-top:18px' }, alwaysRow), cta);
    rootEl.appendChild(h('div.center', null, col));
    function refresh() {
      var cur = state || s;
      rows.forEach(function (r) {
        var live = cur.sources.find(function (y) { return y.id === r.id; }) || r.x;
        r.check.textContent = r.id === chosen ? '✓' : '';
        r.el.setAttribute('aria-checked', String(r.id === chosen));
        r.dot.className = 'dot plain h-' + live.health;
        r.stx.textContent = live.health === 'unknown' ? '' : UI.HEALTH[live.health];
      });
      sw.setAttribute('aria-checked', String(always));
    }
    function submit() { window.movy.act('choose', { id: chosen, always: always }); }
    rootEl.chooserRefresh = refresh;
    rootEl.chooserSubmit = submit;
    refresh();
    play(col, [{ opacity: 0, transform: 'translateY(14px)' }, { opacity: 1, transform: 'translateY(0)' }], T.soft);
  }

  // ---- error ----
  // A branded screen for real failures only (offline, blocked, not responding). Never a URL or a raw browser error page.
  var retryTimer = null;
  function renderError(s) {
    clear();
    rootEl.style.background = '#0f0f0f';
    var e = s.error || { title: s.app.name + ' isn’t responding', body: 'Check your connection and try again.' };
    var retry = h('button.cta', { text: 'Retry', onclick: onRetry });
    function onRetry() {
      retry.textContent = 'Retrying…';
      retry.disabled = true;
      window.movy.act('retry');
      clearTimeout(retryTimer);
      retryTimer = setTimeout(function () { retry.textContent = 'Retry'; retry.disabled = false; }, 2500);
    }
    rootEl.retry = onRetry;
    var col = h('div.col', null, h('div.big', { text: '⚠' }), h('h1', { text: e.title }),
      h('div.sub', { text: e.body }), retry,
      e.suggestionId ? h('button.cta.plain', { text: 'Switch to ' + e.suggestionName, onclick: function () { window.movy.act('switch', { id: e.suggestionId }); } }) : null);
    rootEl.appendChild(h('div.center.err', null, col));
    play(col, [{ opacity: 0, transform: 'translateY(10px)' }, { opacity: 1, transform: 'translateY(0)' }], T.soft);
  }
  // Coming back online retries by itself.
  window.addEventListener('online', function () { if (mode === 'error' && rootEl.retry) rootEl.retry(); });

  // ---- settings ----
  function renderSettings(s) {
    clear();
    rootEl.style.background = 'transparent';
    rootEl.classList.add('scrimmed');
    rootEl.addEventListener('mousedown', onScrim);
    settings = Settings.create(rootEl, window.movy.act);
    settings.open(s);
  }
  function onScrim(e) {
    if (e.target === rootEl) window.movy.act('close-settings');
  }

  // ---- mode switching ----
  function enter(next, s) {
    clearTimeout(fading);
    mode = next;
    rootEl.removeEventListener('mousedown', onScrim);
    if (next === 'splash') renderSplash(s);
    else if (next === 'chooser') renderChooser(s);
    else if (next === 'error') renderError(s);
    else if (next === 'settings') renderSettings(s);
  }

  function leave() {
    var prev = mode;
    mode = null;
    if (prev === 'settings' && settings) {
      var st = settings;
      rootEl.classList.remove('scrimmed');
      st.close().then(function () { rootEl.textContent = ''; window.movy.act('panel-hidden'); });
      return;
    }
    rootEl.classList.add('out');
    fading = setTimeout(function () { rootEl.textContent = ''; window.movy.act('panel-hidden'); }, 320);
  }

  function apply(s) {
    var wanted = s.panel;
    if (wanted !== mode) {
      if (wanted) enter(wanted, s);
      else if (mode) leave();
      return;
    }
    if (mode === 'settings' && settings) settings.update(s);
    else if (mode === 'chooser' && rootEl.chooserRefresh) rootEl.chooserRefresh();
    else if (mode === 'error') {
      var shown = rootEl.querySelector('h1');
      var wantTitle = s.error && s.error.title;
      var cur = rootEl.querySelector('.cta.plain');
      var want = s.error && s.error.suggestionId;
      if ((shown && wantTitle && shown.textContent !== wantTitle) || !!cur !== !!want) renderError(s);
    }
  }

  window.movy.onPush(function (msg) {
    if (msg.type === 'state') {
      state = msg.state;
      apply(state);
    } else if (msg.type === 'toast' && settings) {
      settings.toast(msg.text);
    }
  });
  window.movy.state().then(function (s) {
    if (!s) return;
    state = s;
    apply(s);
  });

  document.addEventListener('keydown', function (e) {
    if (mode === 'settings') {
      if (e.key === 'Escape') {
        if (!settings.back()) window.movy.act('close-settings');
      }
    } else if (mode === 'chooser' && state) {
      var ids = state.sources.map(function (x) { return x.id; });
      var i = ids.indexOf(chosen);
      if (e.key === 'ArrowDown' || e.key === 'ArrowUp') {
        chosen = ids[(i + (e.key === 'ArrowDown' ? 1 : ids.length - 1)) % ids.length];
        rootEl.chooserRefresh();
        e.preventDefault();
      } else if (e.key === 'Enter') {
        rootEl.chooserSubmit();
      }
    }
  });
})();
