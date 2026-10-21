# AC-Camera · 工厂声学网格测量与等声压级绘图 App

面向**声学环保工程师**的安卓现场工具：把工厂区域划分成网格，在格点上测量声压级，
自动插值绘制**等声压级曲线（噪声地图）**，**反推主要噪声源位置**，并跟踪**定向治理**后的降噪效果。

> 仓库：https://github.com/E5-lab/AC-Camera
> 平台：Android 8.0+（minSdk 26） · 语言：Kotlin · UI：Jetpack Compose

---

## 核心工作流

本 App 完整覆盖你所描述的业务闭环：

1. **划分网格**（`网格设置`）
   - 定义列数 × 行数、列/行间距（米），系统据此计算厂区物理尺寸。
   - 可叠加一张厂区平面图照片作为底图，网格覆盖整张图面。
   - 设置**声级校准偏移**（dB），把手机麦克风读数对齐到已校准声级计。

2. **测点测量**（`现场测量`）
   - 在网格图上点击任意格点，调用麦克风实时估算声压级，或手工录入。
   - 每点记录：声压级 dB、备注（设备/工况）、测量时间。
   - 格点按声级高低着色（蓝低 → 红高），顶部显示测量进度。

3. **绘制等声压级曲线**（`等声压级图`）
   - 用 **IDW 反距离加权插值**把离散测点插值成连续声场。
   - 用 **Marching Squares（行进方块法）** 提取各分贝等级的等值线——即等声压级曲线。
   - 蓝→青→绿→黄→红色标表示声级强弱，黑圈标记已识别的疑似噪声源。

4. **反推噪声源**（`噪声源分析`）
   - 以“高于阈值的局部极大值”判定疑似噪声源，按声级从高到低排序。
   - 阈值滑块可调灵敏度；可一键把结果标记回测点（在等声压级图上以黑圈显示）。

5. **定向治理**（`治理跟踪`）
   - 对每个噪声源记录治理措施（隔声 / 消声 / 减振…）并填入复测声压级。
   - 自动计算**降噪量（dB）**，量化治理效果。

6. **导出**（`导出数据`）
   - 一键导出 CSV 报表（项目信息 + 全部测点坐标与声级），通过系统分享发送。

---

## 技术架构

| 层 | 技术 |
| --- | --- |
| 语言 / UI | Kotlin + Jetpack Compose (Material 3) |
| 架构 | MVVM + `ViewModel` + `StateFlow` / `collectAsStateWithLifecycle` |
| 导航 | Navigation Compose |
| 本地存储 | Room（**离线优先**，厂区现场无网络也能用） |
| 声学算法 | IDW 插值、Marching Squares 等值线、局部极大值噪声源检测 |
| 声测量 | `AudioRecord` 实时 RMS → dB，带校准偏移 |

### 目录结构

```
app/src/main/java/com/e5lab/accamera/
├── ACameraApplication.kt          # 数据库 / Repository 容器
├── MainActivity.kt
├── data/
│   ├── model/                     # Project / Measurement 实体
│   ├── local/                     # Room DB + DAO
│   └── repository/                # 离线优先数据层 + CSV 导出
├── audio/SoundMeter.kt           # 麦克风 → 声压级
├── domain/
│   ├── interpolation/             # IDW 插值 (FieldResult)
│   ├── contour/                  # Marching Squares + 热力图渲染
│   └── analysis/                  # 噪声源检测
└── ui/
    ├── theme/  navigation/  viewmodel/
    ├── components/                # GridPlot / ContourPlot / 色标 / 图加载
    └── screens/                  # 列表 / 仪表盘 / 设置 / 测量 / 等值线 / 分析 / 治理
```

---

## 编译与运行

1. 用 **Android Studio (Hedgehog 或更新)** 打开本仓库根目录。
2. 首次打开时若缺少 Gradle Wrapper，Android Studio 会自动生成；
   或执行 `gradle wrapper --gradle-version 8.9`（需本地已装 Gradle）。
3. 连接安卓设备或启动模拟器（需要麦克风权限用于实时测声）。
4. `Run 'app'`。

> 说明：仓库未提交 `gradle-wrapper.jar`（二进制）。Android Studio 打开即自动补齐；
> 若用命令行构建，请先 `gradle wrapper` 生成 wrapper。

### 权限
- `RECORD_AUDIO`：用于麦克风实时声压级测量（运行时申请）。
- 平面图照片通过系统选择器读取，无需存储权限。

### 麦克风校准（重要）
手机麦克风未做绝对声压校准，App 读取的是相对值。请在 `网格设置` 中：
1. 用一台**已校准的声级计**在典型环境读出一个参考值；
2. 观察 App 实时读数，将“校准偏移(dB)”设为 `参考值 − 手机读数`；
3. 之后 App 显示值即近似真实 SPL。默认偏移 90 dB，仅作占位，务必校准后再用于报告。

---

## 算法简述

- **IDW 插值**：`value(x,y)=Σ(wᵢ·vᵢ)/Σwᵢ`，`wᵢ=1/dᵢᵖ`（p=2）。适合稀疏、不规则的现场测点。
- **等声压级曲线**：在插值得到的标量场上，对每个分贝等级用 Marching Squares 提取等值线段。
- **噪声源反推**：以 8 邻域严格局部极大值且超过阈值判定为疑似声源，按声级排序，辅助定位主要声源。

---

## License

本项目用于声学环保现场测量，按仓库默认协议开源。
