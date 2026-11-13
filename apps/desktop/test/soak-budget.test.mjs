import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const uiDir = path.join(__dirname, '..', 'src', 'ui');

const { buildUiState } = require('../src/core/ui-state.js');
const { loadAppConfig } = require('../src/main/config.js');
const { SourceRepository } = require('../src/core/repository.js');
const { BlockStats } = require('../src/main/stats.js');

test('S2: no plain URL strings from config in the UI state or HTML/DOM templates', () => {
  const { app, defaults } = loadAppConfig(path.join(__dirname, '..', 'gen'));
  const repo = new SourceRepository({ get: () => null, set: () => {} }, defaults);
  repo.switchTo('youtube');

  // 1. UI state snapshot must never contain URLs
  const state = buildUiState({
    app,
    repo,
    prefs: { get: () => null },
    healthOf: () => 'up',
    stats: new BlockStats(),
    adblockConfigured: true,
    storageBytes: 1024,
    checking: false,
    island: { shown: true },
    suggestion: null
  });

  const stateJson = JSON.stringify(state);
  assert.equal(stateJson.includes('https://'), false, 'UI state must not leak https://');
  assert.equal(stateJson.includes('youtube.com'), false, 'UI state must not leak hostnames');

  // 2. UI HTML files must never hardcode source URLs
  const htmlFiles = fs.readdirSync(uiDir).filter((f) => f.endsWith('.html'));
  for (const f of htmlFiles) {
    const content = fs.readFileSync(path.join(uiDir, f), 'utf8');
    assert.equal(content.includes('https://www.youtube.com'), false, `${f} must not contain start URLs`);
    assert.equal(content.includes('https://music.youtube.com'), false, `${f} must not contain start URLs`);
  }
});
