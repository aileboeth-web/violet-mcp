import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 发布签名（signing/ 目录已加入 .gitignore，不随源码或交接包分发；模板见 config-templates/）：
// - signing/rotation.properties 存在：项目已做过 v3 密钥轮换。Gradle 只产出未签名的 release 包，
//   再由 tools/sign_release.py 用“旧密钥 + 新密钥 + lineage”签名。此时不允许再用单一密钥签名，
//   因为已经装上轮换版本的手机会拒绝只用旧密钥签名的更新。
// - 否则如果有 signing/keystore.properties：用单一密钥签名（全新环境 / 分支项目）。
// - 两者都没有：release 包不签名，可用 debug 包测试。
val rotationFile = rootProject.file("signing/rotation.properties")
val singleKeyFile = rootProject.file("signing/keystore.properties")
if (rotationFile.exists() && singleKeyFile.exists()) {
    throw GradleException(
        "signing/rotation.properties 与 signing/keystore.properties 同时存在：项目已轮换签名密钥，" +
            "请删除或改名 keystore.properties，release 包改用 tools/sign_release.py 签名")
}
val signingProps = Properties().apply {
    if (!rotationFile.exists() && singleKeyFile.exists()) singleKeyFile.inputStream().use { load(it) }
}

android {
    namespace = "com.mcp.server"
    compileSdk = 35

    defaultConfig {
        // 与原项目 com.mcp.server 区分，可并存安装，且不会继承原项目的配置。
        // 1.3.0 起是 com.mcp.server.violet（以前是 com.mcp.server.eve）：系统把它当作新应用，需要重新安装和配对
        applicationId = "com.mcp.server.violet"
        minSdk = 26
        targetSdk = 35
        versionCode = 7
        versionName = "1.4.0"
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        // Robolectric：在 JVM 上模拟 Android 框架（结果只代表“模拟环境”，不代表真机）
        unitTests.isIncludeAndroidResources = true
        unitTests.all { it.maxHeapSize = "2g" }
    }
    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }
}

// APK 文件名：violet-mcp-<版本>-<debug|release>.apk
android.applicationVariants.all {
    val variant = this
    outputs.all {
        (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
            "violet-mcp-${variant.versionName}-${variant.buildType.name}.apk"
    }
}

dependencies {
    implementation(project(":mcp-core"))

    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")

    // 已移除：Shizuku（shell 权限执行命令）、QuickJS（JavaScript 执行）、DocumentFile（文件读写）、Gson
}
