# AC-Camera 项目记忆

## 定位
安卓现场声学测量 App，面向工厂噪声网格普查与治理。GitHub: E5-lab/AC-Camera。
现扩展为 **Compose 多平台（KMP）**：安卓 APK + Windows 桌面端共用 `shared` 模块算法/模型。

## 架构决策（已确认）
- **KMP 多端**：`shared`（Kotlin Multiplatform commonMain，纯 Kotlin，无 Android/Room/Compose 依赖）= 声学算法 + `@Serializable` 模型（Project/Measurement/IdwInterpolator/EnergyFieldInterpolator/ContourBuilder/HeatmapRenderer/NoiseSourceAnalyzer）。安卓端 `:app` 用 Room 存（ProjectEntity/MeasurementEntity 映射 shared 模型）；桌面端 `:desktopApp` 用 JSON 文件存（`~/ .ac_camera/data.json`，`ProjectWithMeasurements` 序列化）。
- 平台无关渲染：`HeatmapRenderer.renderColorGrid()` 返回 `IntArray`（packed ARGB）；安卓转 Bitmap、桌面转 ImageBitmap。
- 桌面端技术栈：**Compose for Desktop**（Compose Multiplatform 1.7.3 + Kotlin 1.9.25 + Gradle 8.9 + JDK 17），`mainClass=...desktop.MainKt`。
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

## 构建 / CI（产出 APK + Windows 客户端）
- 无本地 JDK/Gradle/SDK，由 **GitHub Actions** 云端构建 `.github/workflows/build-apk.yml`：
  - `build-android`（ubuntu）：`./gradlew :app:assembleDebug` → APK 传到 `latest-debug` Release。
  - `build-desktop-windows`（windows-latest）：`./gradlew :desktopApp:createDistributable` → 便携版 zip 传到 `latest-windows` Release（解压即跑 `AC-Camera.exe`，含捆绑 JRE `runtime/`）。选 windows runner 是因为 jpackage 无法在 Linux 交叉编译 Windows 可执行文件；便携版避开 WiX。
  - 两个 job 的 `build.log`/`desktop-build.log` 传 `ci-logs` Release（公开免鉴权）。
- 版本对齐（**关键坑，已验证**）：全项目统一 **Kotlin 1.9.25**；安卓用 Compose 编译器 1.5.15 + Compose UI BOM 2024.09.00；桌面端 CMP Gradle 插件必须用 **1.7.3**（1.5.12/1.6.11 只支持到 Kotlin 1.9.24，不含 1.9.25；1.7.3 才把 1.9.25 列入兼容表）。
- `settings.gradle.kts` 的 `dependencyResolutionManagement` 需含 `maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")`，否则 CMP 1.7.3 解析 `org.jetbrains.compose.compiler:compiler:1.5.15` 失败（该 artifact 不在 Maven Central）。
- CMP 桌面端坑：① 聚合依赖 `compose.desktop.currentOs` **不含 Material3**，需显式 `implementation(compose.material3)`；② 窗口 API 在 **`androidx.compose.ui.window`**（非 `org.jetbrains.compose.ui.window`）；③ 从文件加载位图用 `androidx.compose.ui.res.loadImageBitmap(InputStream)`（skiko AWT 桥 `toComposeImageBitmap` 不在编译 classpath）；④ 序列化 `Json.encodeToString` 推断类型易与 `SerializationStrategy` 重载歧义，改用 `encodeToString(ListSerializer(X.serializer()), ...)`。
- 工作流必须用 `set -o pipefail`，否则 `| tee build.log` 会掩盖 Gradle 失败、导致"假绿"无产物。
- GitHub SSH 偶发被 reset（22/443 都可能），重试即可；Release 大文件下载需断点续传（download_apk.py）。
- 坑：Compose `Canvas` 里用 `rememberCoroutineScope()` 返回的 `CoroutineScope` 调 `.launch{}` 需 `import kotlinx.coroutines.launch`（扩展函数）；`viewModelScope.launch` 是其自带成员方法，不需该 import。`drawImage` 缩放用 `drawImage(bmp, dstOffset=IntOffset(...), dstSize=IntSize(...))`，不用 `withTransform/translate/scale`（该环境未解析）。
- **Room：实体加/删字段必须同步升 `AppDatabase` version**，否则已装用户崩溃；当前 v2，`fallbackToDestructiveMigration` 会清数据。
- **Room 铁律：父表有 `ON DELETE CASCADE` 子表时，父表 DAO 禁用 `OnConflictStrategy.REPLACE`（= DELETE+INSERT，会级联清空子表）**；用 `@Insert` + `@Update` 分开（dccf838 修过"更新项目清空全部测点数据"的恶性 bug）。
- Compose 细节：`drawContext.density` 是 Density 对象非 Float（用 DrawScope 自身 `density`）；`Canvas.nativeCanvas` 需 import `androidx.compose.ui.graphics.nativeCanvas`；`collectAsStateWithLifecycle` 在 `androidx.lifecycle.compose` 包；委托属性需局部 val 才能 smart cast。
- GitHub SSH 偶发被 reset（22/443 都可能），重试即可；Release 大文件下载需断点续传（download_apk.py）。
