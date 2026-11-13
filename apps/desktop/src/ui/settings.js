'use strict';
// iOS-style Settings card for YouTube Freemium:
// Toggles: PiP, Background play, Keep playing in tray, Allow both sources, Show intro,
// Ad blocking, Cosmetic filtering, Start with Windows, Hardware acceleration.
// Buttons: Clear cache, Clear data, Update lists now, About dialog.
(function (root) {
  var h = UI.h;
  var play = UI.play;
  var T = UI.T;

  var ICON = {
    play: 'M8 5v14l11-7z',
    pip: 'M19 7h-8v6h8V7zm2-4H3c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h18c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16.01H3V4.99h18v14.02z',
    shield: 'M12 1 3 5v6c0 5.5 3.8 10.7 9 12 5.2-1.3 9-6.5 9-12V5l-9-4z',
    audio: 'M12 3v9.28c-.47-.17-.97-.28-1.5-.28C8.01 12 6 14.01 6 16.5S8.01 21 10.5 21c2.31 0 4.2-1.75 4.45-4H15V6h4V3h-7z',
    tray: 'M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5v-3h3.56c.69 1.19 1.97 2 3.44 2s2.75-.81 3.44-2H19v3zm0-5h-4.99c0 1.1-.9 2-2 2s-2-.9-2-2H5V5h14v9z',
    sparkle: 'M12 2L9.19 8.63 2 9.24l5.46 4.73L5.82 21 12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2z',
    gear: 'M19.4 13a7.6 7.6 0 0 0 0-2l2.1-1.6a.5.5 0 0 0 .1-.6l-2-3.5a.5.5 0 0 0-.6-.2l-2.5 1a7.4 7.4 0 0 0-1.7-1l-.4-2.6a.5.5 0 0 0-.5-.4h-4a.5.5 0 0 0-.5.4l-.4 2.6a7.4 7.4 0 0 0-1.7 1l-2.5-1a.5.5 0 0 0-.6.2l-2 3.5a.5.5 0 0 0 .1.6L4.6 11a7.6 7.6 0 0 0 0 2l-2.1 1.6a.5.5 0 0 0-.1.6l2 3.5c.1.2.4.3.6.2l2.5-1c.5.4 1.1.7 1.7 1l.4 2.6c0 .2.2.4.5.4h4c.3 0 .5-.2.5-.4l.4-2.6c.6-.3 1.2-.6 1.7-1l2.5 1c.2.1.5 0 .6-.2l2-3.5a.5.5 0 0 0-.1-.6L19.4 13zM12 15.5A3.5 3.5 0 1 1 12 8.5a3.5 3.5 0 0 1 0 7z',
    storage: 'M2 20h20v-4H2v4zm2-3h2v2H4v-2zM2 4v4h20V4H2zm4 3H4V5h2v2zm-4 7h20v-4H2v4zm2-3h2v2H4v-2z'
  };

  var BADGE = {
    blue: '#0a84ff',
    green: '#30d158',
    orange: '#ff9f0a',
    purple: '#bf5af2',
    grey: '#8e8e93',
    red: '#ff453a'
  };

  function badge(color, icon) {
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    var p = document.createElementNS('http://www.w3.org/2000/svg', 'path');
    p.setAttribute('d', ICON[icon] || ICON.gear);
    p.setAttribute('fill', '#ffffff');
    svg.appendChild(p);
    var b = h('span.badge', { style: 'background:' + color });
    b.appendChild(svg);
    return b;
  }

  function toggleRow(label, color, icon, read, onSet) {
    var sw = h('span.switch', { role: 'switch', 'aria-checked': 'false' });
    var el = h('button.row', { role: 'switch', onclick: function () { onSet(sw.getAttribute('aria-checked') !== 'true'); } },
      badge(color, icon), h('span.label', { text: label }), sw);
    return {
      el: el,
      update: function (s) {
        var on = !!read(s);
        sw.setAttribute('aria-checked', String(on));
        el.setAttribute('aria-checked', String(on));
      }
    };
  }

  function valueRow(label, color, icon, read) {
    var v = h('span.value');
    return {
      el: h('div.row', null, badge(color, icon), h('span.label', { text: label }), v),
      update: function (s) { v.textContent = read(s); }
    };
  }

  function actionRow(label, cls, onClick) {
    return {
      el: h('button.row.plain.' + cls, { onclick: onClick }, h('span.label', { text: label })),
      update: function () {}
    };
  }

  function section(head, foot, rows) {
    var group = h('div.group', null, rows.map(function (r) { return r.el; }));
    var el = h('div.section', null, head ? h('div.head', { text: head }) : null, group, foot || null);
    return {
      el: el,
      rows: rows,
      update: function (s) { rows.forEach(function (r) { r.update(s); }); }
    };
  }

  function create(rootEl, act) {
    var card = h('div.card', { role: 'dialog', 'aria-label': 'Settings' }, h('div.grab'));
    var pagesEl = h('div.pages');
    card.appendChild(pagesEl);
    rootEl.appendChild(card);

    var sections = [];

    function pageEl(title, backLabel, endLabel, onBack, onEnd, bodyChildren, big) {
      var back = h('button', { text: backLabel ? '‹ ' + backLabel : '', onclick: onBack });
      var end = h('button.end', { text: endLabel || '', onclick: onEnd });
      var body = h('div.body', null, big ? h('div.big', { text: big }) : null, bodyChildren);
      var page = h('div.page', null, h('div.bar', null, back, h('span.ttl', { text: big ? '' : title }), end), body);
      return { el: page, body: body };
    }

    function confirmAlert(title, message, confirmText, isDestructive, onConfirm) {
      if (confirm(title + '\n\n' + message)) {
        onConfirm();
      }
    }

    function openAbout() {
      alert('YouTube Freemium v1.0.0 (build 1)\n\nCrafted by Ayush Kumar ❤️\n\nAd-free YouTube & YouTube Music with Picture-in-Picture, background playback, and native controls.');
    }

    function buildRoot(s) {
      sections = [];
      var settings = (s && s.settings) || {};

      // Playback section
      sections.push(section('Playback', null, [
        toggleRow('Picture-in-Picture', BADGE.blue, 'pip',
          function (st) { return (st.settings && st.settings.pip !== undefined) ? st.settings.pip : true; },
          function (on) { act('set-setting', { key: 'pip', value: on }); }),
        toggleRow('Background Play', BADGE.purple, 'audio',
          function (st) { return (st.settings && st.settings.backgroundPlay !== undefined) ? st.settings.backgroundPlay : true; },
          function (on) { act('set-setting', { key: 'backgroundPlay', value: on }); }),
        toggleRow('Keep Playing in Tray', BADGE.grey, 'tray',
          function (st) { return (st.settings && st.settings.keepInTray !== undefined) ? st.settings.keepInTray : true; },
          function (on) { act('set-setting', { key: 'keepInTray', value: on }); }),
        toggleRow('Allow Both Sources to Play', BADGE.orange, 'audio',
          function (st) { return (st.settings && st.settings.allowBoth !== undefined) ? st.settings.allowBoth : false; },
          function (on) { act('set-setting', { key: 'allowBoth', value: on }); })
      ]));

      // Protection section
      sections.push(section('Protection', null, [
        toggleRow('Ad Blocking', BADGE.green, 'shield',
          function (st) { return (st.settings && st.settings.adblock !== undefined) ? st.settings.adblock : true; },
          function (on) { act('set-setting', { key: 'adblock', value: on }); }),
        toggleRow('Cosmetic Filtering', BADGE.green, 'sparkle',
          function (st) { return (st.settings && st.settings.cosmetic !== undefined) ? st.settings.cosmetic : true; },
          function (on) { act('set-setting', { key: 'cosmetic', value: on }); }),
        actionRow('Update Lists Now', 'blue', function () {
          act('update-lists');
          alert('Filter lists updated.');
        })
      ]));

      // System section
      sections.push(section('System', null, [
        toggleRow('Show Intro on Launch', BADGE.blue, 'sparkle',
          function (st) { return (st.settings && st.settings.showIntro !== undefined) ? st.settings.showIntro : true; },
          function (on) { act('set-setting', { key: 'showIntro', value: on }); }),
        toggleRow('Start with Windows', BADGE.grey, 'tray',
          function (st) { return (st.settings && st.settings.startWithWindows !== undefined) ? st.settings.startWithWindows : false; },
          function (on) { act('set-setting', { key: 'startWithWindows', value: on }); }),
        toggleRow('Hardware Acceleration', BADGE.orange, 'gear',
          function (st) { return (st.settings && st.settings.hardwareAcceleration !== undefined) ? st.settings.hardwareAcceleration : true; },
          function (on) { act('set-setting', { key: 'hardwareAcceleration', value: on }); })
      ]));

      // Storage section
      sections.push(section('Storage', null, [
        actionRow('Clear Cache (Keep Logins)', 'blue', function () {
          confirmAlert('Clear Cache?', 'Temporary files and static cache will be removed. You will stay signed in.', 'Clear', false, function () {
            act('clear-cache');
          });
        }),
        actionRow('Clear All Data & Sign Out', 'red', function () {
          confirmAlert('Clear All Data?', 'All logins, cookies and offline cache will be deleted. The app will restart.', 'Clear All', true, function () {
            act('clear-data');
          });
        })
      ]));

      // About section
      sections.push(section('About', null, [
        actionRow('About YouTube Freemium', 'blue', openAbout)
      ]));

      var rootPage = pageEl('Settings', '', 'Done', null, function () { act('close-panel'); },
        sections.map(function (s) { return s.el; }), 'Settings');
      pagesEl.textContent = '';
      pagesEl.appendChild(rootPage.el);
    }

    return {
      update: function (state) {
        if (!sections.length) buildRoot(state);
        sections.forEach(function (s) { if (s.update) s.update(state); });
      }
    };
  }

  root.UI = root.UI || {};
  root.UI.Settings = { create: create };
})(window);
