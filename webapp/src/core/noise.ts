/**
 * 噪声源识别 —— 自 Kotlin NoiseSourceAnalyzer 1:1 移植：
 * 严格 8 邻域局部极大值 + 达到阈值 + 高于底噪，按声压级降序。
 */
import type { Measurement, Project } from './types';

export interface NoiseSource {
  col: number;
  row: number;
  x: number;
  y: number;
  spl: number;
}

export function detectNoiseSources(
  project: Project,
  measurements: Measurement[],
  thresholdDb: number,
  backgroundNoiseDb = 0.0,
): NoiseSource[] {
  if (measurements.length === 0) return [];

  const grid: number[][] = Array.from({ length: project.rows + 1 }, () =>
    new Array<number>(project.cols + 1).fill(NaN),
  );
  for (const m of measurements) {
    if (m.col >= 0 && m.col <= project.cols && m.row >= 0 && m.row <= project.rows) {
      grid[m.row][m.col] = m.spl;
    }
  }

  const sources: NoiseSource[] = [];
  for (const m of measurements) {
    const v = m.spl;
    if (v < thresholdDb) continue;
    if (v - backgroundNoiseDb <= 0.0) continue;

    let isMax = true;
    outer: for (let dr = -1; dr <= 1; dr++) {
      for (let dc = -1; dc <= 1; dc++) {
        if (dr === 0 && dc === 0) continue;
        const r = m.row + dr;
        const c = m.col + dc;
        if (r >= 0 && r <= project.rows && c >= 0 && c <= project.cols) {
          const nv = grid[r][c];
          if (!Number.isNaN(nv) && nv > v) {
            isMax = false;
            break outer;
          }
        }
      }
    }
    if (isMax) {
      sources.push({
        col: m.col,
        row: m.row,
        x: m.col * project.spacingX,
        y: m.row * project.spacingY,
        spl: v,
      });
    }
  }
  return sources.sort((a, b) => b.spl - a.spl);
}
