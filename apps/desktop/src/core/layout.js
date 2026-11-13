'use strict';
// Pure window layout maths: a slim shell chrome strip on top (history buttons + drag area, with the native window
// buttons drawn inside it) and the site area below it, so nothing of the page is ever covered by window controls.

const CHROME_H = 36; // also the height of the native title bar overlay (window buttons)
const BAR_H = 3;
const PILL = Object.freeze({ w: 280, h: 70 });
const ISLAND = Object.freeze({ collapsedW: 28, collapsedH: 88, expandedW: 150, margin: 8 });

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));
const whole = (v) => Math.max(0, Math.round(Number.isFinite(v) ? v : 0));

/** Height of the chrome strip: none in fullscreen (the page owns the whole window). */
function chromeHeight({ fullscreen = false, chromeH = CHROME_H } = {}) {
  return fullscreen ? 0 : chromeH;
}

/**
 * Bounds (content-area coordinates, integers) of every layer.
 * @param {{width:number,height:number,fullscreen?:boolean,chromeH?:number,islandMode?:string,islandHeight?:number,dragTop?:number|null}} i
 */
function computeLayout(i) {
  const width = whole(i.width);
  const height = whole(i.height);
  const top = Math.min(chromeHeight(i), height);
  const siteH = Math.max(0, height - top);
  const site = { x: 0, y: top, width, height: siteH };
  const chrome = { x: 0, y: 0, width, height: top };
  const bar = { x: 0, y: top, width, height: Math.min(BAR_H, siteH) };
  const pill = {
    x: Math.max(0, Math.round((width - PILL.w) / 2)),
    y: top,
    width: Math.min(PILL.w, width),
    height: Math.min(PILL.h, siteH)
  };
  return { chrome, site, panel: { ...site }, bar, pill, island: islandBounds(i, site) };
}

function islandBounds(i, site) {
  if (i.fullscreen || i.islandMode === 'hidden') return null;
  const expanded = i.islandMode === 'expanded';
  const w = expanded ? ISLAND.expandedW : ISLAND.collapsedW;
  const h = expanded
    ? Math.min(Math.max(0, site.height - ISLAND.margin * 2), clamp(Math.round(i.islandHeight ?? 220), 120, 900))
    : Math.min(ISLAND.collapsedH, site.height);
  const hasDrag = typeof i.dragTop === 'number' && Number.isFinite(i.dragTop);
  const preferred = hasDrag ? i.dragTop : (site.height - h) / 2;
  const y = clamp(Math.round(preferred), ISLAND.margin, Math.max(ISLAND.margin, site.height - h - ISLAND.margin));
  return { x: Math.max(0, site.width - w), y: site.y + y, width: w, height: h };
}

module.exports = { CHROME_H, BAR_H, PILL, ISLAND, chromeHeight, computeLayout };
