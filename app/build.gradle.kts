import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

// 正式签名：读取项目根目录的 keystore.properties（不提交到仓库）。没有该文件时 release 包退回调试签名。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.mistpeaklab.clipdock"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "com.mistpeaklab.clipdock"
        minSdk = 34
        targetSdk = 36
        versionCode = 9
        versionName = "0.6.0"
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) create("release") {
            storeFile = file(keystoreProps.getProperty("storeFile"))
            storePassword = keystoreProps.getProperty("storePassword")
            keyAlias = keystoreProps.getProperty("keyAlias")
            keyPassword = keystoreProps.getProperty("keyPassword")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    buildFeatures { compose = true }

    testOptions {
        unitTests {
            // 数据层的测试用 Robolectric 在电脑上跑真实的 SQLite，不用连设备
            isIncludeAndroidResources = true
            all {
                // Robolectric 运行时要下载一份安卓框架，走国内镜像
                it.systemProperty("robolectric.dependency.repo.url", "https://maven.aliyun.com/repository/public")
                it.systemProperty("robolectric.dependency.repo.id", "aliyun")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.06.01")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.1")
    // 只在测试时用，不会进安装包
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
}
