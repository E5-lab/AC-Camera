import './styles.css';
import * as pdfjsLib from 'pdfjs-dist';
import pdfWorkerUrl from 'pdfjs-dist/build/pdf.worker.min.mjs?url';
import { newProject, pointCount } from './core/types';
import type { Project, ProjectWithMeasurements, Measurement } from './core/types';
import { bandColors, colormap, computeSplField, quantizeStep, renderFieldImageData } from './core/field';
import type { FieldResult } from './core/field';
import { contours, defaultLevels } from './core/contour';
import { readDxf } from './core/dxf';
import type { CadDrawing } from './core/dxf';
import { detectNoiseSources } from './core/noise';
import { load, save, exportJsonText, readJsonFile, storageHint, pickDataDir, supportsFsApi } from './core/storage';
import { setExportProvider } from './core/main-state';
import { PlotCanvas } from './ui/plot';

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorkerUrl;

// ───────────────────────── state ─────────────────────────

let store: ProjectWithMeasurements[] = load();
let currentId: number | null = null;

function current(): ProjectWithMeasurements | null {
  return store.find((p) => p.project.id === currentId) ?? null;
}

function touch(p: Project): void {
  p.updatedAt = Date.now();
}

function persist(): void {
  void save();
}

setExportProvider(() => JSON.stringify(sortStore(), null, 2));

function sortStore(): ProjectWithMeasurements[] {
  return [...store].sort((a, b) => b.project.updatedAt - a.project.updatedAt);
}

// ───────────────────────── base-map cache ─────────────────────────

interface Rect { left: number; top: number; width: number; height: number }

let bmCacheKey = '';
let bmImage: HTMLImageElement | null = null;
let bmCad: CadDrawing | null = null;

function basemapKey(p: Project): string {
  return `${p.id}:${p.backgroundImageData?.length ?? 0}:${p.backgroundDxfText?.length ?? 0}`;
}

function refreshBasemapCache(p: Project): void {
  const key = basemapKey(p);
  if (key === bmCacheKey) return;
  bmCacheKey = key;
  bmImage = null;
  bmCad = null;
  if (p.backgroundDxfText) {
    bmCad = readDxf(p.backgroundDxfText);
  } else if (p.backgroundImageData) {
    const img = new Image();
    img.src = p.backgroundImageData;
    img.decode().then(() => {
      bmImage = img;
      entryPlot.paint();
      contourPlot.paint();
    }).catch(() => { bmImage = null; });
  }
}

/** contain-fit 底图矩形，再叠加用户独立缩放/平移（底图对齐）。 */
function alignBaseMap(fitted: Rect, world: Rect, scale: number, offX: number, offY: number): Rect {
  const s = Number.isFinite(scale) && scale > 0 ? Math.min(5, Math.max(0.2, scale)) : 1;
  if (s === 1 && offX === 0 && offY === 0) return fitted;
  const cx = fitted.left + fitted.width / 2;
  const cy = fitted.top + fitted.height / 2;
  const w = fitted.width * s;
  const h = fitted.height * s;
  const dx = (Number.isFinite(offX) ? Math.min(0.5, Math.max(-0.5, offX)) : 0) * world.width;
  const dy = (Number.isFinite(offY) ? Math.min(0.5, Math.max(-0.5, offY)) : 0) * world.height;
  return { left: cx - w / 2 + dx, top: cy - h / 2 + dy, width: w, height: h };
}

function containFit(world: Rect, aspect: number): Rect {
  if (!(aspect > 0)) return world;
  const w = Math.min(world.width, world.height * aspect);
  const h = w / aspect;
  return { left: world.left + (world.width - w) / 2, top: world.top + (world.height - h) / 2, width: w, height: h };
}

function drawBasemap(ctx: CanvasRenderingContext2D, world: Rect, p: Project): void {
  let dst: Rect | null = null;
  if (bmCad) {
    dst = alignBaseMap(containFit(world, bmCad.aspectRatio), world, p.baseMapScale, p.baseMapOffsetX, p.baseMapOffsetY);
  } else if (bmImage && bmImage.naturalWidth > 0) {
    dst = alignBaseMap(containFit(world, bmImage.naturalWidth / bmImage.naturalHeight), world, p.baseMapScale, p.baseMapOffsetX, p.baseMapOffsetY);
  }
  if (!dst) return;

  ctx.save();
  if (bmCad) {
    ctx.strokeStyle = '#1f3b66';
    ctx.lineWidth = 1.1;
    ctx.beginPath();
    for (const poly of bmCad.polylines) {
      for (let i = 0; i < poly.xs.length; i++) {
        const x = dst.left + poly.xs[i] * dst.width;
        const y = dst.top + poly.ys[i] * dst.height;
        if (i === 0) ctx.moveTo(x, y); else ctx.lineTo(x, y);
      }
      if (poly.closed) ctx.closePath();
    }
    ctx.stroke();
  } else if (bmImage) {
    ctx.drawImage(bmImage, dst.left, dst.top, dst.width, dst.height);
  }
  ctx.restore();
}

/** 参照宽度比例尺（左下角，带白描边）。 */
function drawScaleBar(ctx: CanvasRenderingContext2D, world: Rect, planRef: number | null | undefined, zoom: number): void {
  if (!planRef || planRef <= 0) return;
  const pxPerMeter = world.width / planRef / zoom;
  if (!(pxPerMeter > 0)) return;
  const target = 100 / pxPerMeter;
  const exp = Math.floor(Math.log10(target));
  const base = Math.pow(10, exp);
  const f = target / base;
  const nf = f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10;
  const meters = nf * base;
  const barPx = meters * pxPerMeter;
  const x0 = world.left + 12;
  const y = world.top + world.height - 14;
  ctx.save();
  ctx.strokeStyle = 'rgba(255,255,255,0.9)';
  ctx.lineWidth = 4.5;
  ctx.beginPath(); ctx.moveTo(x0, y); ctx.lineTo(x0 + barPx, y); ctx.stroke();
  ctx.strokeStyle = '#000';
  ctx.lineWidth = 2;
  ctx.beginPath();
  ctx.moveTo(x0, y); ctx.lineTo(x0 + barPx, y);
  ctx.moveTo(x0, y - 6); ctx.lineTo(x0, y + 6);
  ctx.moveTo(x0 + barPx, y - 6); ctx.lineTo(x0 + barPx, y + 6);
  ctx.stroke();
  ctx.fillStyle = '#000';
  ctx.font = 'bold 12px system-ui';
  ctx.textAlign = 'left';
  ctx.fillText(meters >= 1 ? `${meters} m` : `${meters.toFixed(1)} m`, x0, y - 10);
  ctx.restore();
}

// ───────────────────────── sidebar ─────────────────────────

const projectListEl = document.getElementById('project-list')!;
const storageHintEl = document.getElementById('storage-hint')!;

function renderSidebar(): void {
  projectListEl.innerHTML = '';
  for (const { project } of sortStore()) {
    const item = document.createElement('div');
    item.className = 'project-item' + (project.id === currentId ? ' active' : '');
    item.innerHTML = `
      <div class="p-name"></div>
      <div class="p-meta"></div>
      <button class="p-del danger-link" title="删除项目">删除</button>`;
    item.querySelector('.p-name')!.textContent = project.name;
    item.querySelector('.p-meta')!.textContent =
      `${project.cols}×${project.rows} 格 · ${(project.cols + 1) * (project.rows + 1)} 点 · ${project.spacingX}×${project.spacingY} m`;
    item.addEventListener('click', (e) => {
      if ((e.target as HTMLElement).classList.contains('p-del')) return;
      currentId = project.id;
      renderAll();
    });
    item.querySelector('.p-del')!.addEventListener('click', () => {
      if (!confirm(`确定删除项目「${project.name}」及其全部测点数据？`)) return;
      store = store.filter((x) => x.project.id !== project.id);
      if (currentId === project.id) currentId = null;
      persist();
      renderAll();
    });
    projectListEl.appendChild(item);
  }
  storageHintEl.textContent = storageHint();
  if (supportsFsApi()) {
    storageHintEl.style.cursor = 'pointer';
    storageHintEl.onclick = () => void pickDataDir().then(renderSidebar);
  }
}

// ───────────────────────── new project ─────────────────────────

document.getElementById('btn-new-project')!.addEventListener('click', () => {
  const name = prompt('项目名称：', '新项目');
  if (!name) return;
  const cols = parseInt(prompt('列数（网格单元）：', '10') ?? '10', 10) || 10;
  const rows = parseInt(prompt('行数（网格单元）：', '10') || '10', 10) || 10;
  const sx = parseFloat(prompt('列间距 (m)：', '5') ?? '5') || 5;
  const sy = parseFloat(prompt('行间距 (m)：', '5') ?? '5') || 5;
  const p = newProject(name, '', Math.max(2, cols), Math.max(2, rows), Math.max(0.1, sx), Math.max(0.1, sy));
  store.push({ project: p, measurements: [] });
  currentId = p.id;
  persist();
  renderAll();
});

// ───────────────────────── export / import ─────────────────────────

document.getElementById('btn-export')!.addEventListener('click', () => {
  exportJsonText(JSON.stringify(sortStore(), null, 2));
});

document.getElementById('btn-import')!.addEventListener('click', () => {
  (document.getElementById('import-file') as HTMLInputElement).click();
});

document.getElementById('import-file')!.addEventListener('change', async (e) => {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const data = JSON.parse(await readJsonFile(file)) as ProjectWithMeasurements[];
    if (!Array.isArray(data)) throw new Error('格式不对');
    for (const item of data) {
      if (!item.project || !Array.isArray(item.measurements)) continue;
      const idx = store.findIndex((x) => x.project.id === item.project.id);
      if (idx >= 0) store[idx] = item; else store.push(item);
    }
    persist();
    renderAll();
    alert('导入完成');
  } catch (err) {
    alert('导入失败：' + (err as Error).message);
  }
  (e.target as HTMLInputElement).value = '';
});

// ───────────────────────── workspace & tabs ─────────────────────────

const emptyEl = document.getElementById('empty-state')!;
const wsEl = document.getElementById('workspace')!;

document.querySelectorAll('#tabs .tab').forEach((btn) => {
  btn.addEventListener('click', () => {
    document.querySelectorAll('#tabs .tab').forEach((b) => b.classList.remove('active'));
    btn.classList.add('active');
    const tab = (btn as HTMLElement).dataset.tab!;
    for (const page of document.querySelectorAll('.tab-page')) {
      (page as HTMLElement).hidden = page.id !== `tab-${tab}`;
    }
    if (tab === 'contour') contourPlot.resize();
    if (tab === 'entry') entryPlot.resize();
  });
});

// ───────────────────────── entry tab ─────────────────────────

const entryPlot = new PlotCanvas(document.getElementById('entry-canvas-wrap')!);
const entryProgressEl = document.getElementById('entry-progress')!;
const entryZoomLabel = document.getElementById('entry-zoom-label')!;
entryPlot.onZoomChange = (z) => (entryZoomLabel.textContent = `${z.toFixed(1)}×`);

document.querySelectorAll('[data-zoom]').forEach((b) => {
  b.addEventListener('click', () => {
    const k = (b as HTMLElement).dataset.zoom!;
    if (k === 'fit') entryPlot.fit();
    else entryPlot.zoomStep(k === 'in' ? 1.25 : 1 / 1.25);
  });
});

function splColor(t: number): string {
  const [r, g, b] = colormap(t);
  return `rgb(${r},${g},${b})`;
}

entryPlot.setPainter((world) => {
  const item = current();
  if (!item) return;
  const ctx = entryPlot.ctx2d;
  const { project } = item;
  const { cols, rows } = project;
  const cw = world.width / cols;
  const ch = world.height / rows;

  ctx.fillStyle = '#f6f7fa';
  ctx.fillRect(world.left, world.top, world.width, world.height);
  drawBasemap(ctx, world, project);

  ctx.strokeStyle = 'rgba(60,70,90,0.35)';
  ctx.lineWidth = 0.7;
  ctx.beginPath();
  for (let c = 0; c <= cols; c++) {
    ctx.moveTo(world.left + c * cw, world.top);
    ctx.lineTo(world.left + c * cw, world.top + world.height);
  }
  for (let r = 0; r <= rows; r++) {
    ctx.moveTo(world.left, world.top + r * ch);
    ctx.lineTo(world.left + world.width, world.top + r * ch);
  }
  ctx.stroke();

  const spl = splMap(item);
  const values = [...spl.values()];
  const min = values.length ? Math.min(...values) : 0;
  const max = values.length ? Math.max(...values, min + 1e-6) : 1;
  for (let c = 0; c <= cols; c++) {
    for (let r = 0; r <= rows; r++) {
      const px = world.left + c * cw;
      const py = world.top + r * ch;
      const v = spl.get(`${c},${r}`);
      if (v !== undefined) {
        const t = (v - min) / (max - min || 1);
        ctx.beginPath();
        ctx.arc(px, py, 9, 0, Math.PI * 2);
        ctx.fillStyle = splColor(t);
        ctx.fill();
        ctx.strokeStyle = 'rgba(255,255,255,0.75)';
        ctx.lineWidth = 1.5;
        ctx.stroke();
        if (entryPlot.transform.zoom >= 1.8) {
          ctx.fillStyle = '#111';
          ctx.font = '11px system-ui';
          ctx.textAlign = 'center';
          ctx.fillText(v.toFixed(0), px, py - 13);
        }
      } else {
        ctx.strokeStyle = 'rgba(80,90,110,0.55)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(px - 4, py); ctx.lineTo(px + 4, py);
        ctx.moveTo(px, py - 4); ctx.lineTo(px, py + 4);
        ctx.stroke();
      }
    }
  }
  drawScaleBar(ctx, world, project.planReferenceMeters, entryPlot.transform.zoom);
});

entryPlot.onTap = (col, row) => {
  const item = current();
  if (!item) return;
  openPointDialog(item, col, row);
};

function splMap(item: ProjectWithMeasurements): Map<string, number> {
  const m = new Map<string, number>();
  for (const meas of item.measurements) m.set(`${meas.col},${meas.row}`, meas.spl);
  return m;
}

// 录入弹窗
const dlg = document.getElementById('point-dialog') as HTMLDialogElement;
let dialogTarget: { col: number; row: number } | null = null;

function openPointDialog(item: ProjectWithMeasurements, col: number, row: number): void {
  dialogTarget = { col, row };
  const existing = item.measurements.find((m) => m.col === col && m.row === row);
  const { project } = item;
  (document.getElementById('pd-title') as HTMLElement).textContent =
    `测点 (列 ${col}, 行 ${row}) · x=${(col * project.spacingX).toFixed(1)}m y=${(row * project.spacingY).toFixed(1)}m`;
  (document.getElementById('pd-spl') as HTMLInputElement).value = existing ? String(existing.spl) : '';
  (document.getElementById('pd-note') as HTMLInputElement).value = existing?.note ?? '';
  (document.getElementById('pd-clear') as HTMLElement).style.display = existing ? '' : 'none';
  dlg.showModal();
}

document.getElementById('pd-save')!.addEventListener('click', () => {
  const item = current();
  if (!item || !dialogTarget) return;
  const v = parseFloat((document.getElementById('pd-spl') as HTMLInputElement).value);
  if (Number.isNaN(v)) { dlg.close(); return; }
  const note = (document.getElementById('pd-note') as HTMLInputElement).value;
  const { col, row } = dialogTarget;
  const idx = item.measurements.findIndex((m) => m.col === col && m.row === row);
  const meas: Measurement = { projectId: item.project.id, col, row, spl: v, note };
  if (idx >= 0) item.measurements[idx] = meas; else item.measurements.push(meas);
  touch(item.project);
  persist();
  renderAll();
  dlg.close();
});

document.getElementById('pd-clear')!.addEventListener('click', () => {
  const item = current();
  if (!item || !dialogTarget) return;
  const { col, row } = dialogTarget;
  item.measurements = item.measurements.filter((m) => !(m.col === col && m.row === row));
  touch(item.project);
  persist();
  renderAll();
  dlg.close();
});

// ───────────────────────── contour tab ─────────────────────────

const contourPlot = new PlotCanvas(document.getElementById('contour-canvas-wrap')!);
const alphaSlider = document.getElementById('alpha-slider') as HTMLInputElement;
const alphaLabel = document.getElementById('alpha-label')!;
const contourZoomLabel = document.getElementById('contour-zoom-label')!;
contourPlot.onZoomChange = (z) => (contourZoomLabel.textContent = `${z.toFixed(1)}×`);
const contourLegendEl = document.getElementById('contour-legend')!;
const bandChoicesEl = document.getElementById('band-choices')!;
const basemapHintEl = document.getElementById('basemap-hint')!;
const noiseHintEl = document.getElementById('noise-hint')!;

let splFieldCache: { key: string; field: FieldResult } | null = null;

function getCachedField(item: ProjectWithMeasurements): FieldResult | null {
  const spl = splMap(item);
  if (spl.size === 0) return null;
  const key = [...spl.entries()].sort().toString() + `|${item.project.cols}|${item.project.rows}|${item.project.spacingX}|${item.project.spacingY}|${item.project.backgroundNoiseDb}`;
  if (splFieldCache?.key === key) return splFieldCache.field;
  const field = computeSplField(item.project, spl);
  splFieldCache = { key, field };
  return field;
}

contourPlot.setPainter((world) => {
  const item = current();
  if (!item) return;
  const ctx = contourPlot.ctx2d;
  const field = getCachedField(item);
  const { project } = item;
  const { cols, rows } = project;
  const cw = world.width / cols;
  const ch = world.height / rows;

  ctx.fillStyle = '#eef1f5';
  ctx.fillRect(world.left, world.top, world.width, world.height);
  drawBasemap(ctx, world, project);

  // 声场热力图 + 等值线
  if (field) {
    const off = document.createElement('canvas');
    off.width = field.resX;
    off.height = field.resY;
    off.getContext('2d')!.putImageData(
      renderFieldImageData(field, project.contourColorBands, parseFloat(alphaSlider.value) / 100), 0, 0,
    );
    ctx.imageSmoothingEnabled = false;
    ctx.drawImage(off, world.left, world.top, world.width, world.height);
    ctx.imageSmoothingEnabled = true;

    // 等值线（Marching Squares，与 Kotlin 版同一实现）
    const levels = defaultLevels(field.min, field.max);
    ctx.strokeStyle = 'rgba(30,30,30,0.65)';
    ctx.lineWidth = 1;
    ctx.beginPath();
    for (const level of levels) {
      for (const s of contours(field, level)) {
        ctx.moveTo(world.left + s.x1 / field.resX * world.width, world.top + s.y1 / field.resY * world.height);
        ctx.lineTo(world.left + s.x2 / field.resX * world.width, world.top + s.y2 / field.resY * world.height);
      }
    }
    ctx.stroke();
  }

  // 网格线
  ctx.strokeStyle = 'rgba(255,255,255,0.35)';
  ctx.lineWidth = 0.6;
  ctx.beginPath();
  for (let c = 0; c <= cols; c++) {
    ctx.moveTo(world.left + c * cw, world.top);
    ctx.lineTo(world.left + c * cw, world.top + world.height);
  }
  for (let r = 0; r <= rows; r++) {
    ctx.moveTo(world.left, world.top + r * ch);
    ctx.lineTo(world.left + world.width, world.top + r * ch);
  }
  ctx.stroke();

  // 测点 + 噪声源黑圈
  const spl = splMap(item);
  const range = field ? field.max - field.min : 1;
  for (let c = 0; c <= cols; c++) {
    for (let r = 0; r <= rows; r++) {
      const px = world.left + c * cw;
      const py = world.top + r * ch;
      const m = item.measurements.find((mm) => mm.col === c && mm.row === r);
      const v = m?.spl;
      if (v !== undefined && field) {
        const [cr, cg, cb] = colormap((v - field.min) / (range || 1));
        ctx.beginPath();
        ctx.arc(px, py, 7, 0, Math.PI * 2);
        ctx.fillStyle = `rgb(${cr},${cg},${cb})`;
        ctx.fill();
        ctx.strokeStyle = 'rgba(255,255,255,0.7)';
        ctx.lineWidth = 1.5;
        ctx.stroke();
        if (m?.isNoiseSource) {
          ctx.beginPath();
          ctx.arc(px, py, 13, 0, Math.PI * 2);
          ctx.strokeStyle = '#000';
          ctx.lineWidth = 3;
          ctx.stroke();
        }
      } else {
        ctx.strokeStyle = 'rgba(80,90,110,0.5)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(px - 4, py); ctx.lineTo(px + 4, py);
        ctx.moveTo(px, py - 4); ctx.lineTo(px, py + 4);
        ctx.stroke();
      }
    }
  }
  drawScaleBar(ctx, world, project.planReferenceMeters, contourPlot.transform.zoom);
});

document.querySelectorAll('[data-czoom]').forEach((b) => {
  b.addEventListener('click', () => {
    const k = (b as HTMLElement).dataset.czoom!;
    if (k === 'fit') contourPlot.fit();
    else contourPlot.zoomStep(k === 'in' ? 1.25 : 1 / 1.25);
  });
});

alphaSlider.addEventListener('input', () => {
  const item = current();
  alphaLabel.textContent = `${alphaSlider.value}%`;
  if (!item) return;
  item.project.overlayAlpha = parseInt(alphaSlider.value, 10) / 100;
  touch(item.project);
  persist();
  contourPlot.paint();
});

// ── 色阶 ──

function renderBandChoices(): void {
  const item = current();
  if (!item) return;
  bandChoicesEl.innerHTML = '';
  for (const b of [8, 16, 24, 32, 64, 128, 256]) {
    const btn = document.createElement('button');
    btn.className = 'band-btn' + (item.project.contourColorBands === b ? ' active' : '');
    btn.textContent = String(b);
    btn.addEventListener('click', () => {
      item.project.contourColorBands = b;
      touch(item.project);
      persist();
      renderBandChoices();
      contourPlot.paint();
    });
    bandChoicesEl.appendChild(btn);
  }
}

function renderLegend(item: ProjectWithMeasurements): void {
  const bands = item.project.contourColorBands;
  const colors = bandColors(bands, quantizeStep(bands));
  const field = getCachedField(item);
  const lo = field ? field.min : 0;
  const hi = field ? field.max : 100;
  const cells = colors.map((c) => `<span style="background:rgb(${c[0]},${c[1]},${c[2]})"></span>`).join('');
  contourLegendEl.innerHTML = `
    <div class="legend-title">声压级 (dB) · 色阶 ${bands} 级（蓝低 → 红高）</div>
    <div class="legend-bar">${cells}</div>
    <div class="legend-scale">
      <span>${lo.toFixed(0)}</span><span>${(lo + (hi - lo) * 0.25).toFixed(0)}</span>
      <span>${((lo + hi) / 2).toFixed(0)}</span><span>${(lo + (hi - lo) * 0.75).toFixed(0)}</span>
      <span>${hi.toFixed(0)}</span>
    </div>`;
}

// ── 底图导入（图片 / PDF / DXF）──

function hintBasemap(p: Project): void {
  basemapHintEl.textContent = p.backgroundDxfText
    ? '底图：CAD 矢量线图'
    : p.backgroundImageData
      ? '底图：位图（图片或 PDF 第 1 页）'
      : '未导入底图';
}

async function downscaleToDataUrl(src: CanvasImageSource, w: number, h: number, max = 1600): Promise<string> {
  const scale = Math.min(1, max / Math.max(w, h));
  const cw = Math.max(1, Math.round(w * scale));
  const chh = Math.max(1, Math.round(h * scale));
  const cv = document.createElement('canvas');
  cv.width = cw;
  cv.height = chh;
  const c = cv.getContext('2d')!;
  c.fillStyle = '#fff';
  c.fillRect(0, 0, cw, chh);
  c.drawImage(src, 0, 0, cw, chh);
  return cv.toDataURL('image/jpeg', 0.85);
}

const basemapFile = document.getElementById('basemap-file') as HTMLInputElement;

document.getElementById('btn-basemap')!.addEventListener('click', () => basemapFile.click());

basemapFile.addEventListener('change', async () => {
  const item = current();
  const file = basemapFile.files?.[0];
  if (!item || !file) return;
  const ext = file.name.split('.').pop()?.toLowerCase() ?? '';
  try {
    if (ext === 'dxf') {
      const text = await file.text();
      if (!readDxf(text)) throw new Error('DXF 中未找到可用的线图');
      item.project.backgroundDxfText = text;
      item.project.backgroundImageData = null;
    } else if (ext === 'pdf') {
      const buf = await file.arrayBuffer();
      const doc = await pdfjsLib.getDocument({ data: new Uint8Array(buf) }).promise;
      const page = await doc.getPage(1);
      const vp = page.getViewport({ scale: 2 });
      const cv = document.createElement('canvas');
      cv.width = Math.round(vp.width);
      cv.height = Math.round(vp.height);
      const c = cv.getContext('2d')!;
      c.fillStyle = '#fff';
      c.fillRect(0, 0, cv.width, cv.height);
      await page.render({ canvasContext: c, viewport: vp }).promise;
      item.project.backgroundImageData = await downscaleToDataUrl(cv, cv.width, cv.height);
      item.project.backgroundDxfText = null;
      await doc.destroy();
    } else {
      const url = URL.createObjectURL(file);
      try {
        const img = new Image();
        img.src = url;
        await img.decode();
        item.project.backgroundImageData = await downscaleToDataUrl(img, img.naturalWidth, img.naturalHeight);
        item.project.backgroundDxfText = null;
      } finally {
        URL.revokeObjectURL(url);
      }
    }
    touch(item.project);
    persist();
    refreshBasemapCache(item.project);
    hintBasemap(item.project);
    entryPlot.paint();
    contourPlot.paint();
  } catch (err) {
    alert('底图导入失败：' + (err as Error).message);
  }
  basemapFile.value = '';
});

// ── 底图独立对齐（只动底图，不动声场层）──

document.querySelectorAll('[data-bm]').forEach((b) => {
  b.addEventListener('click', () => {
    const item = current();
    if (!item) return;
    const p = item.project;
    const k = (b as HTMLElement).dataset.bm!;
    if (k === 'in') p.baseMapScale = Math.min(5, p.baseMapScale * 1.1);
    else if (k === 'out') p.baseMapScale = Math.max(0.2, p.baseMapScale / 1.1);
    else if (k === 'left') p.baseMapOffsetX = Math.max(-0.5, p.baseMapOffsetX - 0.02);
    else if (k === 'right') p.baseMapOffsetX = Math.min(0.5, p.baseMapOffsetX + 0.02);
    else if (k === 'up') p.baseMapOffsetY = Math.max(-0.5, p.baseMapOffsetY - 0.02);
    else if (k === 'down') p.baseMapOffsetY = Math.min(0.5, p.baseMapOffsetY + 0.02);
    else if (k === 'reset') { p.baseMapScale = 1; p.baseMapOffsetX = 0; p.baseMapOffsetY = 0; }
    touch(p);
    persist();
    entryPlot.paint();
    contourPlot.paint();
  });
});

// ── 噪声源识别 ──

document.getElementById('btn-noise')!.addEventListener('click', () => {
  const item = current();
  if (!item) return;
  if (item.measurements.length === 0) { alert('请先录入测点数据'); return; }
  const thr = parseFloat(prompt('噪声源判定阈值 (dB)：', '70') ?? '70');
  if (Number.isNaN(thr)) return;
  const sources = detectNoiseSources(item.project, item.measurements, thr, item.project.backgroundNoiseDb);
  const keys = new Set(sources.map((s) => `${s.col},${s.row}`));
  for (const m of item.measurements) m.isNoiseSource = keys.has(`${m.col},${m.row}`);
  touch(item.project);
  persist();
  noiseHintEl.textContent = `识别到 ${sources.length} 个疑似噪声源（黑圈标记）`;
  contourPlot.paint();
});

// ───────────────────────── settings tab ─────────────────────────

const settingsForm = document.getElementById('settings-form') as HTMLFormElement;

function field(name: string): HTMLInputElement {
  return settingsForm.elements.namedItem(name) as HTMLInputElement;
}

function fillSettings(p: Project): void {
  field('name').value = p.name;
  field('description').value = p.description;
  field('cols').value = String(p.cols);
  field('rows').value = String(p.rows);
  field('spacingX').value = String(p.spacingX);
  field('spacingY').value = String(p.spacingY);
  field('planReferenceMeters').value = p.planReferenceMeters ? String(p.planReferenceMeters) : '';
  field('backgroundNoiseDb').value = String(p.backgroundNoiseDb);
}

settingsForm.addEventListener('submit', (e) => {
  e.preventDefault();
  const item = current();
  if (!item) return;
  const p = item.project;
  p.name = field('name').value.trim() || p.name;
  p.description = field('description').value;
  p.cols = Math.max(2, parseInt(field('cols').value, 10) || p.cols);
  p.rows = Math.max(2, parseInt(field('rows').value, 10) || p.rows);
  p.spacingX = Math.max(0.1, parseFloat(field('spacingX').value) || p.spacingX);
  p.spacingY = Math.max(0.1, parseFloat(field('spacingY').value) || p.spacingY);
  p.planReferenceMeters = parseFloat(field('planReferenceMeters').value) || null;
  p.backgroundNoiseDb = Math.max(0, parseFloat(field('backgroundNoiseDb').value) || 0);
  touch(p);
  persist();
  renderAll();
  alert('已保存');
});

// ───────────────────────── render all ─────────────────────────

function renderWorkspace(): void {
  const item = current();
  emptyEl.hidden = !!item;
  wsEl.hidden = !item;
  if (!item) return;
  const { project } = item;
  (document.getElementById('ws-title') as HTMLElement).textContent = project.name;
  const measured = item.measurements.length;
  (document.getElementById('ws-meta') as HTMLElement).textContent =
    `${project.cols}×${project.rows} 格（${pointCount(project)} 个测点）· 间距 ${project.spacingX}×${project.spacingY} m · 底噪 ${project.backgroundNoiseDb} dBA`;
  entryProgressEl.textContent = `已测 ${measured} / ${pointCount(project)} 测点（测点位于网格线交汇处）`;

  entryPlot.cols = project.cols;
  entryPlot.rows = project.rows;
  contourPlot.cols = project.cols;
  contourPlot.rows = project.rows;

  alphaSlider.value = String(Math.round(project.overlayAlpha * 100));
  alphaLabel.textContent = `${Math.round(project.overlayAlpha * 100)}%`;

  refreshBasemapCache(project);
  hintBasemap(project);
  fillSettings(project);
  renderBandChoices();
  renderLegend(item);
  entryPlot.paint();
  contourPlot.paint();
}

function renderAll(): void {
  renderSidebar();
  renderWorkspace();
}

renderAll();
