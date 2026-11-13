'use strict';
const { ipcMain } = require('electron');
const { isHttp } = require('./hosts');

const MAX_LIST = 20000;
const strings = (a) => (Array.isArray(a) ? a.filter((s) => typeof s === 'string').slice(0, MAX_LIST) : []);

/**
 * Sync IPC used by the site preload (the only channel the remote frames have into the main process).
 * Only webContents accepted by `isSite` are served; URLs come from the sender frame, not the renderer.
 * `assets` = { cosmeticJs, loadingJs, scamguardJs, navguardJs }; `navConfig()` = { allow, auth } for navguard.
 */
function installCosmeticIpc({ engine, cosmetic, isEnabled, assets, navConfig, isSite, stats }) {
  const trusted = (e) => isSite(e.sender);
  const active = () => !!(engine && cosmetic && isEnabled());

  ipcMain.on('movy:boot', (e) => {
    e.returnValue = trusted(e)
      ? { cosmetic: active(), nav: navConfig(), ...assets }
      : null;
  });

  ipcMain.on('movy:cosmetic', (e) => {
    let out = '{}';
    try {
      const url = e.senderFrame?.url ?? '';
      if (trusted(e) && active() && isHttp(url)) {
        out = engine.urlCosmeticResources(url);
        stats.recordCosmetic(JSON.parse(out));
      }
    } catch {
      out = '{}';
    }
    e.returnValue = out;
  });

  ipcMain.on('movy:hidden', (e, classes, ids, exceptions) => {
    let out = '[]';
    try {
      if (trusted(e) && active()) {
        out = engine.hiddenClassIdSelectors(strings(classes), strings(ids), strings(exceptions));
      }
    } catch {
      out = '[]';
    }
    e.returnValue = out;
  });
}

module.exports = { installCosmeticIpc };
