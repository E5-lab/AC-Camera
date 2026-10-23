/**
 * 生成 NoiseAtlas 应用图标（零依赖）。
 * 设计：深蓝圆角方块 + 白色等压级同心环 + 网格线 + 红色噪声源点，呼应产品功能。
 * 输出：icon.png（256）、icon.ico（256/48/32/16，PNG 内嵌格式）、icon-512.png。
 */
import { deflateSync } from 'node:zlib';
import { writeFileSync, mkdirSync, existsSync, rmSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const OUT = resolve(__dirname, '../src-tauri/icons');
mkdirSync(OUT, { recursive: true });

const SS = 4;              // 超采样倍数
const SIZE = 256;

// ───────── 绘制 ─────────

function renderRGBA(size) {
  const n = size * size * 4;
  const px = new Float32Array(n);
  const S = size;
  const r = S * 0.22;                 // 圆角半径
  const cx = S * 0.46, cy = S * 0.52; // 环心

  const inRounded = (x, y) => {
    const m = S * 0.06;
    const x0 = m, y0 = m, x1 = S - m, y1 = S - m;
    const rr = r;
    if (x < x0 || x > x1 || y < y0 || y > y1) return false;
    const inCornerX = x < x0 + rr || x > x1 - rr;
    const inCornerY = y < y0 + rr || y > y1 - rr;
    if (!inCornerX || !inCornerY) return true;
    const px = Math.min(Math.max(x, x0 + rr), x1 - rr);
    const py = Math.min(Math.max(y, y0 + rr), y1 - rr);
    return Math.hypot(x - px, y - py) <= rr;
  };

  const blend = (i, r0, g0, b0, a0) => {
    const a = Math.min(1, Math.max(0, a0));
    const ia = 1 - a;
    px[i] = px[i] * ia + r0 * a;
    px[i + 1] = px[i + 1] * ia + g0 * a;
    px[i + 2] = px[i + 2] * ia + b0 * a;
    px[i + 3] = Math.min(1, px[i + 3] + a);
  };

  for (let y = 0; y < S; y++) {
    for (let x = 0; x < S; x++) {
      const i = (y * S + x) * 4;
      if (!inRounded(x + 0.5, y + 0.5)) continue;

      // 背景：对角线渐变 深蓝 -> 亮蓝
      const t = (x / S) * 0.55 + (y / S) * 0.45;
      const bg = [
        Math.round(16 + 22 * t),
        Math.round(52 + 68 * t),
        Math.round(96 + 76 * t),
      ];
      px[i] = bg[0] / 255; px[i + 1] = bg[1] / 255; px[i + 2] = bg[2] / 255; px[i + 3] = 1;

      // 网格线（细，低透明度）
      const gl = S * 0.5;               // 网格间距
      const ox = S * 0.22;
      const nearV = Math.abs(((x - ox) % gl + gl) % gl) < 1.2 || Math.abs(((x - ox) % gl + gl) % gl - gl) < 1.2;
      const nearH = Math.abs(((y - ox) % gl + gl) % gl) < 1.2 || Math.abs(((y - ox) % gl + gl) % gl - gl) < 1.2;
      if (nearV || nearH) blend(i, 1, 1, 1, 0.14);

      // 等压级同心环
      const d = Math.hypot(x - cx, y - cy) / S;
      for (const [rad, wdt, alpha] of [[0.15, 0.028, 0.95], [0.26, 0.024, 0.8], [0.37, 0.020, 0.6]]) {
        if (Math.abs(d - rad) <= wdt) blend(i, 1, 1, 1, alpha);
      }

      // 中心焦点
      if (d <= 0.045) blend(i, 1, 1, 1, 0.95);

      // 噪声源红点（右上象限）
      const sx = S * 0.70, sy = S * 0.30;
      const ds = Math.hypot(x - sx, y - sy) / S;
      if (ds <= 0.075) {
        // 外圈白描边
        if (ds > 0.058) blend(i, 1, 1, 1, 0.9);
        else blend(i, 0.88, 0.16, 0.13, 1);
      }
    }
  }
  return px;
}

function downsample(src, srcSize, dstSize) {
  const out = new Float32Array(dstSize * dstSize * 4);
  const f = srcSize / dstSize;
  for (let y = 0; y < dstSize; y++) {
    for (let x = 0; x < dstSize; x++) {
      let r = 0, g = 0, b = 0, a = 0, c = 0;
      const y0 = Math.floor(y * f), y1 = Math.floor((y + 1) * f);
      const x0 = Math.floor(x * f), x1 = Math.floor((x + 1) * f);
      for (let yy = y0; yy < y1; yy++) {
        for (let xx = x0; xx < x1; xx++) {
          const i = (yy * srcSize + xx) * 4;
          r += src[i]; g += src[i + 1]; b += src[i + 2]; a += src[i + 3]; c++;
        }
      }
      const i = (y * dstSize + x) * 4;
      out[i] = r / c; out[i + 1] = g / c; out[i + 2] = b / c; out[i + 3] = a / c;
    }
  }
  return out;
}

// ───────── PNG 编码 ─────────

const CRC_TABLE = (() => {
  const t = new Uint32Array(256);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  return t;
})();

function crc32(buf) {
  let c = 0xffffffff;
  for (const b of buf) c = CRC_TABLE[(c ^ b) & 0xff] ^ (c >>> 8);
  return (c ^ 0xffffffff) >>> 0;
}

function chunk(type, data) {
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const typeBuf = Buffer.from(type, 'ascii');
  const crcBuf = Buffer.alloc(4);
  crcBuf.writeUInt32BE(crc32(Buffer.concat([typeBuf, data])), 0);
  return Buffer.concat([len, typeBuf, data, crcBuf]);
}

function encodePng(rgba, size) {
  const raw = Buffer.alloc(size * (size * 4 + 1));
  let o = 0;
  for (let y = 0; y < size; y++) {
    raw[o++] = 0; // filter none
    for (let x = 0; x < size; x++) {
      const i = (y * size + x) * 4;
      raw[o++] = Math.round(Math.min(1, rgba[i]) * 255);
      raw[o++] = Math.round(Math.min(1, rgba[i + 1]) * 255);
      raw[o++] = Math.round(Math.min(1, rgba[i + 2]) * 255);
      raw[o++] = Math.round(Math.min(1, rgba[i + 3]) * 255);
    }
  }
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(size, 0);
  ihdr.writeUInt32BE(size, 4);
  ihdr[8] = 8;   // bit depth
  ihdr[9] = 6;   // RGBA
  ihdr[10] = 0; ihdr[11] = 0; ihdr[12] = 0;
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr),
    chunk('IDAT', deflateSync(raw, { level: 9 })),
    chunk('IEND', Buffer.alloc(0)),
  ]);
}

// ───────── ICO 打包（PNG 内嵌，Vista+ 支持）─────────

function encodeIco(images) {
  const header = Buffer.alloc(6);
  header.writeUInt16LE(0, 0);
  header.writeUInt16LE(1, 2);          // type icon
  header.writeUInt16LE(images.length, 4);

  const entries = [];
  let offset = 6 + images.length * 16;
  for (const img of images) {
    const e = Buffer.alloc(16);
    e[0] = img.size >= 256 ? 0 : img.size;
    e[1] = img.size >= 256 ? 0 : img.size;
    e[2] = 0; e[3] = 0;
    e.writeUInt16LE(1, 4);   // planes
    e.writeUInt16LE(32, 6);  // bpp
    e.writeUInt32LE(img.png.length, 8);
    e.writeUInt32LE(offset, 12);
    offset += img.png.length;
    entries.push(e);
  }
  return Buffer.concat([header, ...entries, ...images.map((i) => i.png)]);
}

// ───────── main ─────────

const big = renderRGBA(SIZE * SS);
const base = downsample(big, SIZE * SS, SIZE);

writeFileSync(resolve(OUT, 'icon.png'), encodePng(base, 256));
writeFileSync(resolve(OUT, '128x128.png'), encodePng(downsample(base, 256, 128), 128));
writeFileSync(resolve(OUT, '32x32.png'), encodePng(downsample(base, 256, 32), 32));

const icoImages = [256, 48, 32, 16].map((s) => ({
  size: s,
  png: s === 256 ? encodePng(base, 256) : encodePng(downsample(base, 256, s), s),
}));
writeFileSync(resolve(OUT, 'icon.ico'), encodeIco(icoImages));

// ── 安卓 mipmap（覆盖 Capacitor 默认图标；并移除自适应图标 XML 以免覆盖 PNG）──
const ANDROID_RES = resolve(__dirname, '../android/app/src/main/res');
const MIPMAPS = [
  ['mipmap-mdpi', 48],
  ['mipmap-hdpi', 72],
  ['mipmap-xhdpi', 96],
  ['mipmap-xxhdpi', 144],
  ['mipmap-xxxhdpi', 192],
];
if (existsSync(ANDROID_RES)) {
  for (const [dir, size] of MIPMAPS) {
    const target = resolve(ANDROID_RES, dir);
    mkdirSync(target, { recursive: true });
    const png = encodePng(downsample(base, 256, size), size);
    writeFileSync(resolve(target, 'ic_launcher.png'), png);
    writeFileSync(resolve(target, 'ic_launcher_round.png'), png);
    writeFileSync(resolve(target, 'ic_launcher_foreground.png'), png);
  }
  // 自适应图标 XML 会优先于 PNG，删掉以便使用我们生成的图标
  const anydpi = resolve(ANDROID_RES, 'mipmap-anydpi-v26');
  if (existsSync(anydpi)) {
    rmSync(anydpi, { recursive: true, force: true });
  }
  console.log('android mipmaps written');
}

console.log('icons written to', OUT);
