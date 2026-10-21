# AC-Camera 项目记忆

## 定位
安卓现场声学测量 App，面向工厂噪声网格普查与治理。GitHub: E5-lab/AC-Camera。

## 架构决策（已确认）
- Kotlin + Jetpack Compose (Material3) + Room（离线优先，无网络可用）+ Navigation Compose。
- 声学算法：**声场用 `EnergyFieldInterpolator`（能量叠加+距离衰减，α=6dB/网格间距，底噪=max(E,0)+bg）**，取代 IDW（IDW 精确插值会在低能量点产生伪辐射凹陷，已弃用但文件保留）+ Marching Squares（`domain/contour/ContourBuilder`）+ 局部极大值噪声源检测（`domain/analysis/NoiseSourceAnalyzer`）。
- 测量数据由声级计人工录入（「数据输入」模块，原「现场测量/测试」已改名），麦克风 SoundMeter 已删除。
- 底图：平面图拷贝到 filesDir/backgrounds，ContourPlot 以 contain-fit 绘制 + overlayAlpha 透明度滑杆（状态用 override 防 flow 重发射重置）+ planReferenceMeters 比例尺；彩图色阶可选 8/16/32/64/128/256（Project.contourColorBands）。
- MVVM：ProjectListViewModel / ProjectViewModel；数据层 ProjectRepository。

## 约定
- 网格坐标：点(col,row) 物理坐标 x=col*spacingX, y=row*spacingY；面积=(cols-1)*spacingX × (rows-1)*spacingY。
- 噪声源标记存于 Measurement.isNoiseSource，由「噪声源分析」写回，等声压级图以黑圈显示。
- 沙箱无构建环境，按用户偏好不做本地 build；交付后由用户在 Android Studio 验证。
- minSdk 26；未提交 gradle-wrapper.jar，由 Android Studio 自动生成。

## 构建 / CI（产出 APK 给手机测试）
- 无本地 JDK/Gradle/SDK，APK 由 **GitHub Actions** 云端构建：`.github/workflows/build-apk.yml`（`assembleDebug` → 上传 APK 到 `latest-debug` Release；构建日志发到 `ci-logs` Release，公开免鉴权可下载）。
- 版本对齐：AGP 8.5.2 + **Kotlin 1.9.25** + Compose 编译器 **1.5.15** + Gradle 8.9（Compose 编译器 1.5.15 要求 Kotlin 1.9.25，非 1.9.24）。
- 工作流必须用 `set -o pipefail`，否则 `| tee build.log` 会掩盖 Gradle 失败、导致"假绿"无 APK。
- 坑：Compose `Canvas` 里用 `rememberCoroutineScope()` 返回的 `CoroutineScope` 调 `.launch{}` 需 `import kotlinx.coroutines.launch`（扩展函数）；`viewModelScope.launch` 是其自带成员方法，不需该 import。`drawImage` 缩放用 `drawImage(bmp, dstOffset=IntOffset(...), dstSize=IntSize(...))`，不用 `withTransform/translate/scale`（该环境未解析）。
- **Room：实体加/删字段必须同步升 `AppDatabase` version**，否则已装用户崩溃；当前 v2，`fallbackToDestructiveMigration` 会清数据。
- Compose 细节：`drawContext.density` 是 Density 对象非 Float（用 DrawScope 自身 `density`）；`Canvas.nativeCanvas` 需 import `androidx.compose.ui.graphics.nativeCanvas`；`collectAsStateWithLifecycle` 在 `androidx.lifecycle.compose` 包；委托属性需局部 val 才能 smart cast。
- GitHub SSH 偶发被 reset（22/443 都可能），重试即可；Release 大文件下载需断点续传（download_apk.py）。
