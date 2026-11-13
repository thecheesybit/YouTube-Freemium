'use strict';
const path = require('node:path');
const { app } = require('electron');

/** Downloads always go through the native save dialog (default folder: Downloads); progress shows on the taskbar. */
function installDownloads(ses, win, log) {
  ses.on('will-download', (_e, item) => {
    item.setSaveDialogOptions({ defaultPath: path.join(app.getPath('downloads'), item.getFilename()) });
    item.on('updated', () => {
      const total = item.getTotalBytes();
      if (!win.isDestroyed() && total > 0) win.setProgressBar(item.getReceivedBytes() / total);
    });
    item.once('done', (_ev, state) => {
      if (!win.isDestroyed()) win.setProgressBar(-1);
      log(`[download] ${state}: ${item.getFilename()}`);
    });
  });
}

module.exports = { installDownloads };
