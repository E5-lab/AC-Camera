/**
 * 声场与色彩算法 —— 从 Kotlin shared 模块 1:1 移植，行为保持一致：
 *  - 能量叠加 + 距离衰减模型（低能量区稳定落在底噪，不伪辐射）
 *  - 蓝→青→绿→黄→红 colormap
 *  - 色带量化（带中心色 + 调色板步数下限 64，保证 64/128/256 可见差异）
 */

export interface PointValue {
  x: number;
  y: number;
  value: number;
}

export interface FieldResult {
  values: Float64Array;
  resX: number;
  resY: number;
  worldW: number;
  worldH: number;
  min: number;
  max: number;
}

const RES_X = 140;

export function computeResY(worldW: number, worldH: number, resX = RES_X): number {
  if (worldH <= 0) return resX;
  return Math.max(2, Math.round(resX * worldH / worldW));
}

/**
 * E(p) = 10·log10( Σ 10^((v_i − α·d) / 10) )，α = attenuationPerMeter（默认 6dB/网格间距）。
 * points 的 value 应为「实测值 − 底噪」（≥0）；调用方再把底噪加回去。
 */
export function energyField(
  points: PointValue[],
  worldW: number,
  worldH: number,
  attenuationPerMeter = 0.6,
  resX = RES_X,
): FieldResult {
  const resY = computeResY(worldW, worldH, resX);
  const values = new Float64Array(resX * resY);
  const result: FieldResult = { values, resX, resY, worldW, worldH, min: 0, max: 0 };
  if (points.length === 0 || worldW <= 0 || worldH <= 0) return result;

  const amp = points.map((p) => Math.pow(10, p.value / 10));
  const k = attenuationPerMeter * 2.302585092994046 / 10; // α·ln(10)/10
  let min = Infinity;
  let max = -Infinity;

  for (let gy = 0; gy < resY; gy++) {
    const y = (gy + 0.5) / resY * worldH;
    for (let gx = 0; gx < resX; gx++) {
      const x = (gx + 0.5) / resX * worldW;
      let energy = 0;
      for (let i = 0; i < points.length; i++) {
        const dx = x - points[i].x;
        const dy = y - points[i].y;
        const d = Math.sqrt(dx * dx + dy * dy);
        energy += amp[i] * Math.exp(-k * d);
      }
      const e = energy > 0 ? 10 * Math.log10(energy) : 0;
      values[gy * resX + gx] = e;
      if (e < min) min = e;
      if (e > max) max = e;
    }
  }
  result.min = min;
  result.max = max;
  return result;
}

/**
 * 由测点 + 项目参数算出「带底噪的声压级场」。
 *
 * 下限（色标最低值）取法：
 *  - 用户填了底噪 bg > 0 → 全场下限 = bg（物理上不可能低于本底噪声）；
 *  - 未填底噪（bg = 0） → 不再硬钳到 0 dB，而是按测点声能自然衰减的包络取
 *    「最安静测点 − DECAY_SPAN dB」，这样色标刻度反映的是真实的衰减范围，
 *    而不是一个没有物理意义的 0 dB。
 */
export const DECAY_SPAN = 18;

export function computeSplField(
  project: { cols: number; rows: number; spacingX: number; spacingY: number; backgroundNoiseDb: number },
  splByPoint: Map<string, number>,
): FieldResult | null {
  if (splByPoint.size === 0) return null;
  const bg = project.backgroundNoiseDb;
  const worldW = project.cols * project.spacingX;
  const worldH = project.rows * project.spacingY;
  const spacing = (project.spacingX + project.spacingY) / 2;
  const atten = spacing > 0 ? 6.0 / spacing : 0.6;
  const pts: PointValue[] = [];
  let minMeasured = Infinity;
  for (const [key, spl] of splByPoint) {
    const [c, r] = key.split(',').map(Number);
    if (spl < minMeasured) minMeasured = spl;
    pts.push({ x: c * project.spacingX, y: r * project.spacingY, value: Math.max(0, spl - bg) });
  }
  const excess = energyField(pts, worldW, worldH, atten);

  // 物理下限：有底噪就落在底噪；没有底噪则跟随自然衰减包络（而不是 0 dB）
  const floor = bg > 0 ? bg : Math.max(0, minMeasured - DECAY_SPAN);

  let smin = Infinity;
  let smax = -Infinity;
  for (let i = 0; i < excess.values.length; i++) {
    const v = Math.max(excess.values[i] + bg, floor);
    excess.values[i] = v;
    if (v < smin) smin = v;
    if (v > smax) smax = v;
  }
  excess.min = smin;
  excess.max = smax;
  return excess;
}

// ───────────────────────── colormap ─────────────────────────

const STOPS: ReadonlyArray<readonly [number, number, number]> = [
  [49, 54, 149],
  [69, 170, 200],
  [60, 180, 75],
  [245, 205, 50],
  [220, 50, 47],
];

export const MIN_PALETTE_STEPS = 64;
export const MAX_PALETTE_STEPS = 256;

export function colormap(t: number): [number, number, number] {
  const x = Math.min(1, Math.max(0, t));
  const n = STOPS.length - 1;
  const pos = x * n;
  const i = Math.min(n - 1, Math.max(0, Math.floor(pos)));
  const f = pos - i;
  const c0 = STOPS[i];
  const c1 = STOPS[i + 1];
  return [
    Math.min(255, Math.max(0, Math.round(c0[0] + (c1[0] - c0[0]) * f))),
    Math.min(255, Math.max(0, Math.round(c0[1] + (c1[1] - c0[1]) * f))),
    Math.min(255, Math.max(0, Math.round(c0[2] + (c1[2] - c0[2]) * f))),
  ];
}

/** 色带中心色，再量化到调色板步数（与 Kotlin bandColor 一致）。 */
export function bandColor(band: number, bands: number, steps: number): [number, number, number] {
  if (bands < 2) return colormap(0);
  const b = Math.min(bands - 1, Math.max(0, band));
  let t = (b + 0.5) / bands;
  if (steps >= 2) {
    const st = Math.min(MAX_PALETTE_STEPS, Math.max(MIN_PALETTE_STEPS, steps));
    t = Math.round(t * st) / st;
  }
  return colormap(t);
}

export function bandColors(bands: number, steps = bands): [number, number, number][] {
  const n = bands >= 2 ? bands : 1;
  const out: [number, number, number][] = [];
  for (let i = 0; i < n; i++) out.push(bandColor(i, bands, steps));
  return out;
}

export function quantizeStep(bands: number): number {
  return Math.min(MAX_PALETTE_STEPS, Math.max(MIN_PALETTE_STEPS, bands));
}

/** 渲染色场为 ImageData（供 putImageData），含色带量化；minValue/maxValue 为场极值。 */
export function renderFieldImageData(
  field: FieldResult,
  colorBands: number,
  alpha: number,
): ImageData {
  const { values, resX, resY, min, max } = field;
  const img = new ImageData(resX, resY);
  const range = max - min > 1e-6 ? max - min : 1.0;
  const bands = colorBands >= 2 ? colorBands : 0;
  const steps = bands > 0 ? quantizeStep(bands) : 0;
  const a = Math.round(alpha * 255);
  for (let i = 0; i < values.length; i++) {
    const t = Math.min(1, Math.max(0, (values[i] - min) / range));
    let rgb: [number, number, number];
    if (bands === 0) {
      rgb = colormap(t);
    } else {
      const band = Math.min(bands - 1, Math.max(0, Math.floor(t * bands)));
      rgb = bandColor(band, bands, steps);
    }
    img.data[i * 4] = rgb[0];
    img.data[i * 4 + 1] = rgb[1];
    img.data[i * 4 + 2] = rgb[2];
    img.data[i * 4 + 3] = a;
  }
  return img;
}
