/**
 * 行进方块法等值线 —— 自 Kotlin ContourBuilder 1:1 移植（含鞍点 5/10 的对称拆分），
 * 与安卓/旧桌面版生成的等值线完全一致。零依赖。
 */

export interface Segment {
  x1: number;
  y1: number;
  x2: number;
  y2: number;
}

export interface GridField {
  values: ArrayLike<number>;
  resX: number;
  resY: number;
}

function frac(level: number, v0: number, v1: number): number {
  const d = v1 - v0;
  if (d === 0) return 0.5;
  return Math.min(1, Math.max(0, (level - v0) / d));
}

export function contours(field: GridField, level: number): Segment[] {
  const { values, resX, resY } = field;
  const segs: Segment[] = [];
  if (resX < 2 || resY < 2) return segs;

  for (let gy = 0; gy < resY - 1; gy++) {
    for (let gx = 0; gx < resX - 1; gx++) {
      const tl = values[gy * resX + gx];
      const tr = values[gy * resX + gx + 1];
      const br = values[(gy + 1) * resX + gx + 1];
      const bl = values[(gy + 1) * resX + gx];

      let idx = 0;
      if (tl > level) idx |= 8;
      if (tr > level) idx |= 4;
      if (br > level) idx |= 2;
      if (bl > level) idx |= 1;
      if (idx === 0 || idx === 15) continue;

      const aX = gx + frac(level, tl, tr); const aY = gy;
      const bX = gx + 1;                   const bY = gy + frac(level, tr, br);
      const cX = gx + frac(level, bl, br); const cY = gy + 1;
      const dX = gx;                       const dY = gy + frac(level, tl, bl);

      switch (idx) {
        case 1: segs.push({ x1: dX, y1: dY, x2: cX, y2: cY }); break;
        case 2: segs.push({ x1: cX, y1: cY, x2: bX, y2: bY }); break;
        case 3: segs.push({ x1: dX, y1: dY, x2: bX, y2: bY }); break;
        case 4: segs.push({ x1: bX, y1: bY, x2: aX, y2: aY }); break;
        case 5:
          segs.push({ x1: aX, y1: aY, x2: dX, y2: dY });
          segs.push({ x1: cX, y1: cY, x2: bX, y2: bY });
          break;
        case 6: segs.push({ x1: aX, y1: aY, x2: bX, y2: bY }); break;
        case 7: segs.push({ x1: aX, y1: aY, x2: dX, y2: dY }); break;
        case 8: segs.push({ x1: aX, y1: aY, x2: dX, y2: dY }); break;
        case 9: segs.push({ x1: aX, y1: aY, x2: dX, y2: dY }); break;
        case 10:
          segs.push({ x1: aX, y1: aY, x2: bX, y2: bY });
          segs.push({ x1: cX, y1: cY, x2: dX, y2: dY });
          break;
        case 11: segs.push({ x1: aX, y1: aY, x2: bX, y2: bY }); break;
        case 12: segs.push({ x1: aX, y1: aY, x2: bX, y2: bY }); break;
        case 13: segs.push({ x1: bX, y1: bY, x2: cX, y2: cY }); break;
        case 14: segs.push({ x1: cX, y1: cY, x2: dX, y2: dY }); break;
      }
    }
  }
  return segs;
}

/** 每 step dB 一条等值线；量程小的时候至少 minLevels 条（与 Kotlin defaultLevels 一致）。 */
export function defaultLevels(min: number, max: number, step = 5.0, minLevels = 4): number[] {
  if (max - min < 1e-6) return [];
  const s = (max - min) / step < minLevels ? (max - min) / minLevels : step;
  const start = Math.floor(min / s) * s;
  const levels: number[] = [];
  for (let l = start; l <= max + 1e-6; l += s) {
    if (l >= min - 1e-6) levels.push(l);
  }
  return levels;
}
