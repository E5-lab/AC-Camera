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

// ───────────────────────── 用户偏好 ─────────────────────────

type ToolbarMode = 'icon' | 'text' | 'both';

interface UiPrefs {
  showCalib: boolean;
  showPoints: boolean;
  showContours: boolean;
  showGridlines: boolean;
  /** 网格尺寸：场地总宽/总高的尺寸标注 */
  showDims: boolean;
  /** 底部距离比例尺 */
  showScaleBar: boolean;
  toolbarMode: ToolbarMode;
}

const PREFS_KEY = 'noiseatlas-ui-prefs-v1';

const prefs: UiPrefs = (() => {
  const def: UiPrefs = {
    showCalib: true, showPoints: true, showContours: true, showGridlines: true,
    showDims: true, showScaleBar: true, toolbarMode: 'both',
  };
  try {
    const raw = localStorage.getItem(PREFS_KEY);
    return raw ? { ...def, ...JSON.parse(raw) } : def;
  } catch {
    return def;
  }
})();

function savePrefs(): void {
  try { localStorage.setItem(PREFS_KEY, JSON.stringify(prefs)); } catch { /* ignore */ }
}

// ───────────────────────── state ─────────────────────────

let store: ProjectWithMeasurements[] = load();
let currentId: number | null = null;
let activeTab: 'entry' | 'contour' | 'settings' = 'entry';

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

// ───────────────────────── 图纸装饰 ─────────────────────────

function drawPaper(ctx: CanvasRenderingContext2D, world: Rect): void {
  ctx.save();
  ctx.fillStyle = '#fbfcfd';
  ctx.fillRect(world.left, world.top, world.width, world.height);
  ctx.strokeStyle = '#6d7681';
  ctx.lineWidth = 1;
  ctx.strokeRect(world.left + 0.5, world.top + 0.5, world.width - 1, world.height - 1);
  ctx.restore();
}

function niceStep(pxPerMeter: number, targetPx = 90): number {
  const meters = targetPx / Math.max(1e-9, pxPerMeter);
  const exp = Math.floor(Math.log10(meters));
  const base = Math.pow(10, exp);
  const f = meters / base;
  const nf = f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10;
  return nf * base;
}

function drawRulers(ctx: CanvasRenderingContext2D, world: Rect, p: Project): void {
  const wm = p.cols * p.spacingX;
  const hm = p.rows * p.spacingY;
  if (!(wm > 0) || !(hm > 0)) return;
  const pxPerMeter = world.width / wm;
  const step = niceStep(pxPerMeter);

  ctx.save();
  ctx.strokeStyle = '#aeb7c2';
  ctx.fillStyle = '#dfe5ec';
  ctx.lineWidth = 1;
  ctx.font = '10px "Segoe UI", system-ui';

  ctx.beginPath();
  for (let m = 0; m <= wm + 1e-6; m += step) {
    const x = world.left + m * pxPerMeter;
    ctx.moveTo(x, world.top - 5); ctx.lineTo(x, world.top);
  }
  for (let m = 0; m <= hm + 1e-6; m += step) {
    const y = world.top + m * pxPerMeter;
    ctx.moveTo(world.left - 5, y); ctx.lineTo(world.left, y);
  }
  ctx.stroke();

  ctx.textAlign = 'center';
  ctx.textBaseline = 'bottom';
  for (let m = 0; m <= wm + 1e-6; m += step) {
    ctx.fillText(`${Math.round(m)}`, world.left + m * pxPerMeter, world.top - 7);
  }
  ctx.textAlign = 'right';
  ctx.textBaseline = 'middle';
  for (let m = step; m <= hm + 1e-6; m += step) {
    ctx.fillText(`${Math.round(m)}`, world.left - 8, world.top + m * pxPerMeter);
  }
  ctx.textAlign = 'left';
  ctx.textBaseline = 'bottom';
  ctx.fillStyle = '#9aa4b0';
  ctx.fillText('m', world.left + world.width + 4, world.top - 7);
  ctx.restore();
}


/** 网格尺寸标注：上方总宽尺寸线 + 左侧总高尺寸线（工程图纸习惯）。 */
function drawDimensions(ctx: CanvasRenderingContext2D, world: Rect, p: Project): void {
  const wm = p.cols * p.spacingX;
  const hm = p.rows * p.spacingY;
  if (!(wm > 0) || !(hm > 0) || world.width < 120) return;

  ctx.save();
  ctx.lineWidth = 1;
  ctx.font = '10px "Segoe UI", system-ui';

  // ── 顶部：总宽 ──
  const y = world.top - 17;
  ctx.strokeStyle = '#8b95a1';
  ctx.beginPath();
  ctx.moveTo(world.left, y); ctx.lineTo(world.left + world.width, y);
  ctx.moveTo(world.left, y - 3); ctx.lineTo(world.left, y + 3);
  ctx.moveTo(world.left + world.width, y - 3); ctx.lineTo(world.left + world.width, y + 3);
  ctx.moveTo(world.left, y); ctx.lineTo(world.left, world.top - 11);
  ctx.moveTo(world.left + world.width, y); ctx.lineTo(world.left + world.width, world.top - 11);
  ctx.stroke();

  const wLabel = `${wm.toFixed(1)} m  (${p.cols} × ${p.spacingX} m)`;
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  const tw = ctx.measureText(wLabel).width;
  const cx = world.left + world.width / 2;
  ctx.fillStyle = 'rgba(240,243,247,0.94)';
  ctx.fillRect(cx - tw / 2 - 4, y - 7, tw + 8, 14);
  ctx.fillStyle = '#2f3945';
  ctx.fillText(wLabel, cx, y);

  // ── 左侧：总高（竖排） ──
  const x = world.left - 17;
  ctx.strokeStyle = '#8b95a1';
  ctx.beginPath();
  ctx.moveTo(x, world.top); ctx.lineTo(x, world.top + world.height);
  ctx.moveTo(x - 3, world.top); ctx.lineTo(x + 3, world.top);
  ctx.moveTo(x - 3, world.top + world.height); ctx.lineTo(x + 3, world.top + world.height);
  ctx.moveTo(x, world.top); ctx.lineTo(world.left - 11, world.top);
  ctx.moveTo(x, world.top + world.height); ctx.lineTo(world.left - 11, world.top + world.height);
  ctx.stroke();

  const hLabel = `${hm.toFixed(1)} m  (${p.rows} × ${p.spacingY} m)`;
  ctx.save();
  ctx.translate(x, world.top + world.height / 2);
  ctx.rotate(-Math.PI / 2);
  const hw = ctx.measureText(hLabel).width;
  ctx.fillStyle = 'rgba(240,243,247,0.94)';
  ctx.fillRect(-hw / 2 - 4, -7, hw + 8, 14);
  ctx.fillStyle = '#2f3945';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'middle';
  ctx.fillText(hLabel, 0, 0);
  ctx.restore();

  ctx.restore();
}

function drawScaleBar(ctx: CanvasRenderingContext2D, world: Rect, planRef: number | null | undefined, zoom: number): void {
  if (!planRef || planRef <= 0) return;
  const pxPerMeter = world.width / planRef / zoom;
  if (!(pxPerMeter > 0)) return;
  const meters = niceStep(pxPerMeter, 100);
  const barPx = meters * pxPerMeter;
  const x0 = world.left + 12;
  const y = world.top + world.height - 14;
  ctx.save();
  ctx.strokeStyle = 'rgba(255,255,255,0.9)';
  ctx.lineWidth = 4.5;
  ctx.beginPath(); ctx.moveTo(x0, y); ctx.lineTo(x0 + barPx, y); ctx.stroke();
  ctx.strokeStyle = '#1c2430';
  ctx.lineWidth = 1.5;
  ctx.beginPath();
  ctx.moveTo(x0, y); ctx.lineTo(x0 + barPx, y);
  ctx.moveTo(x0, y - 5); ctx.lineTo(x0, y + 5);
  ctx.moveTo(x0 + barPx / 2, y - 3); ctx.lineTo(x0 + barPx / 2, y + 3);
  ctx.moveTo(x0 + barPx, y - 5); ctx.lineTo(x0 + barPx, y + 5);
  ctx.stroke();
  ctx.fillStyle = '#1c2430';
  ctx.font = 'bold 11px "Segoe UI", system-ui';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'bottom';
  ctx.fillText(meters >= 1 ? `${meters} m` : `${meters.toFixed(1)} m`, x0, y - 7);
  ctx.restore();
}

function drawTitleBlock(ctx: CanvasRenderingContext2D, world: Rect, p: Project): void {
  const w = Math.min(240, world.width * 0.42);
  const h = 46;
  if (world.width < 320 || world.height < 200) return;
  const x = world.left + world.width - w - 8;
  const y = world.top + world.height - h - 8;
  ctx.save();
  ctx.fillStyle = 'rgba(255,255,255,0.92)';
  ctx.strokeStyle = '#8b95a1';
  ctx.lineWidth = 1;
  ctx.fillRect(x, y, w, h);
  ctx.strokeRect(x + 0.5, y + 0.5, w - 1, h - 1);
  ctx.strokeStyle = '#d6dbe1';
  ctx.beginPath();
  ctx.moveTo(x, y + 18); ctx.lineTo(x + w, y + 18);
  ctx.stroke();
  ctx.fillStyle = '#1c2430';
  ctx.font = 'bold 11px "Segoe UI", system-ui';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  ctx.fillText(p.name.slice(0, 24), x + 8, y + 9);
  ctx.font = '10px "Segoe UI", system-ui';
  ctx.fillStyle = '#5a6472';
  ctx.fillText(
    `${p.cols}×${p.rows} cell · ${(p.cols + 1) * (p.rows + 1)} 点 · 间距 ${p.spacingX}×${p.spacingY} m`,
    x + 8, y + 28,
  );
  ctx.fillText(`NoiseAtlas · 底噪 ${p.backgroundNoiseDb} dBA`, x + 8, y + 40);
  ctx.restore();
}

/** Odeon / Raynoise 式：声压级色标贴在图纸右侧，纵向、带分贝刻度。 */
function drawSplScale(
  ctx: CanvasRenderingContext2D,
  world: Rect,
  field: FieldResult | null,
  bands: number,
): void {
  const x = world.left + world.width + 12;
  const barW = 16;
  const barH = Math.min(world.height * 0.9, 420);
  const y0 = world.top + (world.height - barH) / 2;
  if (world.width < 120) return;

  ctx.save();
  // 底板
  ctx.fillStyle = 'rgba(252,253,254,0.96)';
  ctx.strokeStyle = '#8b95a1';
  ctx.lineWidth = 1;
  const padX = 6;
  ctx.fillRect(x - padX, y0 - 18, barW + padX * 2 + 34, barH + 34);
  ctx.strokeRect(x - padX + 0.5, y0 - 18.5, barW + padX * 2 + 34, barH + 34);

  // 色带（低值在下）
  const colors = bandColors(bands, quantizeStep(bands));
  const n = colors.length;
  const bandH = barH / n;
  for (let i = 0; i < n; i++) {
    const [r, g, b] = colors[n - 1 - i];
    ctx.fillStyle = `rgb(${r},${g},${b})`;
    ctx.fillRect(x, y0 + i * bandH, barW, Math.ceil(bandH) + 0.6);
  }
  ctx.strokeStyle = '#5c6672';
  ctx.strokeRect(x + 0.5, y0 + 0.5, barW - 1, barH - 1);

  // 刻度
  const lo = field ? field.min : 0;
  const hi = field ? field.max : 100;
  const ticks = 5;
  ctx.font = '10px "Segoe UI", system-ui';
  ctx.fillStyle = '#2b333d';
  ctx.textAlign = 'left';
  ctx.textBaseline = 'middle';
  for (let i = 0; i < ticks; i++) {
    const frac = i / (ticks - 1);           // 0 = 顶部 = 最高值
    const y = y0 + barH * frac;
    const val = hi - (hi - lo) * frac;
    ctx.strokeStyle = '#5c6672';
    ctx.beginPath();
    ctx.moveTo(x + barW, y); ctx.lineTo(x + barW + 4, y);
    ctx.stroke();
    ctx.fillText(val.toFixed(0), x + barW + 6, y);
  }
  // 单位与标题
  ctx.font = 'bold 10px "Segoe UI", system-ui';
  ctx.textAlign = 'center';
  ctx.textBaseline = 'bottom';
  ctx.fillText('dB', x + barW / 2, y0 - 6);
  ctx.font = '9px "Segoe UI", system-ui';
  ctx.fillStyle = '#6f7885';
  ctx.fillText('SPL', x + barW / 2, y0 - 16);
  ctx.restore();
}

// ───────────────────────── sidebar / tree ─────────────────────────

const projectListEl = document.getElementById('project-list')!;
const storageHintEl = document.getElementById('storage-hint')!;
const projectCountEl = document.getElementById('project-count')!;
const objTreeEl = document.getElementById('obj-tree')!;

function renderSidebar(): void {
  projectListEl.innerHTML = '';
  const list = sortStore();
  projectCountEl.textContent = String(list.length);
  for (const { project } of list) {
    const item = document.createElement('div');
    item.className = 'project-item' + (project.id === currentId ? ' active' : '');
    item.innerHTML = `<div class="p-name"></div><div class="p-meta"></div>
      <button class="p-del" title="删除项目">删除</button>`;
    item.querySelector('.p-name')!.textContent = project.name;
    item.querySelector('.p-meta')!.textContent =
      `${project.cols}×${project.rows} cell · ${(project.cols + 1) * (project.rows + 1)} 点`;
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

interface TreeNode {
  level: number;
  icon: string;
  name: string;
  badge?: string;
  cmd?: string;
  active?: boolean;
}

function renderTree(item: ProjectWithMeasurements | null): void {
  objTreeEl.innerHTML = '';
  if (!item) {
    const empty = document.createElement('div');
    empty.className = 'tree-node lvl-1';
    empty.innerHTML = '<span class="caret"></span><span class="t-name">（未打开项目）</span>';
    objTreeEl.appendChild(empty);
    return;
  }
  const p = item.project;
  const field = getCachedField(item);
  const sources = item.measurements.filter((m) => m.isNoiseSource).length;
  const cal = p.baseMapCalibration;
  const nodes: TreeNode[] = [
    { level: 0, icon: '▣', name: p.name, badge: `${p.cols}×${p.rows}`, cmd: 'tab-settings' },
    {
      level: 1, icon: '▦', name: '测量网格', badge: `${(p.cols + 1) * (p.rows + 1)} 点`,
      cmd: 'tab-settings', active: activeTab === 'settings',
    },
    {
      level: 2, icon: '·', name: '测点数据', badge: `${item.measurements.length}`,
      cmd: 'tab-entry', active: activeTab === 'entry',
    },
    {
      level: 2, icon: '≈', name: '等声压级图',
      badge: field ? `${field.min.toFixed(0)}–${field.max.toFixed(0)} dB` : '—',
      cmd: 'tab-contour', active: activeTab === 'contour',
    },
    {
      level: 1, icon: '▤', name: '底图',
      badge: p.backgroundDxfText ? 'CAD' : p.backgroundImageData ? '位图' : '无',
      cmd: 'basemap',
    },
    {
      level: 2, icon: '⟷', name: '两点定标',
      badge: cal ? `${cal.meters.toFixed(1)} m` : '未定标',
      cmd: 'calibrate',
    },
    { level: 1, icon: '◎', name: '分析结果', badge: sources ? `${sources} 源` : '' },
    { level: 2, icon: '◉', name: '噪声源', badge: sources ? `${sources} 处` : '未识别', cmd: 'noise' },
  ];

  for (const n of nodes) {
    const el = document.createElement('div');
    el.className = `tree-node lvl-${n.level}` + (n.cmd ? ' clickable' : '') + (n.active ? ' active' : '');
    el.innerHTML = `<span class="caret">${n.level === 0 ? '▾' : ''}</span>
      <span class="t-ico">${n.icon}</span>
      <span class="t-name"></span>
      <span class="t-badge"></span>`;
    el.querySelector('.t-name')!.textContent = n.name;
    const badge = el.querySelector('.t-badge') as HTMLElement;
    if (n.badge) badge.textContent = n.badge; else badge.remove();
    if (n.cmd) el.addEventListener('click', () => runCmd(n.cmd!));
    objTreeEl.appendChild(el);
  }
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]!));
}

// ───────────────────────── workspace & tabs ─────────────────────────

const emptyEl = document.getElementById('empty-state')!;
const wsEl = document.getElementById('workspace')!;
const zoomReadoutEl = document.getElementById('zoom-readout')!;

function switchTab(tab: typeof activeTab): void {
  activeTab = tab;
  document.querySelectorAll('.viewtab').forEach((b) => {
    b.classList.toggle('active', (b as HTMLElement).dataset.tab === tab);
  });
  for (const page of document.querySelectorAll('.tab-page')) {
    (page as HTMLElement).hidden = page.id !== `tab-${tab}`;
  }
  if (tab === 'contour') contourPlot.resize();
  if (tab === 'entry') entryPlot.resize();
  renderTree(current());
  updateStatus();
}

document.querySelectorAll('.viewtab').forEach((btn) => {
  btn.addEventListener('click', () => switchTab(((btn as HTMLElement).dataset.tab as typeof activeTab) ?? 'entry'));
});

function activePlot(): PlotCanvas {
  return activeTab === 'contour' ? contourPlot : entryPlot;
}

document.querySelectorAll('[data-zoom]').forEach((b) => {
  b.addEventListener('click', () => {
    const k = (b as HTMLElement).dataset.zoom!;
    const plot = activePlot();
    if (k === 'fit') plot.fit();
    else plot.zoomStep(k === 'in' ? 1.25 : 1 / 1.25);
  });
});

// ───────────────────────── entry tab ─────────────────────────

const entryPlot = new PlotCanvas(document.getElementById('entry-canvas-wrap')!);

function reportZoom(z: number): void {
  zoomReadoutEl.textContent = `${z.toFixed(1)}×`;
  updateStatus();
}

entryPlot.onZoomChange = reportZoom;

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

  drawPaper(ctx, world);
  drawBasemap(ctx, world, project);

  ctx.strokeStyle = 'rgba(70,84,100,0.30)';
  ctx.lineWidth = 0.7;
  ctx.beginPath();
  for (let c = 1; c < cols; c++) {
    ctx.moveTo(world.left + c * cw, world.top);
    ctx.lineTo(world.left + c * cw, world.top + world.height);
  }
  for (let r = 1; r < rows; r++) {
    ctx.moveTo(world.left, world.top + r * ch);
    ctx.lineTo(world.left + world.width, world.top + r * ch);
  }
  ctx.stroke();

  const spl = splMap(item);
  const values = [...spl.values()];
  const min = values.length ? Math.min(...values) : 0;
  const max = values.length ? Math.max(...values, min + 1e-6) : 1;
  const dense = Math.min(cw, ch) < 26;
  for (let c = 0; c <= cols; c++) {
    for (let r = 0; r <= rows; r++) {
      const px = world.left + c * cw;
      const py = world.top + r * ch;
      const v = spl.get(`${c},${r}`);
      if (v !== undefined) {
        const t = (v - min) / (max - min || 1);
        const rad = Math.max(4, Math.min(10, Math.min(cw, ch) * 0.26));
        ctx.beginPath();
        ctx.arc(px, py, rad, 0, Math.PI * 2);
        ctx.fillStyle = splColor(t);
        ctx.fill();
        ctx.strokeStyle = 'rgba(255,255,255,0.85)';
        ctx.lineWidth = 1.2;
        ctx.stroke();
        if (!dense && entryPlot.transform.zoom >= 1.6) {
          ctx.fillStyle = '#111';
          ctx.font = '10px "Segoe UI", system-ui';
          ctx.textAlign = 'center';
          ctx.textBaseline = 'bottom';
          ctx.fillText(v.toFixed(0), px, py - rad - 2);
        }
      } else {
        ctx.strokeStyle = 'rgba(90,104,122,0.55)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(px - 3.5, py); ctx.lineTo(px + 3.5, py);
        ctx.moveTo(px, py - 3.5); ctx.lineTo(px, py + 3.5);
        ctx.stroke();
      }
    }
  }

  drawRulers(ctx, world, project);
  if (prefs.showDims) drawDimensions(ctx, world, project);
  drawTitleBlock(ctx, world, project);
  if (prefs.showScaleBar) drawScaleBar(ctx, world, project.planReferenceMeters, entryPlot.transform.zoom);
});

entryPlot.onHover = (u, v) => reportCoords(u, v);
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
    `测点 (列 ${col}, 行 ${row}) · x=${(col * project.spacingX).toFixed(1)} m  y=${(row * project.spacingY).toFixed(1)} m`;
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
  splFieldCache = null;
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
  splFieldCache = null;
  renderAll();
  dlg.close();
});

// ───────────────────────── contour tab ─────────────────────────

const contourPlot = new PlotCanvas(document.getElementById('contour-canvas-wrap')!);
contourPlot.padRight = 92; // 右侧留出声压级色标

const alphaSlider = document.getElementById('alpha-slider') as HTMLInputElement;
const alphaLabel = document.getElementById('alpha-label')!;
const contourLegendEl = document.getElementById('contour-legend')!;
const bandChoicesEl = document.getElementById('band-choices')!;
const basemapHintEl = document.getElementById('basemap-hint')!;
const noiseHintEl = document.getElementById('noise-hint')!;
const contourStatsEl = document.getElementById('contour-stats')!;

contourPlot.onZoomChange = reportZoom;
contourPlot.onHover = (u, v) => reportCoords(u, v);

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

  drawPaper(ctx, world);
  drawBasemap(ctx, world, project);

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

    if (prefs.showContours) {
      const levels = defaultLevels(field.min, field.max);
      ctx.strokeStyle = 'rgba(25,28,34,0.55)';
      ctx.lineWidth = 0.9;
      ctx.beginPath();
      for (const level of levels) {
        for (const s of contours(field, level)) {
          ctx.moveTo(world.left + s.x1 / field.resX * world.width, world.top + s.y1 / field.resY * world.height);
          ctx.lineTo(world.left + s.x2 / field.resX * world.width, world.top + s.y2 / field.resY * world.height);
        }
      }
      ctx.stroke();
    }
  }

  if (prefs.showGridlines) {
    ctx.strokeStyle = 'rgba(255,255,255,0.32)';
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
  }

  if (prefs.showPoints) {
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
          ctx.arc(px, py, 6.5, 0, Math.PI * 2);
          ctx.fillStyle = `rgb(${cr},${cg},${cb})`;
          ctx.fill();
          ctx.strokeStyle = 'rgba(255,255,255,0.8)';
          ctx.lineWidth = 1.2;
          ctx.stroke();
          if (m?.isNoiseSource) {
            ctx.beginPath();
            ctx.arc(px, py, 12, 0, Math.PI * 2);
            ctx.strokeStyle = '#c0392b';
            ctx.lineWidth = 2.2;
            ctx.stroke();
          }
        } else if (!field) {
          ctx.strokeStyle = 'rgba(70,84,100,0.5)';
          ctx.lineWidth = 1;
          ctx.beginPath();
          ctx.moveTo(px - 3.5, py); ctx.lineTo(px + 3.5, py);
          ctx.moveTo(px, py - 3.5); ctx.lineTo(px, py + 3.5);
          ctx.stroke();
        }
      }
    }
  }

  if (prefs.showCalib) drawCalibration(ctx, world, project);
  drawRulers(ctx, world, project);
  if (prefs.showDims) drawDimensions(ctx, world, project);
  drawSplScale(ctx, world, field, project.contourColorBands);
  drawTitleBlock(ctx, world, project);
  if (prefs.showScaleBar) drawScaleBar(ctx, world, project.planReferenceMeters, contourPlot.transform.zoom);
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

// ── 显示开关 ──

function bindToggle(id: string, key: keyof UiPrefs): void {
  const el = document.getElementById(id) as HTMLInputElement | null;
  if (!el) return;
  el.checked = Boolean(prefs[key]);
  el.addEventListener('change', () => {
    (prefs as any)[key] = el.checked;
    savePrefs();
    contourPlot.paint();
    entryPlot.paint();
  });
}

bindToggle('chk-calib', 'showCalib');
bindToggle('chk-points', 'showPoints');
bindToggle('chk-contours', 'showContours');
bindToggle('chk-gridlines', 'showGridlines');
bindToggle('chk-dims', 'showDims');
bindToggle('chk-scalebar', 'showScaleBar');

// ── 色阶 ──

function renderBandChoices(): void {
  const item = current();
  if (!item) return;
  bandChoicesEl.innerHTML = '';
  for (const b of [8, 16, 24, 32, 64, 128, 256]) {
    const btn = document.createElement('button');
    btn.className = item.project.contourColorBands === b ? 'active' : '';
    btn.textContent = String(b);
    btn.addEventListener('click', () => {
      item.project.contourColorBands = b;
      touch(item.project);
      persist();
      renderBandChoices();
      renderLegend(item);
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

  contourLegendEl.innerHTML = `
    <div class="legend-vbar">
      <canvas id="legend-canvas" width="24" height="170"></canvas>
      <div class="legend-ticks">
        <span>${hi.toFixed(0)}</span>
        <span>${(lo + (hi - lo) * 0.75).toFixed(0)}</span>
        <span>${((lo + hi) / 2).toFixed(0)}</span>
        <span>${(lo + (hi - lo) * 0.25).toFixed(0)}</span>
        <span>${lo.toFixed(0)}</span>
      </div>
    </div>
    <div class="legend-note">${field ? `色阶 ${bands} 级 · 蓝低 → 红高` : `色阶 ${bands} 级 · 尚无测点`}</div>
    <div class="legend-note">红圈 = 疑似噪声源 · 黄虚线 = 定标线段</div>`;

  const cv = document.getElementById('legend-canvas') as HTMLCanvasElement | null;
  if (!cv) return;
  const ctx = cv.getContext('2d')!;
  const n = colors.length;
  const bandH = cv.height / n;
  for (let i = 0; i < n; i++) {
    const [r, g, b] = colors[n - 1 - i];
    ctx.fillStyle = `rgb(${r},${g},${b})`;
    ctx.fillRect(0, i * bandH, cv.width, Math.ceil(bandH) + 0.5);
  }
  ctx.strokeStyle = 'rgba(0,0,0,0.15)';
  ctx.strokeRect(0.5, 0.5, cv.width - 1, cv.height - 1);
}

// ── 底图导入 ──

function hintBasemap(p: Project): void {
  const kind = p.backgroundDxfText
    ? 'CAD 矢量线图'
    : p.backgroundImageData
      ? '位图（图片 / PDF 第 1 页）'
      : '';
  const cal = p.baseMapCalibration;
  basemapHintEl.textContent = !kind
    ? '未导入底图。支持 JPG / PNG / WebP / PDF / DXF。'
    : cal
      ? `${kind} · 已定标 ${cal.meters.toFixed(1)} m · 缩放 ${p.baseMapScale.toFixed(2)}×`
      : `${kind} · 未定标（建议用「两点定标」校准比例）`;
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
    item.project.baseMapCalibration = null;
    touch(item.project);
    persist();
    refreshBasemapCache(item.project);
    hintBasemap(item.project);
    renderAll();
  } catch (err) {
    alert('底图导入失败：' + (err as Error).message);
  }
  basemapFile.value = '';
});

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
    hintBasemap(p);
    entryPlot.paint();
    contourPlot.paint();
  });
});

// ── 两点定标 ──

const calibBanner = document.getElementById('calib-banner') as HTMLElement;
const calibrateBtn = document.getElementById('btn-calibrate') as HTMLButtonElement;
let calibMode = false;
let calibPts: Array<{ u: number; v: number }> = [];

function setCalibMode(on: boolean): void {
  calibMode = on;
  calibPts = [];
  contourPlot.rawTapMode = on;
  calibBanner.hidden = !on;
  calibrateBtn.classList.toggle('active', on);
  if (on && activeTab !== 'contour') switchTab('contour');
  contourPlot.paint();
}

document.addEventListener('keydown', (e) => {
  if (e.key === 'Escape' && calibMode) setCalibMode(false);
});

function drawCalibration(ctx: CanvasRenderingContext2D, world: Rect, p: Project): void {
  const saved = p.baseMapCalibration;
  let a: { u: number; v: number } | null = null;
  let b: { u: number; v: number } | null = null;
  let label = '';
  if (calibMode) {
    a = calibPts[0] ?? null;
    b = calibPts[1] ?? null;
    label = calibPts.length === 1 ? '再点第二个点' : '';
  } else if (saved) {
    a = { u: saved.u1, v: saved.v1 };
    b = { u: saved.u2, v: saved.v2 };
    label = `${saved.meters.toFixed(1)} m`;
  }
  if (!a) return;

  const toPx = (q: { u: number; v: number }) => ({
    x: world.left + q.u * world.width,
    y: world.top + q.v * world.height,
  });
  const pa = toPx(a);
  ctx.save();
  ctx.strokeStyle = '#c8920a';
  ctx.fillStyle = '#c8920a';
  ctx.lineWidth = 1.8;
  ctx.setLineDash([7, 4]);
  if (b) {
    const pb = toPx(b);
    ctx.beginPath();
    ctx.moveTo(pa.x, pa.y);
    ctx.lineTo(pb.x, pb.y);
    ctx.stroke();
    ctx.setLineDash([]);
    for (const q of [pa, pb]) {
      ctx.beginPath();
      ctx.arc(q.x, q.y, 4.5, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = '#fff';
      ctx.lineWidth = 1.5;
      ctx.stroke();
      ctx.strokeStyle = '#c8920a';
      ctx.lineWidth = 1.8;
    }
    if (label) {
      const mx = (pa.x + pb.x) / 2;
      const my = (pa.y + pb.y) / 2 - 9;
      ctx.font = 'bold 11px "Segoe UI", system-ui';
      ctx.textAlign = 'center';
      ctx.textBaseline = 'bottom';
      ctx.lineWidth = 4;
      ctx.strokeStyle = 'rgba(255,255,255,0.92)';
      ctx.strokeText(label, mx, my);
      ctx.fillStyle = '#7a5b00';
      ctx.fillText(label, mx, my);
    }
  } else {
    ctx.setLineDash([]);
    ctx.beginPath();
    ctx.arc(pa.x, pa.y, 4.5, 0, Math.PI * 2);
    ctx.fill();
    if (label) {
      ctx.font = 'bold 11px "Segoe UI", system-ui';
      ctx.textAlign = 'left';
      ctx.textBaseline = 'bottom';
      ctx.fillStyle = '#7a5b00';
      ctx.fillText(label, pa.x + 9, pa.y - 6);
    }
  }
  ctx.restore();
}

contourPlot.onRawTap = (u, v) => {
  if (!calibMode) return;
  const item = current();
  if (!item) return;
  const p = item.project;
  if (!p.backgroundImageData && !p.backgroundDxfText) {
    alert('请先导入底图，再使用两点定标。');
    setCalibMode(false);
    return;
  }
  calibPts.push({ u, v });
  if (calibPts.length === 1) {
    contourPlot.paint();
    return;
  }

  const [a, b] = calibPts;
  const W = p.cols * p.spacingX;
  const H = p.rows * p.spacingY;
  const gridMeters = Math.hypot((b.u - a.u) * W, (b.v - a.v) * H);
  if (!(gridMeters > 1e-6)) {
    calibPts = [];
    contourPlot.paint();
    return;
  }
  const input = prompt(
    `这两点在底图上代表多少米？（例如厂房两轴线的实际间距）\n按当前网格比例量得 ${gridMeters.toFixed(2)} m`,
    gridMeters.toFixed(1),
  );
  const realM = parseFloat(input ?? '');
  if (!Number.isFinite(realM) || realM <= 0) {
    calibPts = [];
    contourPlot.paint();
    return;
  }
  const factor = realM / gridMeters;
  p.baseMapScale = Math.min(5, Math.max(0.2, p.baseMapScale * factor));
  p.baseMapCalibration = { u1: a.u, v1: a.v, u2: b.u, v2: b.v, meters: realM };
  p.planReferenceMeters = W;
  touch(p);
  persist();
  setCalibMode(false);
  hintBasemap(p);
  renderAll();
  alert(`已校准：底图缩放 ×${factor.toFixed(3)}（现为 ${p.baseMapScale.toFixed(2)}×）\n比例尺已按真实米制显示。`);
};

// ── 噪声源识别 ──

function runNoiseDetect(): void {
  const item = current();
  if (!item) { alert('请先打开项目'); return; }
  if (item.measurements.length === 0) { alert('请先录入测点数据'); return; }
  const thr = parseFloat(prompt('噪声源判定阈值 (dB)：', '70') ?? '70');
  if (Number.isNaN(thr)) return;
  const sources = detectNoiseSources(item.project, item.measurements, thr, item.project.backgroundNoiseDb);
  const keys = new Set(sources.map((s) => `${s.col},${s.row}`));
  for (const m of item.measurements) m.isNoiseSource = keys.has(`${m.col},${m.row}`);
  touch(item.project);
  persist();
  noiseHintEl.textContent = `识别到 ${sources.length} 个疑似噪声源（红圈标记）`;
  renderAll();
  switchTab('contour');
}

// ───────────────────────── settings ─────────────────────────

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
  splFieldCache = null;
  renderAll();
  alert('设置已保存');
});

// ───────────────────────── 状态栏 / 统计 ─────────────────────────

const stCoords = document.getElementById('st-coords')!;
const stZoom = document.getElementById('st-zoom')!;
const stProgress = document.getElementById('st-progress')!;
const stGrid = document.getElementById('st-grid')!;

function reportCoords(u: number, v: number): void {
  const item = current();
  if (!item || !Number.isFinite(u) || !Number.isFinite(v)) {
    stCoords.textContent = 'X — m　Y — m';
    return;
  }
  const p = item.project;
  stCoords.textContent = `X ${(u * p.cols * p.spacingX).toFixed(2)} m　Y ${(v * p.rows * p.spacingY).toFixed(2)} m`;
}

function updateStatus(): void {
  const item = current();
  const z = activePlot().transform.zoom;
  stZoom.textContent = `缩放 ${z.toFixed(1)}×`;
  if (!item) {
    stProgress.textContent = '已测 0 / 0';
    stGrid.textContent = '网格 —';
    return;
  }
  const p = item.project;
  stProgress.textContent = `已测 ${item.measurements.length} / ${pointCount(p)}`;
  stGrid.textContent = `网格 ${p.cols}×${p.rows} cell · ${p.spacingX}×${p.spacingY} m · 底噪 ${p.backgroundNoiseDb} dBA`;
}

function renderStats(item: ProjectWithMeasurements | null): void {
  if (!item) { contourStatsEl.innerHTML = ''; return; }
  const field = getCachedField(item);
  const sources = item.measurements.filter((m) => m.isNoiseSource).length;
  const rows: Array<[string, string]> = [
    ['测点', `${item.measurements.length} / ${pointCount(item.project)}`],
    ['声压级', field ? `${field.min.toFixed(1)} – ${field.max.toFixed(1)} dB` : '—'],
    ['噪声源', sources ? `${sources} 处` : '未识别'],
    ['底图', item.project.backgroundDxfText ? 'CAD 矢量' : item.project.backgroundImageData ? '位图' : '无'],
    ['定标', item.project.baseMapCalibration ? `${item.project.baseMapCalibration.meters.toFixed(1)} m` : '未定标'],
  ];
  contourStatsEl.innerHTML = rows
    .map(([k, v]) => `<span class="k">${k}</span><span class="v">${escapeHtml(v)}</span>`)
    .join('');
}

// ───────────────────────── 导出 / 复制 ─────────────────────────

function downloadBlob(blob: Blob, name: string): void {
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = name;
  a.click();
  URL.revokeObjectURL(a.href);
}

/** 导出前切到等声压级图并重绘，确保导出的是完整图纸。 */
function prepareExport(): ProjectWithMeasurements | null {
  const item = current();
  if (!item) { alert('请先打开项目'); return null; }
  if (activeTab !== 'contour') switchTab('contour');
  contourPlot.resize();
  contourPlot.paint();
  return item;
}

function exportImage(): void {
  const item = prepareExport();
  if (!item) return;
  contourPlot.canvas.toBlob((b) => {
    if (!b) { alert('导出失败：无法生成图像'); return; }
    downloadBlob(b, `${item.project.name}-等声压级图.png`);
  }, 'image/png');
}

async function exportPdf(): Promise<void> {
  const item = prepareExport();
  if (!item) return;
  try {
    const { jsPDF } = await import('jspdf');
    const cv = contourPlot.canvas;
    const img = cv.toDataURL('image/png');
    const w = cv.width, h = cv.height;
    const landscape = w >= h;
    const pdf = new jsPDF({ orientation: landscape ? 'landscape' : 'portrait', unit: 'mm', format: 'a4' });
    const pw = pdf.internal.pageSize.getWidth();
    const ph = pdf.internal.pageSize.getHeight();
    const margin = 10;
    pdf.setFontSize(11);
    pdf.text(`NoiseAtlas · ${item.project.name} · 等声压级图`, margin, margin + 3);
    const availW = pw - margin * 2;
    const availH = ph - margin * 2 - 8;
    const scale = Math.min(availW / w, availH / h);
    pdf.addImage(img, 'PNG', margin + (availW - w * scale) / 2, margin + 8, w * scale, h * scale);
    pdf.save(`${item.project.name}-等声压级图.pdf`);
  } catch (err) {
    alert('PDF 导出失败：' + (err as Error).message);
  }
}

async function copyImage(): Promise<void> {
  const item = prepareExport();
  if (!item) return;
  try {
    const blob = await new Promise<Blob | null>((res) => contourPlot.canvas.toBlob(res, 'image/png'));
    if (!blob) throw new Error('无法生成图像');
    await navigator.clipboard.write([new ClipboardItem({ 'image/png': blob })]);
    alert('图像已复制到剪贴板，可直接粘贴到 Word / CAD / 聊天工具。');
  } catch (err) {
    alert('复制失败（浏览器可能不支持图片剪贴板）：' + (err as Error).message);
  }
}

// ───────────────────────── 菜单栏 ─────────────────────────

const menuPanels = ['file', 'edit', 'view', 'calc', 'help'];

function closeMenus(): void {
  for (const m of menuPanels) {
    const panel = document.getElementById(`menu-${m}`);
    if (panel) panel.hidden = true;
  }
  document.querySelectorAll('#menubar .menu').forEach((b) => b.classList.remove('open'));
}

document.querySelectorAll('#menubar .menu').forEach((btn) => {
  btn.addEventListener('click', (e) => {
    e.stopPropagation();
    const id = `menu-${(btn as HTMLElement).dataset.menu}`;
    const panel = document.getElementById(id)!;
    const wasOpen = !panel.hidden;
    closeMenus();
    if (wasOpen) return;
    // 工具栏 32px 之后定位
    const r = (btn as HTMLElement).getBoundingClientRect();
    panel.style.left = `${Math.round(r.left)}px`;
    panel.style.top = `${Math.round(r.bottom + 2)}px`;
    panel.hidden = false;
    btn.classList.add('open');
  });
});

document.addEventListener('click', () => closeMenus());
document.addEventListener('keydown', (e) => { if (e.key === 'Escape') closeMenus(); });

document.querySelectorAll('.menu-panel button[data-cmd]').forEach((b) => {
  b.addEventListener('click', () => {
    const cmd = (b as HTMLElement).dataset.cmd!;
    closeMenus();
    runCmd(cmd);
  });
});


// 窄屏面板抽屉开关
document.getElementById('btn-toggle-tree')?.addEventListener('click', () => {
  document.getElementById('sidebar')!.classList.toggle('open');
  document.getElementById('right-dock')!.classList.remove('open');
});
document.getElementById('btn-toggle-dock')?.addEventListener('click', () => {
  document.getElementById('right-dock')!.classList.toggle('open');
  document.getElementById('sidebar')!.classList.remove('open');
});
// 点击画布时收起抽屉，避免遮挡绘图区
for (const el of [entryPlot.canvas, contourPlot.canvas]) {
  el.addEventListener('pointerdown', () => {
    document.getElementById('sidebar')?.classList.remove('open');
    document.getElementById('right-dock')?.classList.remove('open');
  });
}

// 右侧分区折叠
document.querySelectorAll('#right-dock .dock-head').forEach((h) => {
  h.addEventListener('click', () => {
    (h.parentElement as HTMLElement).classList.toggle('collapsed');
  });
});

function applyToolbarMode(mode: ToolbarMode): void {
  prefs.toolbarMode = mode;
  savePrefs();
  const tb = document.getElementById('toolbar')!;
  tb.classList.remove('mode-icon', 'mode-text', 'mode-both');
  tb.classList.add(`mode-${mode}`);
}

function runCmd(cmd: string): void {
  const item = current();
  switch (cmd) {
    case 'new-project': createProject(); break;
    case 'demo': createDemo(); break;
    case 'import': (document.getElementById('import-file') as HTMLInputElement).click(); break;
    case 'export': exportJsonText(JSON.stringify(sortStore(), null, 2)); break;
    case 'export-img': exportImage(); break;
    case 'export-pdf': void exportPdf(); break;
    case 'copy-img': void copyImage(); break;
    case 'delete-project':
      if (!item) { alert('没有打开的项目'); return; }
      if (!confirm(`确定删除项目「${item.project.name}」？`)) return;
      store = store.filter((x) => x.project.id !== item.project.id);
      currentId = null;
      persist();
      renderAll();
      break;
    case 'clear-measurements':
      if (!item) return;
      if (!confirm('清空当前项目的全部测点数据？此操作不可撤销。')) return;
      item.measurements = [];
      touch(item.project);
      persist();
      splFieldCache = null;
      renderAll();
      break;
    case 'clear-noise-flags':
      if (!item) return;
      for (const m of item.measurements) m.isNoiseSource = false;
      touch(item.project);
      persist();
      renderAll();
      break;
    case 'tab-entry': switchTab('entry'); break;
    case 'tab-contour': switchTab('contour'); break;
    case 'tab-settings': switchTab('settings'); break;
    case 'zoom-fit': activePlot().fit(); break;
    case 'tb-icon': applyToolbarMode('icon'); break;
    case 'tb-text': applyToolbarMode('text'); break;
    case 'tb-both': applyToolbarMode('both'); break;
    case 'toggle-calib':
      prefs.showCalib = !prefs.showCalib;
      savePrefs();
      (document.getElementById('chk-calib') as HTMLInputElement).checked = prefs.showCalib;
      contourPlot.paint();
      break;
    case 'toggle-points':
      prefs.showPoints = !prefs.showPoints;
      savePrefs();
      (document.getElementById('chk-points') as HTMLInputElement).checked = prefs.showPoints;
      contourPlot.paint();
      break;
    case 'toggle-dims':
      prefs.showDims = !prefs.showDims;
      savePrefs();
      (document.getElementById('chk-dims') as HTMLInputElement).checked = prefs.showDims;
      contourPlot.paint();
      entryPlot.paint();
      break;
    case 'toggle-scalebar':
      prefs.showScaleBar = !prefs.showScaleBar;
      savePrefs();
      (document.getElementById('chk-scalebar') as HTMLInputElement).checked = prefs.showScaleBar;
      contourPlot.paint();
      entryPlot.paint();
      break;
    case 'noise': runNoiseDetect(); break;
    case 'recompute':
      splFieldCache = null;
      renderAll();
      break;
    case 'basemap':
      if (!item) { alert('请先打开项目'); return; }
      basemapFile.click();
      break;
    case 'calibrate': setCalibMode(!calibMode); break;
    case 'about':
      alert('NoiseAtlas 噪声图谱 0.2.0\n\n厂区噪声网格普查 · 等声压级成图 · 噪声源定位\n'
        + '算法：能量叠加 + 距离衰减声场 / Marching Squares 等值线\n'
        + '数据格式与旧版 AC-Camera JSON 兼容');
      break;
    default: break;
  }
}

// 工具条按钮 → 命令
document.getElementById('btn-new-project')!.addEventListener('click', () => runCmd('new-project'));
document.getElementById('btn-demo')!.addEventListener('click', () => runCmd('demo'));
document.getElementById('btn-import')!.addEventListener('click', () => runCmd('import'));
document.getElementById('btn-export')!.addEventListener('click', () => runCmd('export'));
document.getElementById('btn-basemap')!.addEventListener('click', () => runCmd('basemap'));
document.getElementById('btn-calibrate')!.addEventListener('click', () => runCmd('calibrate'));
document.getElementById('btn-noise')!.addEventListener('click', () => runCmd('noise'));
document.getElementById('btn-export-img')!.addEventListener('click', () => runCmd('export-img'));
document.getElementById('btn-export-pdf')!.addEventListener('click', () => runCmd('export-pdf'));
document.getElementById('btn-copy-img')!.addEventListener('click', () => runCmd('copy-img'));

// ───────────────────────── 项目创建 ─────────────────────────

function createProject(): void {
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
}

function createDemo(): void {
  const p = newProject('示例 · 机加工车间', '演示数据：2 台冲床 + 1 台空压机', 8, 6, 5, 5);
  p.backgroundNoiseDb = 55;
  const measurements: Measurement[] = [];
  const sources: Array<[number, number, number]> = [[2, 2, 92], [6, 1, 88], [5, 5, 85]];
  let seed = 42;
  const rnd = () => { seed = (seed * 1103515245 + 12345) & 0x7fffffff; return seed / 0x7fffffff; };
  for (let c = 0; c <= p.cols; c++) {
    for (let r = 0; r <= p.rows; r++) {
      let spl = 55 + rnd() * 3;
      for (const [sc, sr, base] of sources) {
        const d = Math.hypot(c - sc, r - sr);
        spl = Math.max(spl, base - 6 * d + rnd() * 2);
      }
      measurements.push({ projectId: p.id, col: c, row: r, spl: Math.round(spl * 10) / 10, note: '' });
    }
  }
  measurements[2 * (p.rows + 1) + 2].isNoiseSource = true;
  measurements[6 * (p.rows + 1) + 1].isNoiseSource = true;
  store.push({ project: p, measurements });
  currentId = p.id;
  persist();
  renderAll();
}

// ───────────────────────── 渲染 ─────────────────────────

function renderWorkspace(): void {
  const item = current();
  emptyEl.hidden = !!item;
  wsEl.hidden = !item;
  if (!item) {
    renderTree(null);
    updateStatus();
    return;
  }
  const { project } = item;

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
  renderStats(item);
  renderTree(item);
  updateStatus();
  entryPlot.paint();
  contourPlot.paint();
}

function renderAll(): void {
  renderSidebar();
  renderWorkspace();
  document.querySelectorAll('.viewtab').forEach((b) => {
    b.classList.toggle('active', (b as HTMLElement).dataset.tab === activeTab);
  });
}

applyToolbarMode(prefs.toolbarMode);
renderAll();

window.addEventListener('resize', () => {
  entryPlot.resize();
  contourPlot.resize();
});
