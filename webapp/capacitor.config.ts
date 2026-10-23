import type { CapacitorConfig } from '@capacitor/cli';

/**
 * 安卓壳：Capacitor 把 dist/ 打进 WebView，业务代码 100% 复用 Windows 版。
 * - webDir 指向 vite 构建产物
 * - 关闭缩放，避免系统手势与画布双指缩放冲突
 */
const config: CapacitorConfig = {
  appId: 'com.e5lab.noiseatlas',
  appName: 'NoiseAtlas',
  webDir: 'dist',
  android: {
    allowMixedContent: false,
    webContentsDebuggingEnabled: true,
  },
  server: {
    androidScheme: 'https',
  },
};

export default config;
