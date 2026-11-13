'use strict';
// Preload for the app's own UI views (island, panel).
const { contextBridge, ipcRenderer } = require('electron');

const api = {
  state: () => ipcRenderer.invoke('ui:state'),
  act: (type, payload) => ipcRenderer.send('ui:act', { type, ...(payload && typeof payload === 'object' ? payload : {}) }),
  islandView: (mode, height) => ipcRenderer.invoke('ui:island-view', { mode, height }),
  onPush: (cb) => ipcRenderer.on('ui:push', (_e, msg) => cb(msg)),
  splashDone: () => ipcRenderer.invoke('ytf:splash-done')
};

contextBridge.exposeInMainWorld('movy', api);
contextBridge.exposeInMainWorld('ytf', api);
contextBridge.exposeInMainWorld('electronBridge', api);
