'use strict';
// IPC between the main process and the app's own UI views (island, panel).
const fs = require('node:fs');
const path = require('node:path');
const { ipcMain } = require('electron');
const { buildUiState } = require('../core/ui-state');

const PUSH_MS = 40;
const MODES = new Set(['hidden', 'collapsed', 'expanded']);

function createUiBridge({ views, repo, prefs, health, stats, app, gen, settings, ui, extra, actions, log }) {
  const targets = () => views.uiContents().filter((wc) => !wc.isDestroyed());
  const isUi = (wc) => targets().some((t) => t.id === wc.id);
  let icon = null;
  try {
    icon = 'data:image/png;base64,' + fs.readFileSync(path.join(gen, 'icon.png')).toString('base64');
  } catch {
    if (log) log('[ui] icon missing');
  }

  const snapshot = () => {
    const base = buildUiState({
      app,
      repo,
      prefs,
      healthOf: (id) => (health && health.status ? health.status(id) : 'up'),
      stats,
      adblockConfigured: true,
      storageBytes: ui.storageBytes || 0,
      checking: false,
      island: { shown: ui.islandShown !== false },
      suggestion: null
    });
    return {
      ...base,
      settings: settings ? settings.all() : {},
      panel: ui.panel,
      icon,
      ...(extra ? extra() : {})
    };
  };

  let timer = null;
  const send = (msg) => targets().forEach((wc) => wc.send('ui:push', msg));
  const pushState = () => {
    if (timer) return;
    timer = setTimeout(() => {
      timer = null;
      send({ type: 'state', state: snapshot() });
    }, PUSH_MS);
  };

  ipcMain.handle('ui:state', (e) => (isUi(e.sender) ? snapshot() : null));

  ipcMain.handle('ui:island-view', (e, msg) => {
    if (!isUi(e.sender) || !msg || !MODES.has(msg.mode)) return false;
    views.setIslandMode(msg.mode, msg.height);
    return true;
  });

  const str = (v) => (typeof v === 'string' && v.length <= 60 ? v : null);
  const known = (v) => {
    const id = str(v);
    const s = id ? repo.byId(id) : null;
    return s && s.enabled ? s.id : null;
  };

  ipcMain.on('ui:act', (e, m) => {
    if (!isUi(e.sender) || !m || typeof m.type !== 'string') return;
    try {
      switch (m.type) {
        case 'pick':
        case 'switch': {
          const id = known(m.id);
          if (id) actions.switchSource(id);
          break;
        }
        case 'settings':
          actions.openSettings();
          break;
        case 'close-panel':
        case 'close-settings':
          actions.closePanel();
          break;
        case 'set-setting':
          if (actions.setSetting && m.key) actions.setSetting(m.key, m.value);
          pushState();
          break;
        case 'clear-cache':
          if (actions.clearCache) actions.clearCache();
          break;
        case 'clear-data':
          if (actions.clearData) actions.clearData();
          break;
        case 'update-lists':
          if (actions.updateLists) actions.updateLists();
          break;
        case 'nav-back':
          actions.goBack();
          break;
        case 'nav-forward':
          actions.goForward();
          break;
        case 'reload':
          actions.reload();
          break;
        case 'retry':
          actions.retry();
          break;
        case 'panel-hidden':
          break;
        case 'island-expanded':
          if (actions.islandExpanded) actions.islandExpanded(m.on === true);
          break;
        default:
          break;
      }
    } catch (err) {
      if (log) log(`[ui] action ${m.type} failed:`, err.message);
    }
  });

  return { pushState, snapshot };
}

module.exports = { createUiBridge };
