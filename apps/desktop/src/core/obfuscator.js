'use strict';
// XOR + base64 obfuscation of source URLs (the build writes them, this decodes). Keeps URLs out of plain strings in
// the packaged files. It is not encryption. Same scheme as the Android build (scripts/android/defaults.mjs).

function xor(data, key) {
  const out = Buffer.alloc(data.length);
  for (let i = 0; i < data.length; i++) out[i] = data[i] ^ key[i % key.length];
  return out;
}

function createObfuscator(keyBase64) {
  const key = Buffer.from(String(keyBase64 ?? ''), 'base64');
  if (key.length === 0) throw new Error('empty obfuscation key');
  return Object.freeze({
    decode: (enc) => xor(Buffer.from(String(enc), 'base64'), key).toString('utf8'),
    encode: (plain) => xor(Buffer.from(String(plain), 'utf8'), key).toString('base64'),
  });
}

module.exports = { createObfuscator };
