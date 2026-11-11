import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置：keystore.properties 由打包脚本生成
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.pocketcontrol.host"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pocketcontrol.host"
        minSdk = 26
        // 安卓 14+ 禁止 targetSdk>33 的应用读取输入法/无障碍设置（enabled_input_methods 等），
        // 会影响诊断与自动启用无障碍；侧载应用不受商店 targetSdk 限制，故定为 33
        targetSdk = 33
        versionCode = 200
        versionName = "2.0.0"
        buildToolsVersion = "35.0.0"
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    packaging {
        resources.excludes += "META-INF/*.md"
    }

    sourceSets {
        // 主控网页直接引用 master-web 目录，保证交付一致
        getByName("main") {
            assets.srcDir(rootProject.file("../master-web"))
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    // 内置 HTTP + WebSocket 服务器（托管主控网页）
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("org.nanohttpd:nanohttpd-websocket:2.3.1")

    // 中继模式客户端
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // 二维码（会话地址展示）
    implementation("com.google.zxing:core:3.5.3")

    // Shizuku（首次授权 WRITE_SECURE_SETTINGS）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
