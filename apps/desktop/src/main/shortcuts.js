'use strict';

const clamp = (v, lo, hi) => Math.max(lo, Math.min(hi, v));

/**
 * Keyboard shortcuts.
 * `actions` = {
 *   switchSource(id), toggleSource(), togglePip(), openSettings(),
 *   goBack(), goForward(), goHome(), reload(), hardReload(), quit()
 * }
 */
function installShortcuts(wc, win, actions, isSite = true) {
  const zoom = (delta) => wc.setZoomLevel(delta === 0 ? 0 : clamp(wc.getZoomLevel() + delta, -3, 5));

  wc.on('before-input-event', (event, input) => {
    if (input.type !== 'keyDown') return;
    const ctrl = input.control || input.meta;
    const key = input.key;
    const lower = key.toLowerCase();
    let handled = true;

    if (key === 'F11') {
      win.setFullScreen(!win.isFullScreen());
    } else if (ctrl && input.shift && lower === 'p') {
      actions.togglePip?.();
    } else if (ctrl && key === ',') {
      actions.openSettings?.();
    } else if (ctrl && !input.shift && key === '1') {
      actions.switchSource?.('youtube');
    } else if (ctrl && !input.shift && key === '2') {
      actions.switchSource?.('youtube-music');
    } else if (ctrl && key === 'Tab') {
      actions.toggleSource?.();
    } else if (ctrl && lower === 'q') {
      actions.quit?.();
    } else if (!isSite) {
      handled = false;
    } else if (key === 'Escape' && win.isFullScreen()) {
      win.setFullScreen(false);
    } else if (key === 'F5' || (ctrl && !input.shift && lower === 'r')) {
      actions.reload?.();
    } else if ((ctrl && input.shift && lower === 'r') || (ctrl && key === 'F5')) {
      actions.hardReload?.();
    } else if (input.alt && key === 'ArrowLeft') {
      actions.goBack?.();
    } else if (input.alt && key === 'ArrowRight') {
      actions.goForward?.();
    } else if (input.alt && key === 'Home') {
      actions.goHome?.();
    } else if (ctrl && (key === '=' || key === '+')) {
      zoom(0.5);
    } else if (ctrl && (key === '-' || key === '_')) {
      zoom(-0.5);
    } else if (ctrl && key === '0') {
      zoom(0);
    } else {
      handled = false;
    }

    if (handled) event.preventDefault();
  });
}

module.exports = { installShortcuts };
