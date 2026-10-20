# android-compat

Android 13（API 33）上替代 Android 14 `android.graphics.Mesh` 的 Ink 兼容渲染器，独立 Gradle 工程，不参与 Bazel 构建。
依赖 Maven 上官方的 `androidx.ink:*:1.1.0-alpha09`（brush / geometry / strokes / rendering / authoring）。

- 环境：JDK 17+（容器为 21）、Android SDK（`platforms;android-36`、`build-tools;36.0.0`），路径写在 `local.properties` 的 `sdk.dir`（已被 git 忽略）。
- 构建：`gradle assembleRelease`（容器用 `/opt/gradle/bin/gradle`，Gradle 8.14.3，AGP 8.13.2，Kotlin 2.4.21）。
- 单测：`gradle testDebugUnitTest`（JUnit 4）。
- 产物：`inkcompat/build/outputs/aar/`；包名 `com.beiwater.inkcompat`，compileSdk 36，minSdk 26。
- 注意：AGP 9.0.x 需要 Gradle 9.1+，改用 AGP 9 前先升级 Gradle。Maven Central 偶发 429，重跑即可（已下载的会缓存）。

## 结构

纯逻辑（无 native，JVM 单测覆盖）：

| 文件 | 作用 |
|---|---|
| `PackedLayout` / `VertexDecoder` | 由属性个数与步长推断 packed 布局；packed / unpacked 顶点解码 |
| `ColorMath` / `StampUv` | 顶点颜色（Oklab、HCL、不透明度偏移、颜色函数）；印章 UV 与动画帧 |
| `CompatDecision` | `PaintFeatures` -> 谁来画（COMPAT / OFFICIAL / NONE）、`needsCompat(sdkInt, coats)` |
| `MeshGeometry` | `MeshGeometryBuilder`：原始缓冲 -> 位置/索引/着色列，校验索引与缓冲长度，失败返回 null |
| `MeshEntry` | 几何 + 纹理坐标/顶点颜色的按键缓存；`WeakEntryCache`（按 Mesh）、`VersionedCache`（湿笔按 version） |

Android / Ink 薄层（JVM 不测，靠 `src/androidTest` 设备对拍）：`InkMeshReader`（取原始缓冲，`@InkInternalOnlyApi`）、
`PaintFeaturesReader`（笔刷 -> 纯数据）、`CompatVerticesDrawer` / `StampPaints`（`drawVertices` 与 Paint）、`CompatStrokeRenderer`（实现 `CanvasStrokeRenderer`）。

## 用法

```kotlin
// 与 CanvasStrokeRenderer.create(textureStore) 同样的用法，内部同时持有官方渲染器
val renderer: CanvasStrokeRenderer = CompatStrokeRenderer.create(textureStore)
renderer.draw(canvas, stroke, strokeToScreenMatrix)          // 完成的笔画
renderer.draw(canvas, inProgressStroke, strokeToScreenMatrix) // 湿笔：按 version 缓存，整体重取
```

- API >= 34 一律委托官方；API < 34 只接管“印章纹理 + `SelfOverlap.ANY/ACCUMULATE`”（铅笔 v5）的 coat，其余仍由官方（路径渲染器）画。
- 读不到网格 / 布局无法推断 / 取不到印章位图 / 不支持的特性（多纹理层、非 MODULATE、动画帧数 > 1）时整条笔画委托官方，不抛异常。
- 与官方一致：`strokeToScreenTransform` 不会应用到 `canvas`，调用方先把变换 `concat` 到画布。
- 依赖：本模块把 `ink-nativeloader` 作为 `implementation`；接入方若要 `@OptIn(InkInternalOnlyApi)` 需自行依赖 `androidx.ink:ink-nativeloader`。
- 湿笔：`InProgressStrokesView` 内部写死官方渲染器，无法注入；API < 34 的 v5 湿笔要由接入方自绘（如墨记的 `LiveStroke`）并把本渲染器传给它。
- `create(textureStore, alwaysCompat = true)` 在 API 34+ 也强制走兼容路径，只用于对拍。

## 测试

- `gradle testDebugUnitTest`：纯逻辑单测（解码、颜色、UV、选路判定表、数组装配、缓存）。
- `gradle assembleDebugAndroidTest`：设备对拍测试 `CompatRendererDeviceTest`，本环境只保证编译。在 API 34+ 设备/模拟器上
  `gradle connectedDebugAndroidTest`（仅模拟器）：位置金标（`Mesh.fillPosition`）、完成笔画与湿笔的官方/兼容像素差异（logcat tag `InkCompatDiff`，
  PNG 写在应用缓存目录 `inkcompat-diff/`）、API 任意版本的非空白烟雾测试。

## 待验证清单

以下行为来自文档/源码推断或记忆，没有在设备上验证过，需用上述设备对拍确认：

1. `Canvas.drawVertices` 的 `colors` 语义：按非预乘 sRGB ARGB 解释；与 BitmapShader 采样值逐通道相乘。
2. 纹理坐标直接给纹素坐标（UV × 位图宽高），BitmapShader 不设局部矩阵；是否按此解释待确认。
3. 印章 BitmapShader 用 REPEAT/REPEAT（照官方 `BrushPaintCache`），UV=1 边缘的双线性采样是否有可见差别。
4. `drawVertices` 无边缘抗锯齿；印章位图边缘 alpha 为 0 时是否肉眼无差别。
5. 官方在线性扩展 sRGB 预乘空间插值，`drawVertices` 在 sRGB 编码空间插值；粒子内部同色应无差别，有 HCL 渐变时可能有细微色差。
6. 按属性个数/步长推断 packed 布局（6/7/8/9 对应步长 12/16/16/20）的假设；以及 v5 实际得到的是 8 个还是 9 个属性。
7. 湿笔 unpacked 顶点步长恒为 60 字节（只校验缓冲长度 >= 顶点数 × 60，不校验属性数）；`InProgressStroke.getVersion()` 在 `updateShape` 后一定变化。
8. 每个粒子 4 顶点、顶点色相同、UV 取四角；位置省略官方的抗锯齿外扩后，与官方有亚像素差。
9. 硬件画布每次 `drawVertices` 会拷贝顶点数组，页面上粒子很多时的性能（缓存只避免了重复解码与重算）。
10. 软件画布 / `PdfDocument` 画布对 `drawVertices` 的支持情况未知。
11. 混合笔画（部分 coat 需要兼容）的层级顺序与官方日志噪声；当前墨记没有这种笔刷。
