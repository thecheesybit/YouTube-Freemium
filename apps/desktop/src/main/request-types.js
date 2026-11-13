'use strict';
// Electron webRequest resourceType -> adblock request type.
const TYPES = {
  mainFrame: 'document',
  subFrame: 'subdocument',
  stylesheet: 'stylesheet',
  script: 'script',
  image: 'image',
  font: 'font',
  object: 'object',
  xhr: 'xmlhttprequest',
  ping: 'ping',
  cspReport: 'csp_report',
  media: 'media',
  webSocket: 'websocket',
  other: 'other',
};

function toAdblockType(resourceType) {
  return TYPES[resourceType] ?? 'other';
}

module.exports = { toAdblockType, TYPES };
