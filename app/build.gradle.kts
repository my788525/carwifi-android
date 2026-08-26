import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.carwifi.app"
    compileSdk = 34

    // ---- 发布签名：凭据取自 gitignore 的 local.properties，绝不入库 ----
    val keystorePropsFile = rootProject.file("local.properties")
    val keystoreProps = Properties().apply {
        if (keystorePropsFile.exists()) load(keystorePropsFile.inputStream())
    }
    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getProperty("RELEASE_STORE_FILE")!!)
            storePassword = keystoreProps.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = keystoreProps.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = keystoreProps.getProperty("RELEASE_KEY_PASSWORD")
        }
    }

    defaultConfig {
        applicationId = "com.carwifi.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 9
        versionName = "1.6.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-service:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.compiler:compiler:1.5.14")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // DataStore (settings persistence)
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // WorkManager：低功耗周期监测（电量阈值 + 夜间模式补发）
    implementation("androidx.work:work-runtime-ktx:2.9.0")

    // NanoHTTPD：车载 HTTP 文件共享服务器（随热点自动起停，轻量、无需 Root）
    implementation("org.nanohttpd:nanohttpd:2.3.1")

    // SAF 文档访问（共享系统目录如 Music/Download，无需 MANAGE_EXTERNAL_STORAGE）
    implementation("androidx.documentfile:documentfile:1.0.1")
}
