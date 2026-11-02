plugins {
    id("com.android.application")
}

android {
    namespace = "com.esurfing.client"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.esurfing.client"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // 体积是第一优先级：R8 + 资源压缩；用 debug 签名即可侧载安装
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.version",
            "META-INF/*.kotlin_module",
            "DebugProbesKt.bin",
            "kotlin/**",
        )
    }
}

// 无任何第三方依赖：界面全部基于 Android Framework 自绘。
// AGP 9 会默认带上 kotlin-stdlib（内置 Kotlin 支持），本工程全是 Java，直接排除，
// 否则 debug 包会白白多出 2MB 的 Kotlin 运行时。
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin")
    exclude(group = "org.jetbrains", module = "annotations")
}

dependencies {
}
