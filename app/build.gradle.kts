plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 支持：不写 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.ticketfortwo.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ticketfortwo.app"
        // 33 是刻意的：backdrop 的 lens 折射与 Highlight 边缘光需要 API 33，
        // 低于 33 库会静默降级成普通半透明层（不报错），整套视觉语言就没了。
        // 屏幕声用的 AudioPlaybackCapture 只要 29，所以不构成下限。
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            // libwebrtc 每个 ABI 约 7–16MB，四端全打会到 110MB。
            // 保留 x86_64（模拟器）与 arm64-v8a（真机），砍掉 armeabi-v7a / x86。
            ndk {
                abiFilters += listOf("arm64-v8a", "x86_64")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)

    // 液态玻璃底座
    implementation(libs.backdrop)
    // 预编译 libwebrtc：自带 org.webrtc.ScreenCapturerAndroid（MediaProjection 封装）
    implementation(libs.webrtc)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
