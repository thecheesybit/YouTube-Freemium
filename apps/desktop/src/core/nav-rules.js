'use strict';
// Pure navigation policy, a port of the Android NavRules. The app never launches another app or a browser: every
// decision is "load it here" or "drop it". Main frame: only allowed hosts, plus (a) server redirects out of the start
// page (domain moves), (b) sign-in hosts for a user gesture. Popups: only a gesture to an allowed host, loaded in the
// main view. Subframes are not host-restricted here; the ad engine decides.
const { parseParts, hostMatches } = require('./url-parts');

const ALLOW = 'allow';
const KILL = 'kill';

/** Built-in sign-in hosts; "host/prefix" limits a host to a path prefix. */
const DEFAULT_AUTH_HOSTS = Object.freeze([
  'accounts.google.com', 'appleid.apple.com', 'www.facebook.com/login', 'm.facebook.com/login',
  'discord.com/oauth2', 'challenges.cloudflare.com',
]);

const lower = (a) => (a ?? []).map((s) => String(s).toLowerCase());

class NavRules {
  /** @param {{startUrl?:string, allowedHosts?:string[], authHosts?:string[], externalLinks?:string}} o */
  constructor({ startUrl, allowedHosts, authHosts, externalLinks } = {}) {
    this.allowed = lower(allowedHosts);
    this.baseAuth = lower(authHosts);
    this.auth = [...DEFAULT_AUTH_HOSTS, ...this.baseAuth];
    this.anyWeb = externalLinks === 'in-app';
    this.startHosts = new Set(startUrl ? [parseParts(startUrl).host] : []);
    this.mirrors = new Set();
    this.chainTrusted = true; // the current main-frame chain began at a start page
    this.chainAuth = false; // the current chain began with a user-initiated sign-in navigation
  }

  /** Live update (remote config / source change). Takes effect for the next decision. */
  update({ allowedHosts, startHosts, authHosts }) {
    this.allowed = lower(allowedHosts);
    this.startHosts = new Set(startHosts ?? []);
    this.auth = [...DEFAULT_AUTH_HOSTS, ...this.baseAuth, ...lower(authHosts)];
  }

  isAllowedHost(host) {
    if (!host) return false;
    const h = String(host).toLowerCase();
    return this.mirrors.has(h) || this.allowed.some((p) => hostMatches(p, h));
  }

  isAuthTarget(parts) {
    return this.auth.some((pattern) => {
      const slash = pattern.indexOf('/');
      const hp = slash < 0 ? pattern : pattern.slice(0, slash);
      if (!hostMatches(hp, parts.host)) return false;
      return slash < 0 || parts.path.startsWith(pattern.slice(slash));
    });
  }

  /** Patterns the page script may treat as on-site (configured + learned mirrors). */
  allowedPatterns() {
    return [...this.allowed, ...this.mirrors];
  }

  authPatterns() {
    return [...this.auth];
  }

  isStartPage(p) {
    const root = p.path === '' || p.path === '/';
    return root && (this.startHosts.has(p.host) || this.mirrors.has(p.host));
  }

  /** A programmatic main-frame load (source switch, retry, restore): re-bases the chain trust. */
  beginLoad(url) {
    this.chainTrusted = this.isStartPage(parseParts(url));
    this.chainAuth = false;
  }

  /** Main-frame navigation. `currentUrl` is the page being left (may be empty). */
  mainFrame(url, redirect, gesture, currentUrl) {
    const p = parseParts(url);
    switch (p.scheme) {
      case 'about':
        return String(url).trim().toLowerCase() === 'about:blank' ? ALLOW : KILL;
      case 'javascript':
      case 'blob':
        return ALLOW;
      case 'http':
      case 'https':
        break;
      default:
        return KILL; // data:, file:, intent:, market:, tel:, mailto:, custom schemes
    }
    if (this.isAllowedHost(p.host)) {
      if (!redirect) {
        this.chainTrusted = this.isStartPage(p);
        this.chainAuth = false;
      }
      return ALLOW;
    }
    if (redirect && this.chainTrusted) {
      this.mirrors.add(p.host); // domain move / mirror: remember for this session
      return ALLOW;
    }
    if (this.isAuthTarget(p)) {
      const onAuthPage = this.isAuthTarget(parseParts(currentUrl));
      if ((!redirect && (gesture || onAuthPage)) || (redirect && this.chainAuth)) {
        this.chainAuth = true;
        this.chainTrusted = false;
        return ALLOW;
      }
      return KILL;
    }
    return this.anyWeb ? ALLOW : KILL;
  }

  /** Popup (window.open / target=_blank): never a new window; either load in the main view or drop. */
  popupOpensInMain(url, gesture, blockedByEngine) {
    if (!gesture || blockedByEngine) return false;
    const p = parseParts(url);
    return p.isWeb && this.isAllowedHost(p.host);
  }

  /** Iframe navigation: not host-restricted (the ad engine decides); only other-app schemes are dropped. */
  subframeAllowed(url) {
    return ['http', 'https', 'about', 'data', 'blob', 'javascript'].includes(parseParts(url).scheme);
  }
}

module.exports = { NavRules, ALLOW, KILL, DEFAULT_AUTH_HOSTS };
