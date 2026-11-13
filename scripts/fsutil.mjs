import fs from 'node:fs';
import path from 'node:path';

/** Newest mtime (ms) of any file below `dir`, skipping directory names in `skip`. */
export function newestMtime(dir, skip = new Set(['target', 'node_modules'])) {
  let newest = 0;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skip.has(e.name)) continue;
    const p = path.join(dir, e.name);
    newest = Math.max(newest, e.isDirectory() ? newestMtime(p, skip) : fs.statSync(p).mtimeMs);
  }
  return newest;
}
