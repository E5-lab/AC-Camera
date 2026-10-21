# AC-Camera 项目记忆

## 定位
安卓现场声学测量 App，面向工厂噪声网格普查与治理。GitHub: E5-lab/AC-Camera。

## 架构决策（已确认）
- Kotlin + Jetpack Compose (Material3) + Room（离线优先，无网络可用）+ Navigation Compose。
- 声学算法：IDW 插值（`domain/interpolation/IdwInterpolator`）+ Marching Squares（`domain/contour/ContourBuilder`）+ 局部极大值噪声源检测（`domain/analysis/NoiseSourceAnalyzer`）。
- 实时测声：`audio/SoundMeter`（AudioRecord RMS→dB，校准偏移在 Project.calibrationOffsetDb）。
- MVVM：ProjectListViewModel / ProjectViewModel；数据层 ProjectRepository。

## 约定
- 网格坐标：点(col,row) 物理坐标 x=col*spacingX, y=row*spacingY；面积=(cols-1)*spacingX × (rows-1)*spacingY。
- 噪声源标记存于 Measurement.isNoiseSource，由「噪声源分析」写回，等声压级图以黑圈显示。
- 沙箱无构建环境，按用户偏好不做本地 build；交付后由用户在 Android Studio 验证。
- minSdk 26；未提交 gradle-wrapper.jar，由 Android Studio 自动生成。
