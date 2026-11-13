'use strict';
const { parseParts, hostMatches } = require('../core/url-parts');

// Allowed permissions per spec:
// media (audio output), fullscreen, clipboard-sanitized-write, notifications (allowed sites only)
// Everything else is denied (camera, microphone, geolocation, usb, hid, serial, pointerLock, etc.)
const ANYWHERE = new Set(['media', 'fullscreen', 'clipboard-sanitized-write']);
const ALLOWED_SITES_ONLY = new Set(['notifications']);

function isHostAllowed(url, navRules) {
  if (!url) return false;
  const parts = parseParts(url);
  if (!parts.host) return false;
  return navRules ? navRules.isAllowedHost(parts.host) : false;
}

function decide(permission, requestingUrl, navRules) {
  if (ANYWHERE.has(permission)) return true;
  if (ALLOWED_SITES_ONLY.has(permission)) {
    return isHostAllowed(requestingUrl, navRules);
  }
  return false;
}

function installPermissions(ses, navRules) {
  ses.setPermissionRequestHandler((_wc, permission, callback, details) => {
    callback(decide(permission, details.requestingUrl, navRules));
  });
  ses.setPermissionCheckHandler((_wc, permission, requestingOrigin) => {
    return decide(permission, requestingOrigin, navRules);
  });
  ses.setDevicePermissionHandler(() => false);
}

module.exports = { installPermissions, decide };
