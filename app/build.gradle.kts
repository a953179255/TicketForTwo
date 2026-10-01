import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    // AGP 9 内置 Kotlin 支持：不写 org.jetbrains.kotlin.android
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// 发布签名信息从 local.properties 读取（该文件不进 git），密钥文件本体也在仓库外
// （G:\Android\keystore\）。这样签名密码不会出现在任何被提交的文件里。
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
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
        versionCode = 5
        versionName = "0.2.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // 只有 local.properties 里配了密钥信息（本机）才创建；
        // 别人 clone 仓库后没配也能正常构建，只是 release 包不带签名。
        if (keystoreProps.getProperty("t2.storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("t2.storeFile"))
                storePassword = keystoreProps.getProperty("t2.storePassword")
                keyAlias = keystoreProps.getProperty("t2.keyAlias")
                keyPassword = keystoreProps.getProperty("t2.keyPassword")
            }
        }
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
            // 正式版只留 arm64：它是发给真机用的，而隧道程序（cloudflared）单 x86_64
            // 那一份就有 43MB —— 模拟器专用的二进制没有理由跟着分发。（107.9MB → 71.3MB）
            //
            // 关于"正式包能不能在模拟器上验"，实测结论分两半，别混为一谈：
            //  · **界面能验**：本机模拟器镜像 abilist 是 `x86_64,arm64-v8a`（自带 ARM 转译），
            //    只含 arm64 的正式包能装能跑，primaryCpuAbi=arm64-v8a，界面完全正常；
            //  · **"开始分享"验不了**：隧道程序是个**独立可执行文件**，靠 fork+exec 启动，
            //    而模拟器没有内核级 ARM 处理（无 /proc/sys/fs/binfmt_misc）。
            //    实测把 arm64 那份 libcloudflared.so 推到 /data/local/tmp 直接执行 → 段错误；
            //    x86_64 那份同样方式跑 → 正常。⇒ 要跑通整条分享链路必须在模拟器上用 debug 包。
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            signingConfig = signingConfigs.findByName("release")
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
        // 生成 BuildConfig.DEBUG —— 设置页用它判断"要不要露出实验室"。
        // AGP 8 起默认不生成，必须显式打开（不打开的话 BuildConfig 这个类根本不存在，编译不过）。
        buildConfig = true
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // release 构建默认会跑 lintVital（致命问题检查）。本机 Gradle transforms 缓存的
        // 锁文件偶发"拒绝访问"，会把 lintVital 卡死、连带整个 release 构建失败。
        // 致命问题已经通过实机验收兜底，这里先关掉这个检查。
        checkReleaseBuilds = false
    }

    packaging {
        jniLibs {
            // 隧道程序（cloudflared）是一个**可执行文件**，按 native 库的方式打包。
            // 要让它真的能被执行，两个条件缺一不可：
            //  1) useLegacyPackaging = true —— 让它被**解压到磁盘**。Android 10 起禁止从
            //     可写目录执行文件，只有只读的 nativeLibraryDir 例外，而只有解压出来的
            //     才会落在那里；直接 mmap APK 内的版本根本不在文件系统上。
            //  2) 文件名必须是 lib*.so（见 src/main/jniLibs）—— 系统只把这种命名的条目
            //     当 native 库处理并赋予执行权限。
            // 少任何一条，运行时都会得到 "not found" 或 "Permission denied"。
            useLegacyPackaging = true
        }
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

    // 悬浮窗播放器：播"嗅探到的地址"，独立于网页（见 docs/references/yjllq-float-window.md）
    // 不引 media3-ui：画面用 TextureView（SurfaceView 切不了圆角），控制条自绘
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)

    debugImplementation(libs.androidx.ui.tooling)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
