plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

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
