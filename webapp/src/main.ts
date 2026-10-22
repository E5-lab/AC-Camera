import './styles.css';
import { newProject, pointCount } from './core/types';
import type { Project, ProjectWithMeasurements, Measurement } from './core/types';
import { bandColors, colormap, computeSplField, quantizeStep, renderFieldImageData, FieldResult } from './core/field';
import { load, save, exportJsonText, readJsonFile, storageHint, pickDataDir, supportsFsApi } from './core/storage';
import { setExportProvider } from './core/main-state';
import { PlotCanvas } from './ui/plot';

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

  // 背景
  ctx.fillStyle = '#f6f7fa';
  ctx.fillRect(world.left, world.top, world.width, world.height);

  // 网格
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

  // 交汇点
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
        // 数值标注（缩放到足够大时显示）
        if (entryPlot.transform.zoom >= 1.8) {
          ctx.fillStyle = '#111';
          ctx.font = '11px system-ui';
          ctx.textAlign = 'center';
          ctx.fillText(v.toFixed(0), px, py - 13);
        }
      } else {
        // 未测点：十字
        ctx.strokeStyle = 'rgba(80,90,110,0.55)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(px - 4, py); ctx.lineTo(px + 4, py);
        ctx.moveTo(px, py - 4); ctx.lineTo(px, py + 4);
        ctx.stroke();
      }
    }
  }
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

  ctx.fillStyle = '#101418';
  ctx.fillRect(world.left, world.top, world.width, world.height);

  // 声场热力图：把 resX×resY 的 ImageData 画到离屏 canvas，再拉伸到世界矩形
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

  // 测点
  const spl = splMap(item);
  const range = field ? field.max - field.min : 1;
  for (let c = 0; c <= cols; c++) {
    for (let r = 0; r <= rows; r++) {
      const px = world.left + c * cw;
      const py = world.top + r * ch;
      const v = spl.get(`${c},${r}`);
      if (v !== undefined && field) {
        const [cr, cg, cb] = colormap((v - field.min) / (range || 1));
        ctx.beginPath();
        ctx.arc(px, py, 7, 0, Math.PI * 2);
        ctx.fillStyle = `rgb(${cr},${cg},${cb})`;
        ctx.fill();
        ctx.strokeStyle = 'rgba(255,255,255,0.7)';
        ctx.lineWidth = 1.5;
        ctx.stroke();
      } else {
        ctx.strokeStyle = 'rgba(255,255,255,0.55)';
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(px - 4, py); ctx.lineTo(px + 4, py);
        ctx.moveTo(px, py - 4); ctx.lineTo(px, py + 4);
        ctx.stroke();
      }
    }
  }

  // 底部数值刻度（min/中/max）
  if (field) {
    ctx.fillStyle = '#ddd';
    ctx.font = '12px system-ui';
    ctx.textAlign = 'left';
    ctx.fillText(`${field.min.toFixed(0)} dB`, world.left, world.top + world.height + 16);
    ctx.textAlign = 'center';
    ctx.fillText(`${((field.min + field.max) / 2).toFixed(0)} dB`, world.left + world.width / 2, world.top + world.height + 16);
    ctx.textAlign = 'right';
    ctx.fillText(`${field.max.toFixed(0)} dB`, world.left + world.width, world.top + world.height + 16);
  }
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

// ───────────────────────── settings tab ─────────────────────────

const settingsForm = document.getElementById('settings-form') as HTMLFormElement;

/** form.elements 具名访问（不能用 form.name —— 那是 form 自身的 name 属性）。 */
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
