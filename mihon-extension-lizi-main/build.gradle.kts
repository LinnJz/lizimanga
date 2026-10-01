plugins {
    id("com.android.application") version "9.4.0"
}

android {
    namespace = "eu.kanade.tachiyomi.extension.zh.lizi"
    compileSdk = 36

    defaultConfig {
        applicationId = "eu.kanade.tachiyomi.extension.zh.lizi"
        minSdk = 21
        targetSdk = 36

        // versionName 的 "1.6" 前缀会被 mihon 解析为 extensions-lib 版本
        // （ExtensionLoader.SUPPORTED_LIB_VERSIONS = [1.4, 1.6]），不要改。
        versionCode = 2
        versionName = "1.6.2"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/lizi.jks")
            storePassword = "lizi123456"
            keyAlias = "lizi"
            keyPassword = "lizi123456"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // 保持与 release 同包名，避免出现 .debug 后缀导致 mihon 识别成两个扩展
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/*.kotlin_module",
            "META-INF/*.version",
            "kotlin/**",
            "DebugProbesKt.bin",
        )
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    // 全部 compileOnly：运行时由 mihon 宿主提供（扩展 apk 里不打包这些库）
    compileOnly("com.github.mihonapp:extensions-lib:1.6.0-rc1")
    compileOnly("com.squareup.okhttp3:okhttp:5.5.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")
    compileOnly("io.reactivex:rxjava:1.3.8")
    // mihon 宿主自带 injekt（source-api 的 ConfigurableSource 用它取 Context）。
    // 注意：extensions-lib 里的 mihonx.* 是 compileOnly 桩，宿主并不提供实现，禁止使用。
    compileOnly("uy.kohesive.injekt:injekt-core:1.16.1")
}
