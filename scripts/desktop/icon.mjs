// Desktop icon generator: produces icon.png and icon.ico (multi-res: 16, 24, 32, 48, 64, 128, 256)
import fs from 'node:fs';
import path from 'node:path';
import { createCanvas, loadImage } from '@napi-rs/canvas';

const ICO_SIZES = [16, 24, 32, 48, 64, 128, 256];

/** Encode an array of PNG buffers into a standard Windows .ico file */
export function encodeIco(pngBuffers, sizes) {
  const count = pngBuffers.length;
  const headerSize = 6;
  const dirEntrySize = 16;
  let offset = headerSize + count * dirEntrySize;

  const header = Buffer.alloc(headerSize);
  header.writeUInt16LE(0, 0); // reserved
  header.writeUInt16LE(1, 2); // 1 = ICO
  header.writeUInt16LE(count, 4);

  const dirEntries = [];
  for (let i = 0; i < count; i++) {
    const size = sizes[i];
    const buf = pngBuffers[i];
    const entry = Buffer.alloc(dirEntrySize);
    entry.writeUInt8(size >= 256 ? 0 : size, 0); // width (0 = 256)
    entry.writeUInt8(size >= 256 ? 0 : size, 1); // height
    entry.writeUInt8(0, 2); // palette
    entry.writeUInt8(0, 3); // reserved
    entry.writeUInt16LE(1, 4); // planes
    entry.writeUInt16LE(32, 6); // bpp
    entry.writeUInt32LE(buf.length, 8); // size
    entry.writeUInt32LE(offset, 12); // offset
    dirEntries.push(entry);
    offset += buf.length;
  }

  return Buffer.concat([header, ...dirEntries, ...pngBuffers]);
}

export async function writeDesktopIcons(cfg, rootDir, genDir) {
  const custom = path.join(rootDir, 'config', 'assets', cfg.name, 'icon.png');
  const sourceImage = fs.existsSync(custom)
    ? await loadImage(custom)
    : null;

  const png512 = createCanvas(512, 512);
  const ctx512 = png512.getContext('2d');
  if (sourceImage) {
    ctx512.drawImage(sourceImage, 0, 0, 512, 512);
  } else {
    ctx512.fillStyle = '#E60000';
    ctx512.beginPath();
    ctx512.roundRect(0, 0, 512, 512, 512 * 0.2);
    ctx512.fill();
    ctx512.fillStyle = '#FFFFFF';
    ctx512.beginPath();
    ctx512.moveTo(200, 150);
    ctx512.lineTo(360, 256);
    ctx512.lineTo(200, 362);
    ctx512.closePath();
    ctx512.fill();
  }
  const mainPng = png512.toBuffer('image/png');
  fs.writeFileSync(path.join(genDir, 'icon.png'), mainPng);

  // Generate multi-size pngs for .ico
  const pngBuffers = [];
  for (const s of ICO_SIZES) {
    const c = createCanvas(s, s);
    const ctx = c.getContext('2d');
    if (sourceImage) {
      ctx.drawImage(sourceImage, 0, 0, s, s);
    } else {
      ctx.drawImage(png512, 0, 0, s, s);
    }
    pngBuffers.push(c.toBuffer('image/png'));
  }

  const icoBuf = encodeIco(pngBuffers, ICO_SIZES);
  fs.writeFileSync(path.join(genDir, 'icon.ico'), icoBuf);
  return { png: path.join(genDir, 'icon.png'), ico: path.join(genDir, 'icon.ico') };
}
