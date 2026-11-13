// electron-builder configuration derived from the resolved app config. Pure, unit tested.
import path from 'node:path';

export function electronBuilderConfig(cfg, { outDir, gen }) {
  const icoPath = path.join(gen, 'icon.ico');
  const pngPath = path.join(gen, 'icon.png');

  return {
    appId: cfg.appId,
    productName: cfg.appName,
    copyright: 'Crafted by Ayush Kumar',
    extraMetadata: {
      name: cfg.name,
      version: cfg.version,
      main: 'src/main/main.js'
    },
    directories: { output: outDir },
    files: [
      'package.json',
      'src/**/*',
      '!**/node_modules/**',
      '!**/*.test.*'
    ],
    extraResources: [
      { from: gen, to: 'ytfreemium' },
      { from: gen, to: 'movy' }
    ],
    asar: true,
    npmRebuild: false,
    nodeGypRebuild: false,
    win: {
      icon: icoPath,
      target: [
        { target: 'nsis', arch: ['x64'] },
        { target: 'portable', arch: ['x64'] }
      ]
    },
    nsis: {
      oneClick: false,
      perMachine: false,
      allowToChangeInstallationDirectory: true,
      deleteAppDataOnUninstall: false,
      shortcutName: cfg.appName,
      artifactName: 'YouTube-Freemium-Setup-${version}.${ext}'
    },
    portable: {
      artifactName: 'YouTube-Freemium-${version}-portable.${ext}'
    },
    mac: {
      icon: pngPath,
      target: ['dmg']
    },
    linux: {
      icon: pngPath,
      target: ['AppImage']
    }
  };
}
