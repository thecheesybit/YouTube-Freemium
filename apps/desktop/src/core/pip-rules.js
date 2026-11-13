'use strict';
// Picture-in-Picture rules, shared with the Android edition's behaviour:
// - only for video (never Music audio), only while it is actually playing, only when the setting is on
// - never while already in PiP, and not on non-watch pages (home/search previews are not "the video")
// - the in-player PiP button exists on watch pages only and is hidden on Music, during ads and in PiP

const isWatchPath = (pathname) => /^\/watch(\/|$)/.test(String(pathname || ''));

/**
 * Why PiP cannot start, or null when it can. Reasons are short codes (safe to log).
 * @param {{enabled:boolean, inPip:boolean, sourceId:string|null, media:{playing?:boolean,isVideo?:boolean}|null, pathname:string}} i
 */
function pipBlocker(i) {
  if (!i) return 'no-input';
  if (i.inPip) return 'already-in-pip';
  if (i.enabled === false) return 'disabled';
  if (i.sourceId === 'youtube-music') return 'music';
  if (!isWatchPath(i.pathname)) return 'not-watch-page';
  if (!i.media || !i.media.isVideo) return 'no-video';
  if (!i.media.playing) return 'not-playing';
  return null;
}

const canEnterPip = (i) => pipBlocker(i) === null;

/** Mirrors ensureSpeedChip() in the page script (used for tests and documentation). */
function pipButtonVisible({ pathname, host = '', inPip = false, adActive = false }) {
  if (!isWatchPath(pathname)) return false;
  if (String(host).includes('music.youtube.com')) return false;
  return !inPip && !adActive;
}

/** Left offset of the PiP button: right of the speed pill with an 8 px gap. */
const pipButtonLeft = (speedPillWidth, margin = 12, gap = 8) => margin + speedPillWidth + gap;

module.exports = { isWatchPath, pipBlocker, canEnterPip, pipButtonVisible, pipButtonLeft };
