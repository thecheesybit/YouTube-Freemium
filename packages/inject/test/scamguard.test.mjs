import test from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const { isScamOverlay, isBlankSrc } = createRequire(import.meta.url)('../scamguard.js');
const base = { fixed: true, vw: 960, vh: 540, w: 960, h: 540, z: 2147483646, tag: 'IFRAME', src: '', hasSrcdoc: false, sandbox: '',
  hasVideo: false, hasBlankFrame: false, hasSrcdocSandboxFrame: false, hasQr: false, captchaText: false };
const T = (o) => isScamOverlay({ ...base, ...o });

test('the observed fake-captcha iframe (blank, fixed, full-screen, extreme z-index) is removed', () => {
  assert.equal(T({}), true);
  assert.equal(T({ src: 'about:blank' }), true);
});

test('srcdoc + sandbox allow-modals full-screen overlays are removed', () => {
  assert.equal(T({ src: '', hasSrcdoc: true, sandbox: 'allow-scripts allow-modals', z: 5000 }), true);
});

test('container with a blank frame, a QR canvas or captcha text at an extreme z-index is removed', () => {
  assert.equal(T({ tag: 'DIV', hasBlankFrame: true }), true);
  assert.equal(T({ tag: 'DIV', hasQr: true }), true);
  assert.equal(T({ tag: 'DIV', captchaText: true }), true);
  assert.equal(T({ tag: 'DIV', hasSrcdocSandboxFrame: true, z: 10 }), false); // low z: left alone
  assert.equal(T({ tag: 'DIV', hasSrcdocSandboxFrame: true, z: 5000 }), true);
});

test('real players are never touched', () => {
  assert.equal(T({ src: 'https://player.example/embed/1' }), false);        // has an http src
  assert.equal(T({ hasVideo: true }), false);                               // contains a <video>
  assert.equal(T({ tag: 'DIV', hasQr: true, hasVideo: true }), false);
  assert.equal(T({ z: 100 }), false);                                       // ordinary z-index
  assert.equal(T({ fixed: false }), false);
  assert.equal(T({ w: 400, h: 300 }), false);                               // not full-screen
  assert.equal(T({ tag: 'DIV' }), false);                                   // plain fixed container
  assert.equal(T({ hasSrcdoc: true, sandbox: 'allow-scripts', z: 5000 }), false); // srcdoc without allow-modals
});

test('blank src detection', () => {
  assert.equal(isBlankSrc(''), true);
  assert.equal(isBlankSrc('about:blank'), true);
  assert.equal(isBlankSrc('javascript:void 0'), true);
  assert.equal(isBlankSrc('https://x/y'), false);
});
