import type { ProjectWithMeasurements } from './types';

/**
 * 持久化策略：
 *  1. 优先 File System Access API：用户选一个数据文件夹，自动读写 ac-camera-data.json
 *  2. 不可用时退回 localStorage（浏览器直接开也够用）
 *  3. 始终提供导出 / 导入 JSON 文件按钮（与安卓/旧桌面端数据互通）
 */

const LS_KEY = 'ac-camera-web-data-v1';
const FILE_NAME = 'ac-camera-data.json';

let dirHandle: FileSystemDirectoryHandle | null = null;

export function supportsFsApi(): boolean {
  return typeof (window as any).showDirectoryPicker === 'function';
}

export async function pickDataDir(): Promise<boolean> {
  if (!supportsFsApi()) return false;
  try {
    dirHandle = await (window as any).showDirectoryPicker({ mode: 'readwrite' });
    await save(); // 立刻把当前数据落盘到所选目录
    return true;
  } catch {
    return false; // 用户取消
  }
}

export function load(): ProjectWithMeasurements[] {
  try {
    const raw = localStorage.getItem(LS_KEY);
    if (raw) return JSON.parse(raw);
  } catch { /* ignore */ }
  return [];
}

export async function save(): Promise<void> {
  const { exportJson } = await import('./main-state');
  // 延迟取数据避免循环依赖：storage 不持有状态，由 main-state 提供当前数据。
  const data = exportJson();
  try {
    localStorage.setItem(LS_KEY, data);
  } catch { /* 容量不足时仍走文件路径 */ }
  if (dirHandle) {
    try {
      const fh = await dirHandle.getFileHandle(FILE_NAME, { create: true });
      const w = await fh.createWritable();
      await w.write(data);
      await w.close();
    } catch { /* 目录不可写时静默 */ }
  }
}

export function exportJsonText(data: string): void {
  const blob = new Blob([data], { type: 'application/json' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = FILE_NAME;
  a.click();
  URL.revokeObjectURL(a.href);
}

export function readJsonFile(file: File): Promise<string> {
  return file.text();
}

export function storageHint(): string {
  if (dirHandle) return `数据目录：${dirHandle.name}/${FILE_NAME}`;
  if (supportsFsApi()) return '数据存于浏览器本地。点击此处选择数据文件夹可自动落盘。';
  return '此浏览器不支持文件夹读写，数据存于浏览器本地，请用导出/导入按钮备份数据。';
}

export function currentDirName(): string | null {
  return dirHandle?.name ?? null;
}
