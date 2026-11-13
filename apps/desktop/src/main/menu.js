'use strict';
const { Menu, app } = require('electron');

function installMenu(win, actions) {
  const hint = (accelerator) => ({ accelerator, registerAccelerator: false });
  const template = [
    {
      label: 'File',
      submenu: [
        { label: 'Reload', ...hint('Ctrl+R'), click: () => actions.reload?.() },
        { label: 'Hard Reload', ...hint('Ctrl+Shift+R'), click: () => actions.hardReload?.() },
        { type: 'separator' },
        { label: 'Quit', ...hint('Ctrl+Q'), click: () => actions.quit?.() }
      ],
    },
    {
      label: 'Edit',
      submenu: [
        { role: 'undo' },
        { role: 'redo' },
        { type: 'separator' },
        { role: 'cut' },
        { role: 'copy' },
        { role: 'paste' },
        { role: 'selectAll' }
      ]
    },
    {
      label: 'View',
      submenu: [
        { label: 'YouTube', ...hint('Ctrl+1'), click: () => actions.switchSource?.('youtube') },
        { label: 'YouTube Music', ...hint('Ctrl+2'), click: () => actions.switchSource?.('youtube-music') },
        { label: 'Toggle Source', ...hint('Ctrl+Tab'), click: () => actions.toggleSource?.() },
        { type: 'separator' },
        { label: 'Picture-in-Picture', ...hint('Ctrl+Shift+P'), click: () => actions.togglePip?.() },
        { label: 'Settings', ...hint('Ctrl+,'), click: () => actions.openSettings?.() },
        { type: 'separator' },
        { label: 'Full screen', ...hint('F11'), click: () => win.setFullScreen(!win.isFullScreen()) }
      ],
    },
    {
      label: 'Help',
      submenu: [
        { label: 'About YouTube Freemium', click: () => actions.openAbout?.() },
        { label: 'Check for updates...', click: () => actions.openAbout?.() }
      ]
    }
  ];
  win.setMenu(Menu.buildFromTemplate(template));
}

module.exports = { installMenu };
