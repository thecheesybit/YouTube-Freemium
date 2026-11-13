'use strict';
// Single source of truth for the live config, the active source and the last URL per source (port of
// SourceRepository). Listeners are called synchronously on the main process thread.
const live = require('./live-config');
const { belongsTo } = require('./home-page');

class SourceRepository {
  /** @param {{get:Function,set:Function}} prefs  @param {object} initial live config */
  constructor(prefs, initial) {
    this.prefs = prefs;
    this.cfg = initial;
    this.activeId = null;
    this.lastUrls = new Map();
    this.listeners = new Set();
    this.loader = null;
  }

  /** The loader opens `url` of `source` in the site view. */
  setLoader(fn) {
    this.loader = fn;
  }

  config() {
    return this.cfg;
  }

  enabledSources() {
    return live.enabledSources(this.cfg);
  }

  active() {
    return live.find(this.cfg, this.activeId);
  }

  byId(id) {
    return live.find(this.cfg, id);
  }

  addListener(l) {
    this.listeners.add(l);
  }

  emit(name, arg) {
    for (const l of this.listeners) {
      try {
        l[name]?.(arg);
      } catch (e) {
        console.error(`[repo] listener ${name} failed:`, e.message);
      }
    }
  }

  /** Replaces the live config and notifies listeners. */
  update(next) {
    this.cfg = next;
    this.emit('onConfigChanged', next);
  }

  /** Source to open at launch: the saved default when enabled and "ask every launch" is off, else null (chooser). */
  launchSource() {
    const s = live.find(this.cfg, this.prefs.get('defaultSourceId'));
    return s && s.enabled && !this.prefs.get('askEveryLaunch') ? s : null;
  }

  /** Source to preselect in the chooser: last used, else the recommended one. */
  preselected() {
    const last = live.find(this.cfg, this.prefs.get('lastSourceId'));
    return last && last.enabled ? last : live.recommended(this.cfg);
  }

  /** Default source shown in Settings: the saved default when valid, else the preselected one. */
  defaultSource() {
    const s = live.find(this.cfg, this.prefs.get('defaultSourceId'));
    return s && s.enabled ? s : this.preselected();
  }

  /** Switches to a source: its last URL in this session, or its start URL. */
  switchTo(id) {
    const s = live.find(this.cfg, id);
    if (s && s.enabled) this.activate(s, this.lastUrl(s));
  }

  /** Loads the start URL of a source, ignoring its remembered page. */
  openStart(id) {
    const s = live.find(this.cfg, id);
    if (s && s.enabled) this.activate(s, s.url);
  }

  activate(s, url) {
    this.activeId = s.id;
    this.prefs.set('lastSourceId', s.id);
    this.loader?.(s, url);
    this.emit('onActiveSourceChanged', s);
  }

  lastUrl(s) {
    return this.lastUrls.get(s.id) ?? s.url;
  }

  /** Called on every main-frame URL change; the page must belong to the active source. Not persisted. */
  rememberUrl(url) {
    const a = this.active();
    if (a && url && belongsTo(a, url)) this.lastUrls.set(a.id, url);
  }
}

module.exports = { SourceRepository };
