'use strict';
// Chromium drops cookies without an expiry when the app quits. Logins that rely on such session cookies
// must survive restarts, so they are snapshotted on quit and restored before the first page load.
// Only cookies of allowed sites are stored (userData/session-cookies.json).
const fs = require('node:fs');
const path = require('node:path');

const FILE = 'session-cookies.json';

function cookieUrl(c) {
  const host = c.domain.replace(/^\./, '');
  return `${c.secure ? 'https' : 'http'}://${host}${c.path || '/'}`;
}

async function saveSessionCookies(ses, sites, dir, log) {
  try {
    const all = await ses.cookies.get({});
    const keep = all.filter((c) => c.session && sites.isAllowedHost(c.domain.replace(/^\./, '')));
    fs.mkdirSync(dir, { recursive: true });
    fs.writeFileSync(path.join(dir, FILE), JSON.stringify(keep));
    log(`[session] saved ${keep.length} session cookies`);
  } catch (e) {
    log('[session] saving cookies failed:', e.message);
  }
}

async function restoreSessionCookies(ses, sites, dir, log) {
  let list = [];
  try {
    list = JSON.parse(fs.readFileSync(path.join(dir, FILE), 'utf8'));
  } catch {
    return;
  }
  let n = 0;
  for (const c of Array.isArray(list) ? list : []) {
    try {
      if (!c || typeof c.name !== 'string' || typeof c.domain !== 'string') continue;
      if (!sites.isAllowedHost(c.domain.replace(/^\./, ''))) continue;
      await ses.cookies.set({
        url: cookieUrl(c),
        name: c.name,
        value: c.value,
        domain: c.hostOnly ? undefined : c.domain,
        path: c.path,
        secure: c.secure,
        httpOnly: c.httpOnly,
        sameSite: c.sameSite,
      });
      n++;
    } catch {
      /* skip a cookie that no longer validates */
    }
  }
  log(`[session] restored ${n} session cookies`);
}

module.exports = { saveSessionCookies, restoreSessionCookies };
