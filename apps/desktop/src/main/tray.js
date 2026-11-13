'use strict';
const path = require('node:path');
const { Tray, Menu, nativeImage, app } = require('electron');

class TrayManager {
  /** @param {{iconPath:string, win:Electron.BrowserWindow, views:import('./views').Views, media:import('./media').MediaManager, onQuit:Function}} o */
  constructor({ iconPath, win, views, media, onSwitch, onQuit }) {
    this.win = win;
    this.views = views;
    this.media = media;
    this.onQuit = onQuit;
    this.onSwitch = onSwitch || ((id) => views.switchTo(id));
    this.tray = null;

    this.init(iconPath);
  }

  init(iconPath) {
    let img = nativeImage.createFromPath(iconPath);
    if (img.isEmpty()) {
      img = nativeImage.createEmpty();
    }
    this.tray = new Tray(img.resize({ width: 16, height: 16 }));
    this.tray.setToolTip('YouTube Freemium');
    this.tray.on('click', () => this.toggleWindow());
    this.update();
  }

  toggleWindow() {
    if (this.win.isVisible() && !this.win.isMinimized() && this.win.isFocused()) {
      this.win.hide();
    } else {
      if (this.win.isMinimized()) this.win.restore();
      this.win.show();
      this.win.focus();
    }
  }

  update() {
    if (!this.tray || !this.media) return;
    const mediaState = this.media.getActiveState();
    const isPlaying = mediaState.playing;
    const isVisible = this.win.isVisible() && !this.win.isMinimized();

    const template = [
      {
        label: isVisible ? 'Hide' : 'Show',
        click: () => this.toggleWindow()
      },
      { type: 'separator' },
      {
        label: isPlaying ? 'Pause' : 'Play',
        click: () => this.media.togglePlayPause()
      },
      {
        label: 'Next',
        click: () => this.media.next()
      },
      {
        label: 'Previous',
        click: () => this.media.previous()
      },
      { type: 'separator' },
      {
        label: 'YouTube',
        type: 'radio',
        checked: this.views.activeId === 'youtube',
        click: () => this.onSwitch('youtube')
      },
      {
        label: 'YouTube Music',
        type: 'radio',
        checked: this.views.activeId === 'youtube-music',
        click: () => this.onSwitch('youtube-music')
      },
      { type: 'separator' },
      {
        label: 'Quit',
        click: () => this.onQuit()
      }
    ];

    const contextMenu = Menu.buildFromTemplate(template);
    this.tray.setContextMenu(contextMenu);
  }

  destroy() {
    if (this.tray) {
      this.tray.destroy();
      this.tray = null;
    }
  }
}

module.exports = { TrayManager };
