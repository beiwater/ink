plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

// 发布坐标：com.beiwater:inkcompat。接入方（墨记）通过 `gradle publishToMavenLocal` 取本地产物。
// 墨记用 AGP 9 / Gradle 9.8，本模块用 AGP 8.13.2（Gradle 9.6+ 已移除它依赖的内部 API），所以不能 composite build，只能发布到 mavenLocal。
group = "com.beiwater"
version = "0.1.0"

val inkVersion = "1.1.0-alpha09"

android {
    namespace = "com.beiwater.inkcompat"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        // 设备对拍测试用，见 src/androidTest；CI/本容器只保证它能编译
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    publishing {
        singleVariant("release") { withSourcesJar() }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api("androidx.ink:ink-brush:$inkVersion")
    api("androidx.ink:ink-geometry:$inkVersion")
    api("androidx.ink:ink-strokes:$inkVersion")
    api("androidx.ink:ink-rendering:$inkVersion")
    api("androidx.ink:ink-authoring:$inkVersion")
    // InkInternalOnlyApi 注解所在；读取网格原始缓冲时要 @OptIn，接入方（墨记）也需要能解析到它。
    implementation("androidx.ink:ink-nativeloader:$inkVersion")

    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("junit:junit:4.13.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.beiwater"
                artifactId = "inkcompat"
                version = "0.1.0"
            }
        }
    }
}
