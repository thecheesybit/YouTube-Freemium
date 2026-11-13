// Generates launcher icons, adaptive icon, splash colors and the TV banner.
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createCanvas, loadImage } from '@napi-rs/canvas';

const DENSITIES = { mdpi: 1, hdpi: 1.5, xhdpi: 2, xxhdpi: 3, xxxhdpi: 4 };
const FONT = 'Segoe UI, Arial, Helvetica, sans-serif';

const hex = (r, g, b) => '#' + [r, g, b].map((v) => v.toString(16).padStart(2, '0')).join('');

/** Deterministic pleasant color from the app name. */
export function defaultColor(name) {
  const h = crypto.createHash('sha1').update(name).digest()[0] / 255;
  const f = (n) => {
    const k = (n + h * 12) % 12;
    const a = 0.55 * Math.min(0.45, 1 - 0.45);
    return Math.round(255 * (0.45 - a * Math.max(-1, Math.min(k - 3, 9 - k, 1))));
  };
  return hex(f(0), f(8), f(4));
}

function write(file, canvas) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, canvas.toBuffer('image/png'));
}

function drawLetter(ctx, letter, size, color = '#ffffff') {
  ctx.fillStyle = color;
  ctx.font = `700 ${Math.round(size * 0.5)}px ${FONT}`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(letter, size / 2, size / 2 + size * 0.03);
}

/** Pixel (0,0) of an image as #RRGGBB when fully opaque, else null. */
function cornerColor(img) {
  const c = createCanvas(1, 1);
  const ctx = c.getContext('2d');
  ctx.drawImage(img, 0, 0, 1, 1, 0, 0, 1, 1);
  const [r, g, b, a] = ctx.getImageData(0, 0, 1, 1).data;
  return a === 255 ? hex(r, g, b) : null;
}

/** Most common light tone of the image (4 bits per channel buckets, luma > 140), as #RRGGBB; null when none. */
export function dominantLight(img) {
  const N = 64;
  const c = createCanvas(N, N);
  const ctx = c.getContext('2d');
  ctx.drawImage(img, 0, 0, N, N);
  const px = ctx.getImageData(0, 0, N, N).data;
  const buckets = new Map();
  for (let i = 0; i < px.length; i += 4) {
    const [r, g, b, a] = [px[i], px[i + 1], px[i + 2], px[i + 3]];
    if (a < 250 || 0.299 * r + 0.587 * g + 0.114 * b < 140) continue;
    const key = (r >> 4) << 8 | (g >> 4) << 4 | (b >> 4);
    const e = buckets.get(key) ?? { n: 0, r: 0, g: 0, b: 0 };
    buckets.set(key, { n: e.n + 1, r: e.r + r, g: e.g + g, b: e.b + b });
  }
  let best = null;
  for (const e of buckets.values()) if (!best || e.n > best.n) best = e;
  return best ? hex(Math.round(best.r / best.n), Math.round(best.g / best.n), Math.round(best.b / best.n)) : null;
}

/** Draws the icon (or the letter) as a rounded tile of `size` px; used by the splash and the TV banner. */
function tile(icon, letter, size, bg) {
  const c = createCanvas(size, size);
  const ctx = c.getContext('2d');
  ctx.beginPath();
  ctx.roundRect(0, 0, size, size, size * 0.22);
  ctx.clip();
  ctx.fillStyle = bg;
  ctx.fillRect(0, 0, size, size);
  if (icon) ctx.drawImage(icon, 0, 0, size, size);
  else drawLetter(ctx, letter, size);
  return c;
}

/**
 * @param {{resDir:string, tvResDir:string, name:string, appName:string, backgroundColor:string, iconPath?:string|null, bannerPath?:string|null}} o
 */
export async function generateIcons(o) {
  const letter = ([...o.appName.trim()][0] ?? 'Y').toUpperCase();
  const icon = o.iconPath ? await loadImage(o.iconPath) : null;
  const bg = o.name.includes('youtube')
    ? '#E60000'
    : (icon && (dominantLight(icon) || cornerColor(icon))) || (icon ? o.backgroundColor : defaultColor(o.name));

  // Shared colors. movy_background is also used for window background / splash.
  const colorsXml = `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="movy_background">${o.backgroundColor}</color>\n</resources>\n`;
  fs.mkdirSync(path.join(o.resDir, 'values'), { recursive: true });
  fs.writeFileSync(path.join(o.resDir, 'values', 'colors.xml'), colorsXml);
  fs.writeFileSync(
    path.join(o.resDir, 'values', 'ic_launcher_background.xml'),
    `<?xml version="1.0" encoding="utf-8"?>\n<resources>\n    <color name="ic_launcher_background">${bg}</color>\n</resources>\n`,
  );

  const adaptive = `<?xml version="1.0" encoding="utf-8"?>\n<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n    <background android:drawable="@color/ic_launcher_background"/>\n    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>\n    <monochrome android:drawable="@mipmap/ic_launcher_monochrome"/>\n</adaptive-icon>\n`;
  for (const f of ['ic_launcher.xml', 'ic_launcher_round.xml']) {
    fs.mkdirSync(path.join(o.resDir, 'mipmap-anydpi-v26'), { recursive: true });
    fs.writeFileSync(path.join(o.resDir, 'mipmap-anydpi-v26', f), adaptive);
  }

  for (const [dpi, scale] of Object.entries(DENSITIES)) {
    const dir = path.join(o.resDir, `mipmap-${dpi}`);
    const legacy = Math.round(48 * scale);
    const fg = Math.round(108 * scale);

    // Legacy square icon (rounded) + round icon.
    for (const [file, round] of [['ic_launcher.png', false], ['ic_launcher_round.png', true]]) {
      const c = createCanvas(legacy, legacy);
      const ctx = c.getContext('2d');
      ctx.beginPath();
      if (round) ctx.arc(legacy / 2, legacy / 2, legacy / 2, 0, Math.PI * 2);
      else ctx.roundRect(0, 0, legacy, legacy, legacy * 0.2);
      ctx.clip();
      ctx.fillStyle = bg;
      ctx.fillRect(0, 0, legacy, legacy);
      if (icon) ctx.drawImage(icon, 0, 0, legacy, legacy);
      else drawLetter(ctx, letter, legacy);
      write(path.join(dir, file), c);
    }

    // Adaptive foreground: content inside the central 66% safe zone.
    const c = createCanvas(fg, fg);
    const ctx = c.getContext('2d');
    if (icon) {
      const inner = fg * (66 / 108);
      ctx.drawImage(icon, (fg - inner) / 2, (fg - inner) / 2, inner, inner);
    } else {
      drawLetter(ctx, letter, fg);
    }
    write(path.join(dir, 'ic_launcher_foreground.png'), c);

    // Monochrome icon for Android 13 themed icons:
    // Extract silhouette / white play button as flat white
    const mc = createCanvas(fg, fg);
    const mctx = mc.getContext('2d');
    if (icon) {
      const inner = fg * (66 / 108);
      mctx.drawImage(icon, (fg - inner) / 2, (fg - inner) / 2, inner, inner);
      const imgData = mctx.getImageData(0, 0, fg, fg);
      const data = imgData.data;
      for (let i = 0; i < data.length; i += 4) {
        const a = data[i + 3];
        if (a > 30) {
          // If pixel is light (play button) keep it solid white; if dark/red make it semi or border
          const luma = 0.299 * data[i] + 0.587 * data[i + 1] + 0.114 * data[i + 2];
          if (luma > 160) {
            data[i] = 255;
            data[i + 1] = 255;
            data[i + 2] = 255;
            data[i + 3] = 255;
          } else {
            // Background container outline
            data[i] = 255;
            data[i + 1] = 255;
            data[i + 2] = 255;
            data[i + 3] = 120;
          }
        }
      }
      mctx.putImageData(imgData, 0, 0);
    } else {
      drawLetter(mctx, letter, fg, '#ffffff');
    }
    write(path.join(dir, 'ic_launcher_monochrome.png'), mc);
  }

  // Splash / About drawable (referenced by the native splash and the system launch splash).
  write(path.join(o.resDir, 'drawable-nodpi', 'splash_icon.png'), tile(icon, letter, 512, bg));

  await generateBanner(o, bg, icon, letter);
}

async function generateBanner(o, bg, icon, letter) {
  const W = 320;
  const H = 180;
  const c = createCanvas(W, H);
  const ctx = c.getContext('2d');
  if (o.bannerPath) {
    ctx.drawImage(await loadImage(o.bannerPath), 0, 0, W, H);
  } else {
    // Icon + app name on dark.
    ctx.fillStyle = '#0B0B0C';
    ctx.fillRect(0, 0, W, H);
    const t = 88;
    ctx.drawImage(tile(icon, letter, t * 2, bg), 28, (H - t) / 2, t, t);
    ctx.fillStyle = '#ffffff';
    ctx.textAlign = 'left';
    ctx.textBaseline = 'middle';
    let size = 36;
    const maxW = W - 28 - t - 20 - 16;
    do {
      ctx.font = `700 ${size}px ${FONT}`;
      size -= 2;
    } while (ctx.measureText(o.appName).width > maxW && size > 12);
    ctx.fillText(o.appName, 28 + t + 20, H / 2);
  }
  if (o.tvResDir) {
    write(path.join(o.tvResDir, 'drawable-xhdpi', 'tv_banner.png'), c);
  }
}
