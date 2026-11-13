'use strict';
// Decides when the Island is shown (port of the Android IslandController visibility logic): on home pages of a source
// (island.visibility "home") or always, never over fullscreen video, the chooser/splash or Settings; the error panel
// always shows it. Learns, per source, the landing page of start pages that redirect (e.g. "/" -> "/home").
const home = require('./home-page');

const LANDING_WINDOW_MS = 3000;

class IslandVisibility {
  /** @param {{repo:object, now?:()=>number, onChange:(shown:boolean)=>void}} o */
  constructor({ repo, now = Date.now, onChange }) {
    this.repo = repo;
    this.now = now;
    this.onChange = onChange;
    this.homeNow = false;
    this.fullscreen = false;
    this.blocked = false; // splash / chooser / settings are up
    this.errorShown = false;
    this.forced = false; // Ctrl+K on a deep page
    this.shown = false;
    this.landing = null;
    this.landings = new Map();
    this.armLanding = false;
    this.landingUntil = 0;
  }

  /** Every main-frame URL change, including SPA route changes. */
  onUrl(url) {
    const active = this.repo.active();
    const t = this.now();
    if (this.armLanding && active && home.belongsTo(active, url)) {
      if (this.landing === null) this.landingUntil = t + LANDING_WINDOW_MS;
      if (t <= this.landingUntil) {
        this.landing = home.key(url);
        this.landings.set(active.id, this.landing);
      } else {
        this.armLanding = false;
      }
    }
    this.homeNow = home.isHomeOfAny(this.repo.enabledSources(), url) || (this.landing !== null && this.landing === home.key(url));
    this.refresh();
  }

  onActiveSourceChanged(active) {
    this.landing = this.landings.get(active.id) ?? null;
    this.landingUntil = 0;
    // Only a start-page load defines the landing page.
    this.armLanding = this.landing === null && this.repo.lastUrl(active) === active.url;
    this.homeNow = false;
    this.refresh();
  }

  setFullscreen(on) {
    this.fullscreen = on;
    this.refresh();
  }

  setBlocked(on) {
    this.blocked = on;
    this.refresh();
  }

  setError(on) {
    this.errorShown = on;
    this.refresh();
  }

  /** Ctrl+K: shows (forced) or lets the normal rule decide again. Returns whether it is shown now. */
  toggleForced() {
    if (this.fullscreen || this.blocked) return this.shown;
    this.forced = !this.shown ? true : false;
    this.refresh();
    return this.shown;
  }

  clearForced() {
    if (!this.forced) return;
    this.forced = false;
    this.refresh();
  }

  compute() {
    if (this.fullscreen || this.blocked) return false;
    if (this.forced || this.errorShown) return true;
    return this.repo.config().islandVisibility === 'always' || this.homeNow;
  }

  refresh() {
    const want = this.compute();
    if (want === this.shown) return;
    this.shown = want;
    this.onChange(want);
  }
}

module.exports = { IslandVisibility, LANDING_WINDOW_MS };
