'use strict';
const fs = require('node:fs');
const path = require('node:path');
const { app } = require('electron');

function genDir() {
  if (!app.isPackaged) return path.join(__dirname, '..', '..', 'gen');
  const ytf = path.join(process.resourcesPath, 'ytfreemium');
  if (fs.existsSync(ytf)) return ytf;
  return path.join(process.resourcesPath, 'movy');
}

const uiDir = () => path.join(__dirname, '..', 'ui');
const preloadPath = () => path.join(__dirname, '..', 'preload', 'site.js');
const uiPreloadPath = () => path.join(__dirname, '..', 'preload', 'ui.js');

module.exports = { genDir, uiDir, preloadPath, uiPreloadPath };
