/** 解除 storage ↔ main 的循环依赖：main 注册导出器，storage 取当前数据。 */
import type { ProjectWithMeasurements } from './types';

let provider: () => string = () => '[]';

export function setExportProvider(fn: () => string): void {
  provider = fn;
}

export function exportJson(): string {
  return provider();
}
