'use strict';

/** Cheap request counters; produces a one-line summary for the log. */
class BlockStats {
  constructor() {
    this.checked = 0;
    this.blocked = 0;
    this.redirected = 0;
    this.cosmeticPages = 0;
    this.hideSelectors = 0;
    this.scriptlets = 0;
    this.kills = 0;
    this.byType = new Map();
    this.byHost = new Map();
  }

  recordBlock(url, type, redirected) {
    if (redirected) this.redirected++;
    else this.blocked++;
    this.byType.set(type, (this.byType.get(type) ?? 0) + 1);
    let host = '?';
    try {
      host = new URL(url).hostname;
    } catch {
      /* keep '?' */
    }
    this.byHost.set(host, (this.byHost.get(host) ?? 0) + 1);
  }

  /** A navigation / popup / overlay was stopped by the shell (not an engine block). */
  recordKill() {
    this.kills++;
  }

  adsBlocked() {
    return this.blocked + this.redirected;
  }

  redirectsStopped() {
    return this.kills;
  }

  recordCosmetic(res) {
    this.cosmeticPages++;
    this.hideSelectors += res.hide_selectors?.length ?? 0;
    if (res.injected_script) this.scriptlets++;
  }

  summary() {
    const top = [...this.byHost].sort((a, b) => b[1] - a[1]).slice(0, 5).map(([h, n]) => `${h}:${n}`).join(' ');
    const types = [...this.byType].map(([t, n]) => `${t}:${n}`).join(' ');
    return (
      `[adblock] checked=${this.checked} blocked=${this.blocked} redirected=${this.redirected} ` +
      `cosmetic(frames=${this.cosmeticPages} selectors=${this.hideSelectors} scriptlets=${this.scriptlets}) ` +
      `types{${types}} top{${top}}`
    );
  }
}

module.exports = { BlockStats };
