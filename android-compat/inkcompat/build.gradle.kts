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

    testImplementation("junit:junit:4.13.2")
}
