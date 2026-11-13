'use strict';
// Builds the snapshot the UI views render. Pure. Never contains a URL or host: only names, accents and monograms.

const monogram = (name) => ([...String(name).trim()][0] ?? '?').toUpperCase();

/**
 * @param {{app:object, repo:object, prefs:object, healthOf:(id:string)=>string, stats:object, panel:object,
 *          island:object, storageBytes:number|null, checking:boolean, adblockConfigured:boolean}} i
 */
function buildUiState(i) {
  const cfg = i.repo.config();
  const active = i.repo.active();
  const sources = i.repo.enabledSources().map((s) => ({
    id: s.id,
    name: s.name,
    accent: s.accent,
    mono: monogram(s.name),
    health: i.healthOf(s.id),
  }));
  return {
    app: { name: i.app.appName, version: i.app.version, build: i.app.build, cfg: i.app.remote && cfg.contentVersion > 0 ? cfg.contentVersion : 0 },
    tagline: cfg.tagline,
    credit: cfg.credit,
    chooser: cfg.chooser,
    splashMinMs: cfg.splashMinMs,
    sources,
    activeId: active ? active.id : null,
    defaultId: i.repo.defaultSource().id,
    preselectedId: i.repo.preselected().id,
    recommendedId: cfg.recommendedSource,
    askEveryLaunch: !!i.prefs.get('askEveryLaunch'),
    adblock: { configured: i.adblockConfigured, on: !!i.prefs.get('adblockEnabled'), ads: i.stats.adsBlocked(), redirects: i.stats.redirectsStopped() },
    storageBytes: i.storageBytes,
    checking: i.checking,
    island: { ...i.island, y: i.prefs.get('islandY') },
    suggestion: i.suggestion ?? null,
  };
}

module.exports = { buildUiState, monogram };
