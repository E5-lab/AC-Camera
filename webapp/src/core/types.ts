/** 与旧版 Kotlin / JSON 导出格式字段一致，保证旧 data.json 可无损导入。 */
export interface Project {
  id: number;
  name: string;
  description: string;
  createdAt: number;
  updatedAt: number;
  /** 列数 = 网格单元数；测点位于交汇处，共 (cols+1) 列 */
  cols: number;
  rows: number;
  spacingX: number;
  spacingY: number;
  backgroundImagePath?: string | null;
  planReferenceMeters?: number | null;
  overlayAlpha: number;
  baseMapOffsetX: number;
  baseMapOffsetY: number;
  baseMapScale: number;
  backgroundNoiseDb: number;
  contourColorBands: number;
}

export interface Measurement {
  projectId: number;
  /** 交汇点列号 0..cols */
  col: number;
  /** 交汇点行号 0..rows */
  row: number;
  spl: number;
  note: string;
  isNoiseSource?: boolean;
}

export interface ProjectWithMeasurements {
  project: Project;
  measurements: Measurement[];
}

export function newProject(name: string, description: string, cols: number, rows: number, sx: number, sy: number): Project {
  const now = Date.now();
  return {
    id: now,
    name,
    description,
    createdAt: now,
    updatedAt: now,
    cols,
    rows,
    spacingX: sx,
    spacingY: sy,
    backgroundImagePath: null,
    planReferenceMeters: null,
    overlayAlpha: 0.72,
    baseMapOffsetX: 0,
    baseMapOffsetY: 0,
    baseMapScale: 1,
    backgroundNoiseDb: 0,
    contourColorBands: 32,
  };
}

export function pointCount(p: Project): number {
  return (p.cols + 1) * (p.rows + 1);
}
