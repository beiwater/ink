# android-compat

Android 13（API 33）上替代 Android 14 `android.graphics.Mesh` 的 Ink 兼容渲染器，独立 Gradle 工程，不参与 Bazel 构建。
依赖 Maven 上官方的 `androidx.ink:*:1.1.0-alpha09`（brush / geometry / strokes / rendering / authoring）。

- 环境：JDK 17+（容器为 21）、Android SDK（`platforms;android-36`、`build-tools;36.0.0`），路径写在 `local.properties` 的 `sdk.dir`（已被 git 忽略）。
- 构建：`gradle assembleRelease`（容器用 `/opt/gradle/bin/gradle`，Gradle 8.14.3，AGP 8.13.2，Kotlin 2.4.21）。
- 单测：`gradle testDebugUnitTest`（JUnit 4）。
- 产物：`inkcompat/build/outputs/aar/`；包名 `com.beiwater.inkcompat`，compileSdk 36，minSdk 26。
- 注意：AGP 9.0.x 需要 Gradle 9.1+，改用 AGP 9 前先升级 Gradle。Maven Central 偶发 429，重跑即可（已下载的会缓存）。
