import fs from 'node:fs';
import path from 'node:path';

/** JDK used for Gradle. Capacitor 8 / AGP 8.13 / Gradle 8.14 need 17..24 (JBR 25 is too new). */
export function findJavaHome() {
  const candidates = [
    process.env.MOVY_JAVA_HOME,
    'C:\\Program Files\\Java\\jdk-21',
    'C:\\Program Files\\Java\\jdk-17',
    process.env.JAVA_HOME,
  ].filter(Boolean);
  for (const c of candidates) {
    const exe = path.join(c, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac');
    if (fs.existsSync(exe)) return c;
  }
  throw new Error('No JDK 17/21 found. Set MOVY_JAVA_HOME to a JDK between 17 and 24.');
}

export function findAndroidSdk() {
  const candidates = [
    process.env.ANDROID_HOME,
    process.env.ANDROID_SDK_ROOT,
    process.env.LOCALAPPDATA && path.join(process.env.LOCALAPPDATA, 'Android', 'Sdk'),
    process.env.HOME && path.join(process.env.HOME, 'Android', 'Sdk'),
  ].filter(Boolean);
  for (const c of candidates) if (fs.existsSync(path.join(c, 'platforms'))) return c;
  throw new Error('Android SDK not found. Set ANDROID_HOME.');
}

export function adbPath() {
  const exe = process.platform === 'win32' ? 'adb.exe' : 'adb';
  const p = path.join(findAndroidSdk(), 'platform-tools', exe);
  return fs.existsSync(p) ? p : exe;
}
