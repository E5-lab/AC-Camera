/**
 * 极简 DXF 解析器 —— 自 Kotlin CadReader 移植核心几何：
 * LINE / LWPOLYLINE / POLYLINE+VERTEX / CIRCLE / ARC / ELLIPSE / POINT / SPLINE / INSERT(递归)。
 * 输出归一化到 0..1、Y 翻转（CAD 上正 → 屏幕下正）、保持纵横比。
 */

export interface CadPolyline {
  xs: number[];
  ys: number[];
  closed: boolean;
}

export interface CadDrawing {
  polylines: CadPolyline[];
  aspectRatio: number;
}

const MAX_VERTICES = 400_000;
const MAX_DEPTH = 6;
const CIRCLE_SEGMENTS = 64;
const DEG = Math.PI / 180.0;

interface Tag { code: number; value: string }

interface Entity {
  type: string;
  tags: Tag[];
  verts: Entity[];
}

interface Block { name: string; baseX: number; baseY: number; entities: Entity[] }

interface Xform { a: number; b: number; c: number; d: number; e: number; f: number }

const ID: Xform = { a: 1, b: 0, c: 0, d: 1, e: 0, f: 0 };
const mapX = (xf: Xform, x: number, y: number) => xf.a * x + xf.b * y + xf.e;
const mapY = (xf: Xform, x: number, y: number) => xf.c * x + xf.d * y + xf.f;

function tokenize(text: string): Tag[] {
  const out: Tag[] = [];
  const lines = text.split(/\r?\n/);
  let pending: number | null = null;
  for (let i = 0; i < lines.length; i++) {
    const code = pending ?? parseInt(lines[i].trim(), 10);
    if (Number.isNaN(code)) continue;
    if (++i >= lines.length) break;
    out.push({ code, value: lines[i] });
    pending = null;
  }
  return out;
}

export function readDxf(text: string): CadDrawing | null {
  const tags = tokenize(text);
  if (tags.length === 0) return null;

  const blocks = new Map<string, Block>();
  const model: Entity[] = [];
  partition(tags, blocks, model);
  if (model.length === 0) return null;

  const raw: CadPolyline[] = [];
  let budget = MAX_VERTICES;
  emit(model, ID, blocks, raw, 0, (n) => { budget -= n; return budget > 0; });
  if (raw.length === 0) return null;

  let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
  for (const p of raw) {
    for (let i = 0; i < p.xs.length; i++) {
      if (p.xs[i] < minX) minX = p.xs[i];
      if (p.xs[i] > maxX) maxX = p.xs[i];
      if (p.ys[i] < minY) minY = p.ys[i];
      if (p.ys[i] > maxY) maxY = p.ys[i];
    }
  }
  if (![minX, minY, maxX, maxY].every(Number.isFinite)) return null;
  if (maxX <= minX || maxY <= minY) return null;

  const span = Math.max(maxX - minX, maxY - minY);
  const cx = (minX + maxX) / 2;
  const cy = (minY + maxY) / 2;
  const polylines = raw.map((p) => ({
    xs: p.xs.map((x) => 0.5 + (x - cx) / span),
    ys: p.ys.map((y) => 0.5 - (y - cy) / span),
    closed: p.closed,
  }));
  const nativeW = maxX - minX;
  const nativeH = maxY - minY;
  return { polylines, aspectRatio: nativeH > 1e-6 ? nativeW / nativeH : 1 };
}

function partition(tags: Tag[], blocks: Map<string, Block>, model: Entity[]): void {
  let current: Entity | null = null;
  let blockName: string | null = null;
  let blockBaseX = 0, blockBaseY = 0;
  let blockEntities: Entity[] | null = null;
  let polyline: Entity | null = null;

  const flush = () => {
    const c = current;
    current = null;
    if (!c || c.type === 'VERTEX' || c.type === 'BLOCK') return;
    if (blockEntities) blockEntities.push(c); else model.push(c);
  };

  for (let i = 0; i < tags.length; i++) {
    const t = tags[i];
    if (t.code !== 0) {
      if (current) {
        current.tags.push(t);
        if (current.type === 'BLOCK') {
          if (t.code === 2) blockName = t.value.trim();
          else if (t.code === 10) blockBaseX = parseFloat(t.value.trim()) || 0;
          else if (t.code === 20) blockBaseY = parseFloat(t.value.trim()) || 0;
        }
      }
      continue;
    }
    const name = t.value.trim();
    switch (name) {
      case 'SECTION': {
        flush();
        const next = tags[i + 1];
        if (next && next.code === 2) i++;
        break;
      }
      case 'ENDSEC':
        flush(); blockName = null; blockEntities = null; polyline = null;
        break;
      case 'BLOCK':
        flush(); blockName = null; blockBaseX = 0; blockBaseY = 0;
        blockEntities = [];
        current = { type: 'BLOCK', tags: [], verts: [] };
        break;
      case 'ENDBLK': {
        flush();
        if (blockName && blockEntities) blocks.set(blockName, { name: blockName, baseX: blockBaseX, baseY: blockBaseY, entities: blockEntities });
        blockName = null; blockEntities = null;
        break;
      }
      case 'VERTEX':
        if (polyline) {
          const v: Entity = { type: 'VERTEX', tags: [], verts: [] };
          polyline.verts.push(v);
          current = v;
        } else {
          current = null;
        }
        break;
      case 'SEQEND':
        current = null; polyline = null;
        break;
      default:
        flush();
        if (!name) break;
        current = { type: name, tags: [], verts: [] };
        if (name === 'POLYLINE') polyline = current;
        break;
    }
  }
  flush();
}

// ── entity helpers ──

function first(e: Entity, code: number): string | undefined {
  return e.tags.find((t) => t.code === code)?.value;
}
function dbl(e: Entity, code: number, def = 0): number {
  const v = first(e, code)?.trim();
  const n = v !== undefined ? parseFloat(v) : NaN;
  return Number.isFinite(n) ? n : def;
}
function all(e: Entity, code: number): number[] {
  return e.tags.filter((t) => t.code === code)
    .map((t) => parseFloat(t.value.trim()))
    .filter(Number.isFinite);
}
const gx = (e: Entity, code: number) => dbl(e, code);
const gy = (e: Entity, code: number) => dbl(e, code + 10);

function addSegment(out: CadPolyline[], xf: Xform, x1: number, y1: number, x2: number, y2: number, tick: (n: number) => boolean): void {
  if (!tick(2)) return;
  out.push({ xs: [mapX(xf, x1, y1), mapX(xf, x2, y2)], ys: [mapY(xf, x1, y1), mapY(xf, x2, y2)], closed: false });
}

function addPoly(out: CadPolyline[], flat: number[], closed: boolean, tick: (n: number) => boolean): void {
  const n = flat.length / 2;
  if (n < 2 || !tick(n)) return;
  const xs: number[] = [], ys: number[] = [];
  for (let k = 0; k < n; k++) { xs.push(flat[k * 2]); ys.push(flat[k * 2 + 1]); }
  out.push({ xs, ys, closed });
}

function emit(entities: Entity[], xf: Xform, blocks: Map<string, Block>, out: CadPolyline[], depth: number, tick: (n: number) => boolean): void {
  if (depth > MAX_DEPTH) return;
  for (const e of entities) {
    switch (e.type) {
      case 'LINE':
        addSegment(out, xf, gx(e, 10), gy(e, 10), gx(e, 11), gy(e, 11), tick);
        break;
      case 'LWPOLYLINE':
      case 'SPLINE': {
        const xs = all(e, 10), ys = all(e, 20);
        const n = Math.min(xs.length, ys.length);
        if (n >= 2) {
          const flat: number[] = [];
          for (let k = 0; k < n; k++) { flat.push(mapX(xf, xs[k], ys[k]), mapY(xf, xs[k], ys[k])); }
          addPoly(out, flat, e.type === 'LWPOLYLINE' && (dbl(e, 70) & 1) !== 0, tick);
        }
        break;
      }
      case 'POLYLINE': {
        const verts = e.verts;
        if (verts.length >= 2) {
          const flat: number[] = [];
          for (const v of verts) { flat.push(mapX(xf, gx(v, 10), gy(v, 10)), mapY(xf, gx(v, 10), gy(v, 10))); }
          addPoly(out, flat, (dbl(e, 70) & 2) !== 0, tick);
        }
        break;
      }
      case 'CIRCLE':
      case 'ARC': {
        const cx0 = gx(e, 10), cy0 = gy(e, 10), r = gx(e, 40);
        if (r > 1e-9) {
          const isArc = e.type === 'ARC';
          const a0 = isArc ? dbl(e, 50) : 0;
          let a1 = isArc ? dbl(e, 51) : 360;
          if (isArc && a1 <= a0) a1 += 360;
          const sweep = a1 - a0;
          const steps = Math.max(8, Math.round(CIRCLE_SEGMENTS * Math.abs(sweep) / 360));
          const flat: number[] = [];
          for (let k = 0; k <= steps; k++) {
            const ang = (a0 + sweep * k / steps) * DEG;
            flat.push(mapX(xf, cx0 + r * Math.cos(ang), cy0 + r * Math.sin(ang)),
                      mapY(xf, cx0 + r * Math.cos(ang), cy0 + r * Math.sin(ang)));
          }
          addPoly(out, flat, !isArc, tick);
        }
        break;
      }
      case 'ELLIPSE': {
        const cx0 = gx(e, 10), cy0 = gy(e, 10);
        const mx = gx(e, 11), my = gy(e, 11);
        const ratio = dbl(e, 40, 1);
        const t0 = dbl(e, 41, 0), t1 = dbl(e, 42, 2 * Math.PI);
        const majLen = Math.hypot(mx, my);
        if (majLen > 1e-9) {
          const minorX = -my * ratio, minorY = mx * ratio;
          const full = Math.abs(t1 - t0) >= 2 * Math.PI - 1e-6;
          const steps = Math.max(12, Math.round(CIRCLE_SEGMENTS * Math.abs(t1 - t0) / (2 * Math.PI)));
          const flat: number[] = [];
          for (let k = 0; k <= steps; k++) {
            const t = t0 + (t1 - t0) * k / steps;
            const px = cx0 + mx * Math.cos(t) + minorX * Math.sin(t);
            const py = cy0 + my * Math.cos(t) + minorY * Math.sin(t);
            flat.push(mapX(xf, px, py), mapY(xf, px, py));
          }
          addPoly(out, flat, full, tick);
        }
        break;
      }
      case 'SOLID':
      case '3DFACE':
      case 'TRACE': {
        const pts: Array<[number, number]> = [];
        for (const [xc, yc] of [[10, 20], [11, 21], [12, 22], [13, 23]] as const) {
          const sx = first(e, xc)?.trim(), sy = first(e, yc)?.trim();
          if (sx !== undefined && sy !== undefined) {
            const fx = parseFloat(sx), fy = parseFloat(sy);
            if (Number.isFinite(fx) && Number.isFinite(fy)) pts.push([fx, fy]);
          }
        }
        if (pts.length >= 3) {
          const flat: number[] = [];
          for (const [px, py] of pts) { flat.push(mapX(xf, px, py), mapY(xf, px, py)); }
          addPoly(out, flat, true, tick);
        }
        break;
      }
      case 'POINT':
        addSegment(out, xf, gx(e, 10), gy(e, 10), gx(e, 10), gy(e, 10), tick);
        break;
      case 'INSERT': {
        const name = first(e, 2)?.trim();
        const block = name ? blocks.get(name) : undefined;
        if (block) {
          const ix = gx(e, 10), iy = gy(e, 10);
          const sx = dbl(e, 41, 1), sy = dbl(e, 42, 1);
          const rot = dbl(e, 50) * DEG;
          const cs = Math.cos(rot) * sx, sn = Math.sin(rot) * sx;
          const cs2 = Math.cos(rot) * sy, sn2 = Math.sin(rot) * sy;
          const a = cs, b = -sn2, c = sn, d = cs2;
          const ee = ix - (a * block.baseX + b * block.baseY);
          const ff = iy - (c * block.baseX + d * block.baseY);
          const inner: Xform = {
            a: xf.a * a + xf.b * c,
            b: xf.a * b + xf.b * d,
            c: xf.c * a + xf.d * c,
            d: xf.c * b + xf.d * d,
            e: xf.a * ee + xf.b * ff + xf.e,
            f: xf.c * ee + xf.d * ff + xf.f,
          };
          emit(block.entities, inner, blocks, out, depth + 1, tick);
        }
        break;
      }
    }
  }
}
