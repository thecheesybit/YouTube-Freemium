'use strict';
const fs = require('node:fs');
const path = require('node:path');

const NODE_FILE = 'movy_adblock_node.node';

/** Loads the napi binding from `dir` (must run in the main / a plain Node process). */
function loadBinding(dir) {
  return require(path.join(dir, NODE_FILE));
}

/**
 * Loads engine.dat (+ resources.json) from `dir` through the native binding.
 * Returns null when the files are absent (adblock disabled at build time).
 */
function loadEngine(dir) {
  const dat = path.join(dir, 'engine.dat');
  if (!fs.existsSync(dat)) return null;
  const resFile = path.join(dir, 'resources.json');
  const resources = fs.existsSync(resFile) ? fs.readFileSync(resFile, 'utf8') : undefined;
  const { Engine } = loadBinding(dir);
  return Engine.fromSerialized(fs.readFileSync(dat), resources);
}

module.exports = { loadBinding, loadEngine, NODE_FILE };
