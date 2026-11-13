'use strict';
const fs = require('node:fs');
const path = require('node:path');

let file = null;

function sanitize(msg) {
  return String(msg).replace(/https?:\/\/[^\s"'<>]+/gi, '[URL]');
}

/** Mirrors console output into <dir>/desktop.log (truncated per launch). */
function initLog(dir) {
  try {
    fs.mkdirSync(dir, { recursive: true });
    file = path.join(dir, 'desktop.log');
    fs.writeFileSync(file, '');
  } catch {
    file = null;
  }
}

function log(...parts) {
  const line = `${new Date().toISOString()} ${parts.map(sanitize).join(' ')}`;
  console.log(line);
  if (file) {
    try {
      fs.appendFileSync(file, line + '\n');
    } catch {
      /* logging must never throw */
    }
  }
}

module.exports = { initLog, log };
