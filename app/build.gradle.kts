import java.nio.charset.StandardCharsets

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

private val gitCommitsCount: Int by lazy {
    try {
        val isWindows = System.getProperty("os.name").contains("Windows", ignoreCase = true)
        val processBuilder = when {
            isWindows -> ProcessBuilder("cmd", "/c", "git", "rev-list", "--count", "HEAD")
            else -> ProcessBuilder("git", "rev-list", "--count", "HEAD")
        }
        processBuilder.redirectErrorStream(true)
        processBuilder.start().inputStream.bufferedReader(StandardCharsets.UTF_8).readLine().trim().toInt()
    } catch (_: Exception) {
        1
    }
}

kotlin {
    jvmToolchain(libs.versions.jdk.get().toInt())
}

// 读取 release 上传密钥配置（来自 gradle.properties；密钥库 app/upload-keystore.jks 已在 .gitignore 忽略，不入库）
val uploadStoreFile = project.findProperty("UPLOAD_STORE_FILE")?.toString()
    ?: error("缺少 gradle.properties 的 UPLOAD_STORE_FILE")
val uploadKeyAlias = project.findProperty("UPLOAD_KEY_ALIAS")?.toString()
    ?: error("缺少 gradle.properties 的 UPLOAD_KEY_ALIAS")
val uploadStorePassword = project.findProperty("UPLOAD_STORE_PASSWORD")?.toString()
    ?: error("缺少 gradle.properties 的 UPLOAD_STORE_PASSWORD")
val uploadKeyPassword = project.findProperty("UPLOAD_KEY_PASSWORD")?.toString()
    ?: error("缺少 gradle.properties 的 UPLOAD_KEY_PASSWORD")

android {
    namespace = "com.gudao.coderandroid"
    compileSdk = libs.versions.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "com.gudao.coderandroid"
        minSdk = libs.versions.min.sdk.get().toInt()
        targetSdk = libs.versions.target.sdk.get().toInt()
        versionCode = gitCommitsCount
        versionName = "1.0.0"
    }

    signingConfigs {
        getByName("debug") {
            keyAlias = "myapplication"
            keyPassword = "password"
            storeFile = rootProject.file(".github/debug-key.jks")
            storePassword = "password"
        }
        // 发布用上传密钥（AppGallery 方式二）：用此密钥签名后上传，AGC 校验后重签分发
        create("release") {
            keyAlias = uploadKeyAlias
            keyPassword = uploadKeyPassword
            storeFile = file(uploadStoreFile)
            storePassword = uploadStorePassword
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }
}

base {
    archivesName.set("GudaoCode-v${android.defaultConfig.versionName}(${android.defaultConfig.versionCode})")
}

dependencies {
    implementation(libs.google.material)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling)
    implementation(libs.androidx.core.splashscreen)
}

tasks.register("printVersion") {
    doLast {
        println("VERSION_NAME=${android.defaultConfig.versionName}")
        println("VERSION_CODE=${android.defaultConfig.versionCode}")
    }
}
